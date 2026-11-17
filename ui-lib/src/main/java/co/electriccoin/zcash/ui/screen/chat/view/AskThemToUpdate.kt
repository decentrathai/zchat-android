package co.electriccoin.zcash.ui.screen.chat.view

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ZchatShareExclusions

/**
 * Bridge 3 (transition plan section 2.3 and 2.5): "Ask them to update" opens the Android share sheet with the
 * update text (the copy deck's English block, then its Spanish block). ZChat's own share target is excluded,
 * and so is any other installed ZChat build, so the text can't be posted into a chat.
 */
internal fun askThemToUpdateChooser(context: Context): Intent {
    val text =
        context.getString(R.string.upgrade_share_text_en) + "\n\n" + context.getString(R.string.upgrade_share_text_es)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    val resolved =
        runCatching {
            context.packageManager
                .queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { info -> info.activityInfo?.let { it.packageName to it.name } }
        }.getOrDefault(emptyList())
    val excluded =
        ZchatShareExclusions.excluded(context.packageName, resolved).map { (pkg, cls) -> ComponentName(pkg, cls) }.toTypedArray()
    return Intent.createChooser(send, context.getString(R.string.upgrade_ask_them_to_update)).apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded)
    }
}
