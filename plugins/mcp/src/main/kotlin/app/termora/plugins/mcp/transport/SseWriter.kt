package app.termora.plugins.mcp.transport

import com.sun.net.httpserver.HttpExchange
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * SSE 帧写入
 */
internal object SseWriter {
    fun writeEvent(output: OutputStream, event: String, data: String) {
        val sb = StringBuilder()
        sb.append("event: ").append(event).append('\n')
        for (line in data.split('\n')) {
            sb.append("data: ").append(line).append('\n')
        }
        sb.append('\n')
        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }
}

/**
 * HTTP 响应辅助
 */
internal fun HttpExchange.respondJson(status: Int, body: String) {
    responseHeaders.add("Content-Type", "application/json")
    responseHeaders.add("Cache-Control", "no-cache")
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
