package com.ridervoice.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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

    fun applyApplicationLocales(langCode: String) {
        val localeList = when (langCode.lowercase()) {
            "hi" -> LocaleListCompat.forLanguageTags("hi")
            "mr" -> LocaleListCompat.forLanguageTags("mr")
            "en" -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun getLocalizedContext(context: Context, langCode: String): Context {
        val locale = getLocaleForLanguage(langCode)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }
}
