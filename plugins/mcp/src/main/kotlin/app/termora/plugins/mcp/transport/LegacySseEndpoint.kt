package app.termora.plugins.mcp.transport

import app.termora.plugins.mcp.McpSession
import app.termora.plugins.mcp.McpSessionManager
import app.termora.plugins.mcp.protocol.JsonRpc
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.TimeUnit

/**
 * 旧版 HTTP+SSE 传输（2024-11-05 规范）
 *
 * GET /sse：建立 SSE 流，先发送 endpoint 事件告知客户端提交地址
 * POST /messages?sessionId=x：提交 JSON-RPC 消息，响应通过原 SSE 流返回
 */
internal class LegacySseEndpoint(
    private val handler: app.termora.plugins.mcp.protocol.McpMessageHandler,
    private val mode: Mode,
) : HttpHandler {

    enum class Mode { SSE, MESSAGES }

    override fun handle(exchange: HttpExchange) {
        try {
            when (mode) {
                Mode.SSE -> handleSse(exchange)
                Mode.MESSAGES -> handleMessages(exchange)
            }
        } finally {
            exchange.close()
        }
    }

    private fun handleSse(exchange: HttpExchange) {
        val session = McpSessionManager.create()

        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        val output = exchange.responseBody

        try {
            // 告知客户端 JSON-RPC 提交地址
            SseWriter.writeEvent(output, "endpoint", "/messages?sessionId=${session.id}")

            // 持续推送响应
            while (!session.closed) {
                val message = session.outgoing.poll(15, TimeUnit.SECONDS) ?: continue
                if (message.isEmpty()) break
                SseWriter.writeEvent(output, "message", message)
            }
        } catch (_: Exception) {
            // 客户端断开等 IO 异常
        } finally {
            McpSessionManager.remove(session.id)
        }
    }

    private fun handleMessages(exchange: HttpExchange) {
        val sessionId = queryParam(exchange, "sessionId")
        val session = McpSessionManager.get(sessionId)
        if (session == null) {
            exchange.respondJson(404, "{\"error\":\"Session not found\"}")
            return
        }

        val text = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
        val element = JsonRpc.parse(text)
        if (element == null) {
            // 解析错误也通过 SSE 流返回
            session.outgoing.offer(JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error").toString())
            exchange.respondJson(202, "{}")
            return
        }

        val messages = when (element) {
            is JsonArray -> element.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(element)
            else -> emptyList()
        }

        for (message in messages) {
            val response = handler.handleMessage(session, message)
            if (response != null) {
                session.outgoing.offer(response.toString())
            }
        }

        exchange.respondJson(202, "{}")
    }

    private fun queryParam(exchange: HttpExchange, name: String): String? {
        val query = exchange.requestURI.query ?: return null
        for (pair in query.split('&')) {
            val index = pair.indexOf('=')
            if (index <= 0) continue
            if (pair.substring(0, index) == name) {
                return pair.substring(index + 1)
            }
        }
        return null
    }
}
