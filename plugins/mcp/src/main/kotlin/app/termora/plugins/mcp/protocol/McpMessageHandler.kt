package app.termora.plugins.mcp.protocol

import app.termora.Application
import app.termora.plugins.mcp.McpSession
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * MCP 协议消息处理核心，两种传输共用
 */
internal class McpMessageHandler(private val toolRegistry: McpToolRegistry) {
    companion object {
        private val log = LoggerFactory.getLogger(McpMessageHandler::class.java)

        /**
         * 支持的协议版本，从新到旧
         */
        val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26")
        const val LATEST_PROTOCOL_VERSION = "2025-06-18"
    }

    /**
     * 处理一条 JSON-RPC 消息
     *
     * @return 响应消息，如果是通知类消息则返回 null
     */
    fun handleMessage(session: McpSession?, message: JsonObject): JsonElement? {
        val method = JsonRpc.method(message)
            ?: return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid Request: missing method")

        val id = JsonRpc.id(message)
        val params = JsonRpc.params(message)

        // 通知类消息（无 id），不响应
        if (id == null) {
            if (log.isDebugEnabled) {
                log.debug("MCP notification: {}", method)
            }
            return null
        }

        // 除 initialize / ping 外的请求需要先完成握手
        if (method != "initialize" && method != "ping" && session == null) {
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Session not found, send 'initialize' first")
        }

        return try {
            when (method) {
                "initialize" -> initialize(id, session, params)
                "ping" -> JsonRpc.result(id, buildJsonObject { })
                "tools/list" -> JsonRpc.result(id, toolRegistry.listTools())
                "tools/call" -> JsonRpc.result(id, toolRegistry.call(params))
                else -> JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (e: Exception) {
            log.error("Unable to handle MCP message: {}", method, e)
            JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, "Internal error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun initialize(id: JsonElement, session: McpSession?, params: JsonObject): JsonObject {
        // 版本协商：支持则回显，否则返回服务器支持的最新版本
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val version = if (requested != null && requested in SUPPORTED_PROTOCOL_VERSIONS) {
            requested
        } else {
            LATEST_PROTOCOL_VERSION
        }

        if (session != null) {
            session.protocolVersion = version
        }

        return JsonRpc.result(id, buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { })
            })
            put("serverInfo", buildJsonObject {
                put("name", "termora")
                put("version", Application.getVersion())
            })
        })
    }
}
