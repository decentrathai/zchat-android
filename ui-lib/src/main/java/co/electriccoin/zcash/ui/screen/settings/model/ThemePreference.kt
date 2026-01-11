package co.electriccoin.zcash.ui.screen.settings.model

import co.electriccoin.zcash.ui.design.theme.ThemeMode

enum class ThemePreference {
    SYSTEM,      // Follow system setting
    LIGHT,       // White/Light theme
    DARK,        // Full dark theme
    CYBERPUNK,   // Neon cyan/magenta on deep purple
    DEEP_CYBER;  // Full cyberpunk with circuit patterns, transmission headers, neon glow

    companion object {
        fun fromString(value: String?): ThemePreference {
            return when (value) {
                "system" -> SYSTEM
                "light" -> LIGHT
                "dark" -> DARK
                "cyberpunk" -> CYBERPUNK
                else -> DEEP_CYBER  // Deep Cyber is the default theme
            }
        }
    }

    fun toStorageString(): String {
        return when (this) {
            SYSTEM -> "system"
            LIGHT -> "light"
            DARK -> "dark"
            CYBERPUNK -> "cyberpunk"
            DEEP_CYBER -> "deep_cyber"
        }
    }

    fun displayName(): String {
        return when (this) {
            SYSTEM -> "System"
            LIGHT -> "Light"
            DARK -> "Dark"
            CYBERPUNK -> "Cyberpunk"
            DEEP_CYBER -> "Deep Cyber"
        }
    }

    fun toThemeMode(): ThemeMode {
        return when (this) {
            SYSTEM -> ThemeMode.SYSTEM
            LIGHT -> ThemeMode.LIGHT
            DARK -> ThemeMode.DARK
            CYBERPUNK -> ThemeMode.CYBERPUNK
            DEEP_CYBER -> ThemeMode.DEEP_CYBER
        }
    }
}
