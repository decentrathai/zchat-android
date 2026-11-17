package co.electriccoin.zcash.ui.screen.settings.datasource

import android.content.Context
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.settings.model.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ThemePreferenceDataSource {
    val themePreference: StateFlow<ThemePreference>
    fun setTheme(theme: ThemePreference)
}

/**
 * The app-settings file (theme, and the app language of ZC-1103): an app setting a Reset keeps (ResetWipe, R17) and a
 * Destroy deletes.
 */
fun appSettingsPreferences(context: Context): SharedPreferences =
    context.getSharedPreferences(
        APP_SETTINGS_PREFS_NAME,
        Context.MODE_PRIVATE
    )

private const val APP_SETTINGS_PREFS_NAME = "zchat_theme_prefs"

class ThemePreferenceDataSourceImpl(context: Context) : ThemePreferenceDataSource {

    private val prefs: SharedPreferences = appSettingsPreferences(context)

    private val _themePreference = MutableStateFlow(loadTheme())
    override val themePreference: StateFlow<ThemePreference> = _themePreference.asStateFlow()

    private fun loadTheme(): ThemePreference {
        val stored = prefs.getString(KEY_THEME, null)
        return ThemePreference.fromString(stored)
    }

    override fun setTheme(theme: ThemePreference) {
        prefs.edit().putString(KEY_THEME, theme.toStorageString()).apply()
        _themePreference.value = theme
    }

    companion object {
        private const val KEY_THEME = "theme_preference"
    }
}
