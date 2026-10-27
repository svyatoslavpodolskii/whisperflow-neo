package com.pimax.whisperflow.sync

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object UiLanguage {
    fun code(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales != null && !locales.isEmpty) return locales[0].language
        }
        return context.getSharedPreferences("ui_language", Context.MODE_PRIVATE).getString("code", "ru") ?: "ru"
    }
    fun wrap(context: Context): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(code(context)))
    })
    fun set(context: Context, code: String) {
        require(code in listOf("ru", "en"))
        context.getSharedPreferences("ui_language", Context.MODE_PRIVATE).edit().putString("code", code).commit()
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(code)
        NeoApplication.textContext = wrap(context.applicationContext)
    }
}

class NeoApplication : Application() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(UiLanguage.wrap(base))
    override fun onCreate() { super.onCreate(); textContext = UiLanguage.wrap(applicationContext) }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        textContext = UiLanguage.wrap(applicationContext)
    }
    companion object { internal lateinit var textContext: Context }
}

/** Android string resources for legacy callbacks as well as Compose; never holds an Activity. */
fun uiText(id: Int, vararg args: Any): String = NeoApplication.textContext.getString(id, *args)
