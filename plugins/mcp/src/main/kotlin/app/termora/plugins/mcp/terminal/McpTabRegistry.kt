package app.termora.plugins.mcp.terminal

import app.termora.TerminalTab
import app.termora.actions.DataProviders
import app.termora.randomUUID
import app.termora.terminal.Terminal
import app.termora.terminal.TerminalListener
import java.util.concurrent.ConcurrentHashMap

/**
 * tabId(UUID) 与 TerminalTab 的映射，tab 关闭时自动清理
 */
internal object McpTabRegistry {
    private val tabs = ConcurrentHashMap<String, TerminalTab>()
    private val buffers = ConcurrentHashMap<String, McpTabBuffer>()

    /**
     * 注册 tab（幂等），返回稳定 id。需要在 EDT 调用
     */
    fun register(tab: TerminalTab): String {
        for ((id, t) in tabs) {
            if (t === tab) return id
        }

        val id = randomUUID()
        tabs[id] = tab

        // 增量输出缓冲 + 终端关闭时清理映射
        val terminal = tab.getData(DataProviders.Terminal)
        if (terminal != null) {
            buffers[id] = McpTabBuffer(terminal)
            terminal.addTerminalListener(object : TerminalListener {
                override fun onClose(terminal: Terminal) {
                    val removed = tabs.entries.removeIf { it.value === tab }
                    if (removed) {
                        buffers.remove(id)
                    }
                }
            })
        }

        return id
    }

    fun get(id: String): TerminalTab? {
        return tabs[id]
    }

    fun getBuffer(id: String): McpTabBuffer? {
        return buffers[id]
    }

    fun unregister(id: String) {
        tabs.remove(id)
        buffers.remove(id)
    }
}
