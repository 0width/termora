package app.termora.plugins.mcp

import app.termora.randomUUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * MCP 会话
 *
 * @param id 会话 ID
 */
internal class McpSession(val id: String) {

    @Volatile
    var protocolVersion: String = "2025-06-18"

    /**
     * 待通过 SSE 推送给客户端的消息队列
     */
    val outgoing = LinkedBlockingQueue<String>()

    @Volatile
    var closed = false
        private set

    fun close() {
        if (closed) return
        closed = true
        // 唤醒阻塞在队列上的读取线程
        outgoing.offer(String())
    }
}

/**
 * 会话管理
 */
internal object McpSessionManager {
    private val sessions = ConcurrentHashMap<String, McpSession>()

    fun create(): McpSession {
        val session = McpSession(randomUUID())
        sessions[session.id] = session
        return session
    }

    fun get(id: String?): McpSession? {
        if (id.isNullOrBlank()) return null
        val session = sessions[id] ?: return null
        if (session.closed) {
            remove(session.id)
            return null
        }
        return session
    }

    fun remove(id: String) {
        sessions.remove(id)?.close()
    }

    fun removeAll() {
        for (id in sessions.keys) {
            remove(id)
        }
    }
}
