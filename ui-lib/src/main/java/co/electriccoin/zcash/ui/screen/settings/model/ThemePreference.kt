package co.electriccoin.zcash.ui.screen.settings.model

import co.electriccoin.zcash.ui.design.theme.ThemeMode

enum class ThemePreference {
    SYSTEM,      // Follow system setting
    LIGHT,       // White/Light theme
    DARK,        // Full dark theme
    ZYPHERPUNK;  // Full cyberpunk with circuit patterns, transmission headers, neon glow

    companion object {
        fun fromString(value: String?): ThemePreference {
            return when (value) {
                "system" -> SYSTEM
                "light" -> LIGHT
                "dark" -> DARK
                "zypherpunk", "cyberpunk", "deep_cyber" -> ZYPHERPUNK  // Migrate old prefs
                else -> ZYPHERPUNK  // Zypherpunk is the default theme
            }
        }
    }

    fun toStorageString(): String {
        return when (this) {
            SYSTEM -> "system"
            LIGHT -> "light"
            DARK -> "dark"
            ZYPHERPUNK -> "zypherpunk"
        }
    }

    fun displayName(): String {
        return when (this) {
            SYSTEM -> "System"
            LIGHT -> "Light"
            DARK -> "Dark"
            ZYPHERPUNK -> "Zypherpunk"
        }
    }

    fun toThemeMode(): ThemeMode {
        return when (this) {
            SYSTEM -> ThemeMode.SYSTEM
            LIGHT -> ThemeMode.LIGHT
            DARK -> ThemeMode.DARK
            ZYPHERPUNK -> ThemeMode.ZYPHERPUNK
        }
    }
}
