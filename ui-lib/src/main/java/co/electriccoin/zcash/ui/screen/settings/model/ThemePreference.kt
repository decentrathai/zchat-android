package co.electriccoin.zcash.ui.screen.settings.model

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ThemeMode

enum class ThemePreference {
    SYSTEM,           // Follow system setting
    LIGHT,            // White/Light theme
    DARK,             // Full dark theme
    ZYPHERPUNK,       // Nightwire dark — cyberpunk: circuit patterns, transmission headers, neon glow
    NIGHTWIRE_LIGHT;  // Nightwire daylight — bone paper, teal-cyan, garnet, forest green

    companion object {
        fun fromString(value: String?): ThemePreference {
            return when (value) {
                "system" -> SYSTEM
                "light" -> LIGHT
                "dark" -> DARK
                "nightwire_light", "daylight" -> NIGHTWIRE_LIGHT
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
            NIGHTWIRE_LIGHT -> "nightwire_light"
        }
    }

    /**
     * Display name as a string resource, so the More row and the theme picker reach a Spanish phone in
     * Spanish (round 4 report 3). The two Nightwire names are product names and read the same in both.
     */
    @StringRes
    fun displayNameRes(): Int {
        return when (this) {
            SYSTEM -> R.string.theme_name_system
            LIGHT -> R.string.theme_name_light
            DARK -> R.string.theme_name_dark
            ZYPHERPUNK -> R.string.theme_name_nightwire_dark
            NIGHTWIRE_LIGHT -> R.string.theme_name_nightwire_light
        }
    }

    fun toThemeMode(): ThemeMode {
        return when (this) {
            SYSTEM -> ThemeMode.SYSTEM
            LIGHT -> ThemeMode.LIGHT
            DARK -> ThemeMode.DARK
            ZYPHERPUNK -> ThemeMode.ZYPHERPUNK
            NIGHTWIRE_LIGHT -> ThemeMode.NIGHTWIRE_LIGHT
        }
    }
}
