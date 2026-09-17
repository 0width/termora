package app.termora.plugins.mcp

/**
 * 危险命令导致的拦截异常，消息会作为工具执行结果返回给 AI
 */
internal class DangerousCommandException(message: String) : IllegalArgumentException(message)

/**
 * 安全护栏：发送到终端的命令在命中危险命令规则时拒绝执行
 *
 * 规则格式（每行一条，存于设置中，用户可自行增删）：
 * - 默认子串匹配：命令包含规则文本即拦截（忽略大小写）
 * - 以 `regex:` 开头：按正则表达式匹配
 * - 以 `#` 开头：注释
 */
internal object McpGuard {

    fun check(command: String) {
        for (raw in McpConfig.instance.dangerousCommands.lines()) {
            val rule = raw.trim()
            if (rule.isEmpty() || rule.startsWith("#")) continue

            if (rule.startsWith("regex:", ignoreCase = true)) {
                val pattern = rule.substring("regex:".length).trim()
                val matched = runCatching {
                    Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(command)
                }.getOrDefault(false)
                if (matched) {
                    throw DangerousCommandException(
                        "Blocked by safety guard (regex rule: $pattern). Ask the user to confirm or adjust the rules in Termora settings."
                    )
                }
            } else if (command.contains(rule, ignoreCase = true)) {
                throw DangerousCommandException(
                    "Blocked by safety guard (rule: $rule). Ask the user to confirm or adjust the rules in Termora settings."
                )
            }
        }
    }
}
