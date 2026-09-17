package app.termora.plugins.mcp

import app.termora.OptionsPane
import app.termora.SettingsOptionExtension

internal class McpOptionExtension private constructor() : SettingsOptionExtension {
    companion object {
        val instance by lazy { McpOptionExtension() }
    }

    override fun createSettingsOption(): OptionsPane.Option {
        return McpOptionPane()
    }
}
