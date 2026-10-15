package me.rerere.rikkahub.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.os.ConfigurationCompat
import java.util.Locale

/**
 * App 内语言切换：支持简体中文 / 繁体中文 / English / 跟随系统。
 * 由设置页写入 [languageCode]，Activity 在 attachBaseContext 时应用。
 */
object AppLocale {
    @Volatile
    var languageCode: String = "system"

    fun apply(context: Context): Context {
        val code = languageCode
        if (code == "system" || code.isBlank()) return context
        val locale = when (code) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "zh-rTW" -> Locale.TAIWAN
            "en" -> Locale.ENGLISH
            else -> Locale.forLanguageTag(code)
        }
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun systemLanguage(): String = ConfigurationCompat.getLocales(
        Locale.getDefault().let { Configuration().apply { setLocale(it) } }
    ).get(0)?.language ?: "zh"
}
