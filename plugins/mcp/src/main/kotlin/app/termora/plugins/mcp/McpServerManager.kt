package app.termora.plugins.mcp

import app.termora.plugins.mcp.protocol.McpMessageHandler
import app.termora.plugins.mcp.protocol.McpToolRegistry
import app.termora.plugins.mcp.transport.LegacySseEndpoint
import app.termora.plugins.mcp.transport.StreamableHttpEndpoint
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * MCP HTTP 服务器生命周期管理
 *
 * 仅监听 127.0.0.1，路径：/mcp（Streamable HTTP）、/sse + /messages（旧版 SSE）
 */
internal object McpServerManager {
    private val log = LoggerFactory.getLogger(McpServerManager::class.java)
    private val handler = McpMessageHandler(McpToolRegistry)

    @Volatile
    private var httpServer: HttpServer? = null

    val isRunning: Boolean
        get() = httpServer != null

    @Synchronized
    fun start(port: Int) {
        stop()

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "Termora-MCP").apply { isDaemon = true }
        }
        server.createContext("/mcp", StreamableHttpEndpoint(handler))
        server.createContext("/sse", LegacySseEndpoint(handler, LegacySseEndpoint.Mode.SSE))
        server.createContext("/messages", LegacySseEndpoint(handler, LegacySseEndpoint.Mode.MESSAGES))
        server.start()

        httpServer = server
        if (log.isInfoEnabled) {
            log.info("MCP server started on port {}", port)
        }
    }

    @Synchronized
    fun stop() {
        val server = httpServer ?: return
        httpServer = null
        McpSessionManager.removeAll()
        server.stop(0)
        if (log.isInfoEnabled) {
            log.info("MCP server stopped")
        }
    }

    /**
     * 使用新端口重启，仅在已启用时启动
     */
    @Synchronized
    fun restart(port: Int) {
        stop()
        if (McpConfig.instance.enabled) {
            start(port)
        }
    }
}
