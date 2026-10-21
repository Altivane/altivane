package com.altivane.app.domain

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用内语言（跟随系统 / 中文 / English）：
 * SharedPreferences 记忆 + attachBaseContext 包 Configuration + recreate 生效。
 * 覆盖 API 29+ 全版本（不依赖 appcompat / LocaleManager）。
 */
object AppLang {
    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    private const val FILE = "altivane_prefs"
    private const val KEY = "app_lang"

    fun get(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun set(ctx: Context, lang: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, lang).apply()
    }

    /** 语言 = system 时不包装（跟随系统）；否则强制指定 locale */
    fun wrap(base: Context): Context {
        val lang = get(base)
        if (lang == SYSTEM) return base
        val loc = Locale.forLanguageTag(lang)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        return base.createConfigurationContext(cfg)
    }
}
