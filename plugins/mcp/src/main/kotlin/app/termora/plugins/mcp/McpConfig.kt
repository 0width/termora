package app.termora.plugins.mcp

import app.termora.database.DatabaseManager

internal class McpConfig private constructor(databaseManager: DatabaseManager) :
    DatabaseManager.IProperties(databaseManager, "Plugin.MCP") {

    companion object {
        val instance by lazy { McpConfig(DatabaseManager.getInstance()) }

        /**
         * 默认危险命令规则，每行一条，# 开头为注释，regex: 前缀为正则
         */
        const val DEFAULT_DANGEROUS_COMMANDS = """# One rule per line, the command is blocked when it matches.
# Lines starting with # are comments. Prefix with "regex:" to use a regular expression.
rm -rf
rm -fr
mkfs
dd if=
shutdown
poweroff
halt
reboot
init 0
init 6
# Redirect into /dev/ devices (e.g. raw disk), but allow the harmless /dev/null
regex:>\s*/dev/(?!null\b)
:(){:|:&};:
chmod -R 777 /
del /f /s /q
rd /s /q
diskpart
Remove-Item -Recurse -Force"""
    }

    /**
     * 是否启用
     */
    var enabled by BooleanPropertyDelegate(false)

    /**
     * 监听端口
     */
    var port by IntPropertyDelegate(8920)

    /**
     * 危险命令规则，每行一条
     */
    var dangerousCommands by StringPropertyDelegate(DEFAULT_DANGEROUS_COMMANDS)
}
