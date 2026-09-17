package app.termora.plugins.mcp.terminal

import app.termora.terminal.DataKey
import app.termora.terminal.DataListener
import app.termora.terminal.Terminal
import app.termora.terminal.TerminalListener
import app.termora.terminal.VisualTerminal
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 每个 tab 的增量输出缓冲
 *
 * 监听 [VisualTerminal.Written]（原始输出流），剥离 ANSI 转义序列后保留纯文本，支持游标式读取增量
 */
internal class McpTabBuffer(private val terminal: Terminal) : DataListener {
    companion object {
        private const val MAX_SIZE = 256 * 1024
        private const val KEEP_SIZE = 128 * 1024

        /**
         * ANSI 转义序列：CSI、OSC 以及其他两字符转义
         */
        private val ANSI_PATTERN = Regex(
            "\u001B\\[[0-9;:<=>?]*[ -/]*[@-~]" +              // CSI ... final byte
                "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" +  // OSC ... BEL / ST
                "|\u001B[@-Z\\-_]"                                  // 其他两字符转义
        )
    }

    private val sb = StringBuilder()
    private val closed = AtomicBoolean(false)

    /**
     * 最后一次追加输出的时间戳，0 表示 clear 后尚无新输出
     */
    private var lastModifiedAt = 0L

    init {
        terminal.getTerminalModel().addDataListener(this)
        terminal.addTerminalListener(object : TerminalListener {
            override fun onClose(terminal: Terminal) {
                if (closed.compareAndSet(false, true)) {
                    terminal.getTerminalModel().removeDataListener(this@McpTabBuffer)
                }
            }
        })
    }

    override fun onChanged(key: DataKey<*>, data: Any) {
        if (closed.get()) return
        if (key != VisualTerminal.Written) return

        synchronized(sb) {
            sb.append(ANSI_PATTERN.replace(data as String, ""))
            lastModifiedAt = System.currentTimeMillis()
            if (sb.length > MAX_SIZE) {
                sb.delete(0, sb.length - KEEP_SIZE)
            }
        }
    }

    /**
     * 读取并清空增量输出
     */
    fun readIncremental(): String = synchronized(sb) {
        if (sb.isEmpty()) return ""
        val text = sb.toString()
        sb.setLength(0)
        if (text.length >= MAX_SIZE) "[...earlier output truncated...]\n$text" else text
    }

    /**
     * 丢弃未读取的增量输出，并重置静默计时
     */
    fun clear() = synchronized(sb) {
        sb.setLength(0)
        lastModifiedAt = 0L
    }

    /**
     * 距最后一次输出经过的毫秒数；尚无输出时返回 -1
     */
    fun idleMs(): Long = synchronized(sb) {
        if (lastModifiedAt == 0L) -1L
        else System.currentTimeMillis() - lastModifiedAt
    }
}
