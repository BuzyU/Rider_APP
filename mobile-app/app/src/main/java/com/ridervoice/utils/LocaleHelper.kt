package com.ridervoice.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    fun getLocaleForLanguage(langCode: String): Locale {
        return when (langCode.lowercase()) {
            "hi" -> Locale("hi")
            "mr" -> Locale("mr")
            "en" -> Locale.ENGLISH
            else -> Locale.getDefault()
        }
    }

    fun getLocalizedContext(context: Context, langCode: String): Context {
        val locale = getLocaleForLanguage(langCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }
}
