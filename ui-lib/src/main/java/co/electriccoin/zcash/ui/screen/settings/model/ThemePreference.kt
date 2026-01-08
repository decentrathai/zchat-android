package co.electriccoin.zcash.ui.screen.settings.model

import co.electriccoin.zcash.ui.design.theme.ThemeMode

enum class ThemePreference {
    SYSTEM,  // Follow system setting
    LIGHT,   // White/Light theme
    DARK;    // Full dark theme

    companion object {
        fun fromString(value: String?): ThemePreference {
            return when (value) {
                "light" -> LIGHT
                "dark" -> DARK
                else -> SYSTEM
            }
        }
    }

    fun toStorageString(): String {
        return when (this) {
            SYSTEM -> "system"
            LIGHT -> "light"
            DARK -> "dark"
        }
    }

    fun displayName(): String {
        return when (this) {
            SYSTEM -> "System"
            LIGHT -> "Light"
            DARK -> "Dark"
        }
    }

    fun toThemeMode(): ThemeMode {
        return when (this) {
            SYSTEM -> ThemeMode.SYSTEM
            LIGHT -> ThemeMode.LIGHT
            DARK -> ThemeMode.DARK
        }
    }
}
