package co.electriccoin.zcash.ui.screen.settings.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.settings.model.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Applies a language tag to the running app; "" clears the app's override (System default). */
fun interface AppLocaleApplier {
    fun apply(tag: String)
}

/** ZC-1103: the language chosen in More, kept across restarts. */
interface AppLanguageDataSource {
    val appLanguage: StateFlow<AppLanguage>

    /** Applies [language] at once and stores it. */
    fun setLanguage(language: AppLanguage)

    /**
     * Takes over a tag the app is already running with (Android's per-app language setting on API 33+) without
     * applying it again, so the More row shows a change made outside the app.
     */
    fun adoptRunningTag(tag: String)
}

class AppLanguageDataSourceImpl(
    private val prefs: SharedPreferences,
    private val applier: AppLocaleApplier,
) : AppLanguageDataSource {
    private val _appLanguage = MutableStateFlow(storedLanguage(prefs))
    override val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    override fun setLanguage(language: AppLanguage) {
        store(language)
        _appLanguage.value = language
        applier.apply(language.tag)
    }

    override fun adoptRunningTag(tag: String) {
        val language = AppLanguage.fromTag(tag)
        if (language == _appLanguage.value) return
        store(language)
        _appLanguage.value = language
    }

    private fun store(language: AppLanguage) {
        val editor = prefs.edit()
        if (language == AppLanguage.SYSTEM_DEFAULT) {
            editor.remove(KEY_APP_LANGUAGE)
        } else {
            editor.putString(KEY_APP_LANGUAGE, language.tag)
        }
        // commit, not apply: below API 33 the activity is recreated right after and reads this in attachBaseContext.
        editor.commit()
    }

    companion object {
        /** Key in the app-settings file ([appSettingsPreferences]); absent = System default. */
        const val KEY_APP_LANGUAGE = "app_language"

        fun storedLanguage(prefs: SharedPreferences): AppLanguage = AppLanguage.fromTag(prefs.getString(KEY_APP_LANGUAGE, null))
    }
}
