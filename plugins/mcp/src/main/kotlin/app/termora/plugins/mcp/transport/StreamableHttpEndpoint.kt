package app.termora.plugins.mcp.transport

import app.termora.plugins.mcp.McpSession
import app.termora.plugins.mcp.McpSessionManager
import app.termora.plugins.mcp.protocol.JsonRpc
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import java.util.concurrent.TimeUnit

/**
 * 新版 Streamable HTTP 传输（/mcp）
 *
 * POST：提交 JSON-RPC 消息，响应可以是 application/json 或 SSE 流
 * GET：打开服务器到客户端的 SSE 通知流
 * DELETE：结束会话
 */
internal class StreamableHttpEndpoint(private val handler: app.termora.plugins.mcp.protocol.McpMessageHandler) :
    HttpHandler {

    override fun handle(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod.uppercase()) {
                "POST" -> handlePost(exchange)
                "GET" -> handleGet(exchange)
                "DELETE" -> handleDelete(exchange)
                else -> exchange.sendResponseHeaders(405, -1)
            }
        } finally {
            exchange.close()
        }
    }

    private fun handlePost(exchange: HttpExchange) {
        val text = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
        val element = JsonRpc.parse(text)
        if (element == null) {
            exchange.respondJson(400, JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error").toString())
            return
        }

        // 支持单条消息与批量（JSON 数组）
        val messages = when (element) {
            is JsonArray -> element.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        if (messages.isEmpty()) {
            exchange.respondJson(400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid Request").toString())
            return
        }

        val sessionIdHeader = exchange.requestHeaders.getFirst("Mcp-Session-Id")
        var session: McpSession? = McpSessionManager.get(sessionIdHeader)
        val responses = mutableListOf<JsonElement>()

        for (message in messages) {
            // initialize 建立会话
            if (JsonRpc.method(message) == "initialize" && session == null) {
                session = McpSessionManager.create()
            }
            val response = handler.handleMessage(session, message)
            if (response != null) {
                responses.add(response)
            }
        }

        // initialize 建立的会话下发会话 ID
        if (session != null && sessionIdHeader == null) {
            exchange.responseHeaders.add("Mcp-Session-Id", session.id)
        }

        // 只有通知，没有请求
        if (responses.isEmpty()) {
            exchange.respondJson(202, "{}")
            return
        }

        val body = if (responses.size == 1) {
            responses.first().toString()
        } else {
            buildJsonArray { responses.forEach { add(it) } }.toString()
        }

        val wantsSse = (exchange.requestHeaders.getFirst("Accept") ?: "").contains("text/event-stream", true)
        if (wantsSse) {
            // 以 SSE 流形式返回响应
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.responseHeaders.add("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, 0)
            val output = exchange.responseBody
            if (responses.size == 1) {
                SseWriter.writeEvent(output, "message", responses.first().toString())
            } else {
                for (response in responses) {
                    SseWriter.writeEvent(output, "message", response.toString())
                }
            }
        } else {
            exchange.respondJson(200, body)
        }
    }

    private fun handleGet(exchange: HttpExchange) {
        val session = McpSessionManager.get(exchange.requestHeaders.getFirst("Mcp-Session-Id"))
        if (session == null) {
            exchange.respondJson(404, "{\"error\":\"Session not found\"}")
            return
        }

        val accept = exchange.requestHeaders.getFirst("Accept") ?: ""
        if (!accept.contains("text/event-stream", true)) {
            exchange.sendResponseHeaders(405, -1)
            return
        }

        // 服务器到客户端的通知流
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        val output = exchange.responseBody
        try {
            while (!session.closed) {
                val message = session.outgoing.poll(15, TimeUnit.SECONDS) ?: continue
                if (message.isEmpty()) break
                SseWriter.writeEvent(output, "message", message)
            }
        } catch (_: InterruptedException) {
        } finally {
            McpSessionManager.remove(session.id)
        }
    }

    private fun handleDelete(exchange: HttpExchange) {
        val sessionId = exchange.requestHeaders.getFirst("Mcp-Session-Id")
        if (sessionId == null) {
            exchange.respondJson(400, "{\"error\":\"Missing Mcp-Session-Id\"}")
            return
        }

        McpSessionManager.remove(sessionId)
        exchange.sendResponseHeaders(200, -1)
    }
}
