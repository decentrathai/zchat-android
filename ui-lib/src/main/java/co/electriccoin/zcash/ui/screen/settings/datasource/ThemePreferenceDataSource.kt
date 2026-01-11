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

class ThemePreferenceDataSourceImpl(context: Context) : ThemePreferenceDataSource {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

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
        private const val PREFS_NAME = "zchat_theme_prefs"
        private const val KEY_THEME = "theme_preference"
    }
}
