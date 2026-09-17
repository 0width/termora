package app.termora.plugins.mcp

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * 插件自带的 i18n 加载器
 *
 * 键在插件 jar 内的 i18n/messages*.properties 中，不依赖主程序 bundle，
 * 以便插件独立分发给旧版本主程序使用
 */
internal object McpI18n {
    private val bundle = ResourceBundle.getBundle(
        "i18n/messages", Locale.getDefault(), McpI18n::class.java.classLoader
    )

    fun getString(key: String, vararg args: Any): String {
        return try {
            val text = bundle.getString(key)
            if (args.isEmpty()) text else MessageFormat.format(text, *args)
        } catch (_: MissingResourceException) {
            key
        }
    }
}
