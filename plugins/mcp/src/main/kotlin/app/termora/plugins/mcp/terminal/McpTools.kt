package app.termora.plugins.mcp.terminal

import app.termora.ApplicationScope
import app.termora.Authentication
import app.termora.AuthenticationType
import app.termora.Host
import app.termora.HostManager
import app.termora.HostTerminalTab
import app.termora.OpenHostActionEvent
import app.termora.PtyHostTerminalTab
import app.termora.TerminalTabbedManager
import app.termora.actions.ActionManager
import app.termora.actions.DataProviders
import app.termora.actions.OpenHostAction
import app.termora.plugins.mcp.McpGuard
import app.termora.terminal.Terminal
import app.termora.terminal.TerminalKeyEvent
import app.termora.plugin.internal.ssh.SSHTerminalTab
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.sshd.client.channel.ClientChannelEvent
import org.slf4j.LoggerFactory
import java.awt.Component
import java.awt.event.KeyEvent
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.EnumSet
import java.util.EventObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * MCP 终端工具实现
 *
 * 所有 Terminal / TerminalTabbedManager 操作必须在 EDT，通过 [edt] 桥接；
 * PtyConnector.write 可在任意线程调用
 */
internal object McpTools {
    private val log = LoggerFactory.getLogger(McpTools::class.java)
    private const val EDT_TIMEOUT_MS = 5_000L

    /**
     * 输出静默阈值：连续这么久没有新输出即视为本轮输出结束
     */
    private const val QUIET_MS = 300L
    private const val POLL_INTERVAL_MS = 50L
    private const val MAX_WAIT_MS = 30_000L

    private class TabContext(
        val tab: app.termora.TerminalTab,
        val terminal: Terminal?,
        val ptyConnector: app.termora.terminal.PtyConnector?,
        val enterBytes: ByteArray,
    )

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return block()
        }

        val future = CompletableFuture<T>()
        SwingUtilities.invokeLater {
            try {
                future.complete(block())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return future.get(EDT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * 解析 tabId，返回 tab 上下文。无效的 id 或无法输入的 tab 抛出 IllegalArgumentException
     */
    private fun resolve(tabId: String): TabContext = edt {
        val tab = McpTabRegistry.get(tabId)
            ?: throw IllegalArgumentException("Terminal tab not found: $tabId")
        val terminal = tab.getData(DataProviders.Terminal)
        val ptyConnector = (tab as? PtyHostTerminalTab)?.getPtyConnector()
        val enterBytes = if (terminal != null && ptyConnector != null) {
            terminal.getKeyEncoder()
                .encode(TerminalKeyEvent(KeyEvent.VK_ENTER))
                .toByteArray(ptyConnector.getCharset())
        } else {
            byteArrayOf('\r'.code.toByte())
        }
        TabContext(tab, terminal, ptyConnector, enterBytes)
    }

    /**
     * 找到 tab 所在的 manager 并激活
     */
    private fun activate(tab: app.termora.TerminalTab) {
        for (windowScope in ApplicationScope.forApplicationScope().windowScopes()) {
            val manager = runCatching { windowScope.get(TerminalTabbedManager::class) }.getOrNull() ?: continue
            if (manager.getTerminalTabs().contains(tab)) {
                manager.setSelectedTerminalTab(tab)
                return
            }
        }
    }

    fun listTabs(): String = edt {
        buildJsonArray {
            for (windowScope in ApplicationScope.forApplicationScope().windowScopes()) {
                val manager = runCatching { windowScope.get(TerminalTabbedManager::class) }.getOrNull() ?: continue
                val selected = manager.getSelectedTerminalTab()
                for (tab in manager.getTerminalTabs()) {
                    // 跳过非终端 tab（如 SFTP 固定标签），它们没有 Terminal
                    val terminal = tab.getData(DataProviders.Terminal) ?: continue
                    val id = McpTabRegistry.register(tab)
                    val host = terminal.getTerminalModel().getData(HostTerminalTab.Host)
                    add(buildJsonObject {
                        put("id", id)
                        put("title", tab.getTitle())
                        put("active", tab === selected)
                        if (host != null) {
                            put("protocol", host.protocol)
                            put("host", host.host)
                            put("port", host.port)
                            put("username", host.username)
                        }
                    })
                }
            }
        }.toString()
    }

    fun readTerminal(tabId: String, full: Boolean, rows: Int): String = edt {
        val tab = McpTabRegistry.get(tabId)
            ?: throw IllegalArgumentException("Terminal tab not found: $tabId")
        val terminal = tab.getData(DataProviders.Terminal)
            ?: throw IllegalArgumentException("Tab has no terminal: $tabId")

        if (full) {
            terminal.getDocument().getText()
        } else {
            val visibleRows = terminal.getTerminalModel().getRows()
            val count = rows.coerceIn(1, visibleRows)
            val sb = StringBuilder()
            for (row in (visibleRows - count + 1)..visibleRows) {
                if (row < 1) continue
                sb.append(terminal.getDocument().getScreenLine(row).getText()).append('\n')
            }
            sb.toString().trimEnd('\n')
        }
    }

    fun sendCommand(tabId: String, command: String, waitMs: Long): String {
        // 安全护栏：命中危险命令规则则拒绝执行
        McpGuard.check(command)

        val context = resolve(tabId)
        val ptyConnector = context.ptyConnector
            ?: throw IllegalArgumentException("Terminal tab does not support input: $tabId")

        // 激活 tab（EDT）
        edt { activate(context.tab) }

        // 丢弃此前累积的输出（上一条命令的残留），只返回本命令执行后的增量
        McpTabRegistry.getBuffer(tabId)?.clear()

        // 写入命令 + 回车
        val charset = ptyConnector.getCharset()
        ptyConnector.write(command.toByteArray(charset))
        ptyConnector.write(context.enterBytes)

        // 等到输出安静（连续无新输出）或达到 waitMs 上限
        val output = awaitOutput(tabId, QUIET_MS, waitMs.coerceIn(0, MAX_WAIT_MS))
        return if (output.isEmpty()) "(no new output)" else output
    }

    /**
     * 读取自上次读取以来的新增输出，可等待输出安静。用于轮询长时命令的进度/结果
     */
    fun readOutput(tabId: String, waitMs: Long, quietMs: Long): String {
        val output = awaitOutput(tabId, quietMs.coerceIn(0, 10_000), waitMs.coerceIn(0, MAX_WAIT_MS))
        return if (output.isEmpty()) "(no new output)" else output
    }

    /**
     * 通过 SSH exec channel 直接执行命令，返回退出码与 stdout/stderr。
     * 全新 shell 上下文：不继承交互会话的目录/环境变量，无法处理交互提示和 TUI
     */
    fun execCommand(tabId: String, command: String, timeoutMs: Long): String {
        // 安全护栏：命中危险命令规则则拒绝执行
        McpGuard.check(command)

        val tab = McpTabRegistry.get(tabId)
            ?: throw IllegalArgumentException("Terminal tab not found: $tabId")
        val session = tab.getData(SSHTerminalTab.SSHSession)
            ?: throw IllegalArgumentException(
                "exec_command is only supported on SSH tabs; use send_command for this tab"
            )

        val charset = (tab as? PtyHostTerminalTab)?.getPtyConnector()?.getCharset() ?: Charsets.UTF_8
        val timeout = Duration.ofMillis(timeoutMs.coerceIn(1_000, 600_000))
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val channel = session.createExecChannel(command)
        channel.out = stdout
        channel.err = stderr

        var timedOut = false
        try {
            channel.open().verify(timeout).await(timeout)
            val events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout)
            timedOut = ClientChannelEvent.TIMEOUT in events
        } finally {
            runCatching { channel.close() }
        }

        return buildJsonObject {
            put("exitCode", channel.exitStatus ?: -1)
            put("output", stdout.toString(charset))
            put("stderr", stderr.toString(charset))
            if (timedOut) put("timedOut", true)
        }.toString()
    }

    /**
     * 等待增量输出安静（连续 quietMs 毫秒无新输出）或超时，然后读取并清空。
     * 尚无输出时（idle=-1）不视为安静，会一直等到超时
     */
    private fun awaitOutput(tabId: String, quietMs: Long, timeoutMs: Long): String {
        val buffer = McpTabRegistry.getBuffer(tabId) ?: return String()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val idle = buffer.idleMs()
            if (idle >= 0 && idle >= quietMs) break
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return buffer.readIncremental()
    }

    fun sendKeys(tabId: String, keys: String): String {
        // 安全护栏：命中危险命令规则则拒绝执行
        McpGuard.check(keys)

        val context = resolve(tabId)
        val ptyConnector = context.ptyConnector
            ?: throw IllegalArgumentException("Terminal tab does not support input: $tabId")
        ptyConnector.write(keys.toByteArray(ptyConnector.getCharset()))
        return "OK"
    }

    fun createTab(
        protocol: String,
        host: String?,
        port: Int?,
        username: String?,
        password: String?,
    ): String = edt {
        val newHost = when (protocol.lowercase()) {
            "local" -> Host(id = "local", name = "Local", protocol = "Local")
            "ssh" -> {
                val address = host ?: throw IllegalArgumentException("Missing required parameter: host")
                Host(
                    name = if (username.isNullOrBlank()) address else "$username@$address",
                    protocol = "SSH",
                    host = address,
                    port = port ?: 22,
                    username = username ?: String(),
                    authentication = if (password.isNullOrBlank()) {
                        Authentication.No
                    } else {
                        Authentication(AuthenticationType.Password, password)
                    },
                )
            }
            else -> throw IllegalArgumentException("Unsupported protocol: $protocol (support: local, ssh)")
        }

        openHostTab(newHost)
    }

    /**
     * 列出已保存的主机（不含文件夹），供 open_host 使用
     */
    fun listHosts(): String = edt {
        buildJsonArray {
            for (host in HostManager.getInstance().hosts()) {
                if (host.isFolder || host.isTemporary) continue
                add(buildJsonObject {
                    put("id", host.id)
                    put("name", host.name)
                    put("protocol", host.protocol)
                    if (host.host.isNotBlank()) put("host", host.host)
                    if (host.port > 0) put("port", host.port)
                    if (host.username.isNotBlank()) put("username", host.username)
                })
            }
        }.toString()
    }

    /**
     * 打开一个已存在的主机（优先 id 精确匹配，其次名称精确匹配，最后名称模糊匹配）
     */
    fun openHost(nameOrId: String): String = edt {
        val keyword = nameOrId.trim()
        val candidates = HostManager.getInstance().hosts()
            .filter { !it.isFolder && !it.isTemporary }

        val host = candidates.firstOrNull { it.id == keyword }
            ?: candidates.firstOrNull { it.name.equals(keyword, ignoreCase = true) }
            ?: candidates.firstOrNull { it.name.contains(keyword, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Host not found: $nameOrId. Available hosts: "
                        + candidates.joinToString(", ") { it.name }
            )

        openHostTab(host)
    }

    /**
     * 打开主机并注册新 tab，返回结果 JSON。需要在 EDT 调用
     */
    private fun openHostTab(host: Host): String {
        val windowScope = ApplicationScope.forApplicationScope().windowScopes().firstOrNull()
            ?: throw IllegalStateException("No terminal window is open")
        val manager = runCatching { windowScope.get(TerminalTabbedManager::class) }.getOrNull()
            ?: throw IllegalStateException("Terminal window is not ready")

        // source 必须是能提供 TerminalTabbedManager / WindowScope 的组件（TerminalTabbed 自身即可）
        val source = manager as Component
        val before = manager.getTerminalTabs().toSet()
        ActionManager.getInstance().getAction(OpenHostAction.OPEN_HOST)
            .actionPerformed(OpenHostActionEvent(source, host, EventObject(source)))

        // 注册新打开的 tab
        val newTab = manager.getTerminalTabs().firstOrNull { it !in before }
            ?: throw IllegalStateException(
                "No terminal tab was created (the host may be a transfer protocol like SFTP)"
            )
        val id = McpTabRegistry.register(newTab)

        return buildJsonObject {
            put("id", id)
            put("title", newTab.getTitle())
            put("protocol", host.protocol)
        }.toString()
    }

    fun closeTab(tabId: String): String = edt {
        val tab = McpTabRegistry.get(tabId)
            ?: throw IllegalArgumentException("Terminal tab not found: $tabId")

        for (windowScope in ApplicationScope.forApplicationScope().windowScopes()) {
            val manager = runCatching { windowScope.get(TerminalTabbedManager::class) }.getOrNull() ?: continue
            if (manager.getTerminalTabs().contains(tab)) {
                manager.closeTerminalTab(tab, disposable = true, reconnect = false)
                break
            }
        }

        McpTabRegistry.unregister(tabId)
        "OK"
    }

    fun activateTab(tabId: String): String = edt {
        val tab = McpTabRegistry.get(tabId)
            ?: throw IllegalArgumentException("Terminal tab not found: $tabId")
        activate(tab)
        "OK"
    }
}
