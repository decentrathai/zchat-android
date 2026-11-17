package co.electriccoin.zcash.ui.screen.settings.datasource

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * ZC-1103: the real [AppLocaleApplier].
 *
 * API 33+: Android's per-app language ([LocaleManager.setApplicationLocales]); Android keeps it across restarts and
 * shows it in its own settings. MainActivity declares `locale` in configChanges, so the running screen gets the new
 * configuration and Compose redraws its texts without a restart.
 *
 * Below API 33 there is no per-app language in Android: [AppLanguageDataSourceImpl] stores the tag, MainActivity
 * reads it in attachBaseContext through [AppLocaleOverride.wrap] and recreates itself when the choice changes.
 */
class AndroidAppLocaleApplier(
    private val context: Context
) : AppLocaleApplier {
    override fun apply(tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }
}

object AppLocaleOverride {
    /** True where the app keeps and applies its own override (below API 33). */
    val isOwnOverride: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /** API 33+: the per-app language Android runs the app with ("" = System default); null below 33. */
    fun runningTag(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context
                .getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.toLanguageTags()
                .orEmpty()
        } else {
            null
        }

    /** The tag stored in the app settings ("" = System default). */
    fun storedTag(context: Context): String = AppLanguageDataSourceImpl.storedLanguage(appSettingsPreferences(context)).tag

    /** Below API 33: [base] with the stored language, or [base] itself when none is stored; API 33+: [base]. */
    fun wrap(base: Context): Context {
        if (!isOwnOverride) return base
        val tag = storedTag(base)
        if (tag.isEmpty()) return base
        // A delta, not a copy of base's configuration: only the locale (and its layout direction) is overridden, so
        // rotation, screen size and font scale still follow the phone (MainActivity handles those changes itself).
        val delta = Configuration()
        delta.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(delta)
    }
}
