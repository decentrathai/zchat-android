package co.electriccoin.zcash.ui.screen.settings.model

import androidx.annotation.StringRes
import co.electriccoin.zcash.ui.R
import java.util.Locale

/**
 * ZC-1103: the app's own language choice. SYSTEM_DEFAULT (no override) first, then one entry per locale of
 * app/src/main/res/xml/locales_config.xml, in that file's order (AppLanguageSettingTest pins both).
 */
enum class AppLanguage(
    val tag: String
) {
    SYSTEM_DEFAULT(""),
    ENGLISH("en"),
    SPANISH("es");

    /** The name the picker shows; a language is named in its own language. */
    @StringRes
    fun displayNameRes(): Int =
        when (this) {
            SYSTEM_DEFAULT -> R.string.language_name_system_default
            ENGLISH -> R.string.language_name_en
            SPANISH -> R.string.language_name_es
        }

    companion object {
        /**
         * The entry for a stored or system-reported tag ("es", "es-ES", "en-US"; a list "es,en" uses its first);
         * empty, unknown or unreadable means System default.
         */
        fun fromTag(tag: String?): AppLanguage {
            val first =
                tag
                    ?.split(',')
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
            if (first.isEmpty()) return SYSTEM_DEFAULT
            val language = Locale.forLanguageTag(first).language
            return entries.firstOrNull { it != SYSTEM_DEFAULT && it.tag == language } ?: SYSTEM_DEFAULT
        }
    }
}
