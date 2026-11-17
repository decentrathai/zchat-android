package co.electriccoin.zcash.ui.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.util.FaultSeams

/**
 * QA test-infrastructure (DEBUG BUILDS ONLY — `debug` source set, registered only in the debug manifest, not
 * exported). A content provider is created before Application.onCreate, so this re-arms the fault seams saved by
 * the TestHooks receiver before any wallet, chat store or relay code runs. It serves no data.
 */
class FaultSeamsLoader : ContentProvider() {
    override fun onCreate(): Boolean {
        if (!BuildConfig.DEBUG) return true
        val filesDir = context?.filesDir ?: return true
        runCatching { FaultSeamsConfig.read(filesDir) }
            .onSuccess { saved ->
                saved.forEach { (seam, value) -> FaultSeams.arm(seam, value) }
                if (saved.isNotEmpty()) Log.i(TAG, "fault_seams_loaded {armed=${FaultSeamsConfig.render(saved).trim().replace('\n', ',')}}")
            }.onFailure { Log.w(TAG, "fault_seams_loaded {ok=false, reason=${it.javaClass.simpleName}}") }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "TestHooks"
    }
}
