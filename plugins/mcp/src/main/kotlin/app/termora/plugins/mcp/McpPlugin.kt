package app.termora.plugins.mcp

import app.termora.ApplicationRunnerExtension
import app.termora.SettingsOptionExtension
import app.termora.plugin.Extension
import app.termora.plugin.ExtensionSupport
import app.termora.plugin.Plugin
import org.slf4j.LoggerFactory

internal class McpPlugin : Plugin {
    companion object {
        private val log = LoggerFactory.getLogger(McpPlugin::class.java)
    }

    private val support = ExtensionSupport()

    override fun getAuthor(): String {
        return "TermoraDev"
    }

    override fun getName(): String {
        return "MCP"
    }

    init {
        support.addExtension(SettingsOptionExtension::class.java) { McpOptionExtension.instance }
        support.addExtension(ApplicationRunnerExtension::class.java) { RunnerExtension() }
    }

    override fun <T : Extension> getExtensions(clazz: Class<T>): List<T> {
        return support.getExtensions(clazz)
    }

    private class RunnerExtension : ApplicationRunnerExtension {
        override fun ready() {
            val config = McpConfig.instance
            if (config.enabled.not()) return

            runCatching { McpServerManager.start(config.port) }
                .onFailure { if (log.isErrorEnabled) log.error("Unable to start MCP server", it) }
        }
    }
}
