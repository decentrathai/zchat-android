package co.electriccoin.zcash.ui.screen.walletstartup

import android.content.Context
import android.content.Intent
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.spackle.Twig
import java.io.File

/**
 * The SDK's wallet data database files in [directory] (the SDK's no-backup folder, see [sdkDirectory]).
 *
 * SDK 3.0.0 `DatabaseCoordinator` names the data database `<alias>_<network>_data.sqlite3` in
 * `noBackupFilesDir/co.electricoin.zcash`; WalletCoordinator uses the default alias `zcash_sdk`.
 */
class WalletDataDatabaseFiles(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val rename: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
) : WalletDataDatabase {
    /**
     * Moves the data database first, then each journal file to the same new name plus its own suffix, so SQLite
     * still pairs them. If any move fails, every moved file goes back. Nothing is ever deleted or overwritten.
     */
    override fun setAside(network: ZcashNetwork): Boolean {
        val database = File(directory, "${SDK_ALIAS}_${network.networkName}_$DATA_DB_NAME")
        if (!database.isFile) {
            Twig.error { "WALLET - no wallet data database to set aside" }
            return false
        }
        val setAsideName = freeSetAsideName(database.name)
        val moves =
            listOf(database to File(directory, setAsideName)) +
                JOURNAL_SUFFIXES
                    .map { suffix -> File(directory, database.name + suffix) to File(directory, setAsideName + suffix) }
                    .filter { (from, _) -> from.exists() }
        val done = mutableListOf<Pair<File, File>>()
        for ((from, to) in moves) {
            if (to.exists() || !rename(from, to)) {
                Twig.error { "WALLET - could not set aside ${from.name}; putting every file back" }
                done.asReversed().forEach { (original, moved) ->
                    if (!rename(moved, original)) {
                        Twig.error { "WALLET - could not put ${original.name} back; it is kept as ${moved.name}" }
                    }
                }
                return false
            }
            done += from to to
        }
        Twig.warn { "WALLET - wallet data database set aside as $setAsideName" }
        return true
    }

    /** A set-aside name used by no file yet, so an earlier set-aside is never overwritten. */
    private fun freeSetAsideName(databaseName: String): String {
        val base = "$databaseName$SET_ASIDE_MARKER${now()}"
        return generateSequence(0) { it + 1 }
            .map { n -> if (n == 0) base else "$base-$n" }
            .first { name -> (listOf("") + JOURNAL_SUFFIXES).none { File(directory, name + it).exists() } }
    }

    companion object {
        const val SDK_NO_BACKUP_SUBDIRECTORY = "co.electricoin.zcash"
        const val SDK_ALIAS = "zcash_sdk"
        private const val DATA_DB_NAME = "data.sqlite3"
        internal const val SET_ASIDE_MARKER = ".set-aside-"
        private val JOURNAL_SUFFIXES = listOf("-wal", "-shm", "-journal")

        fun sdkDirectory(context: Context) = File(context.noBackupFilesDir, SDK_NO_BACKUP_SUBDIRECTORY)
    }
}

/** Restarts ZCHAT: opens a fresh task on the launch screen, then ends this process. */
class AppRestarterImpl(
    private val context: Context,
) : AppRestarter {
    override fun restart() {
        // ZC-090-R: the process ends also when the launch throws (a Reset's record is on disk and its wallet is going).
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val component = launch?.component
            if (component != null) {
                context.startActivity(Intent.makeRestartActivityTask(component))
            }
        } finally {
            Runtime.getRuntime().exit(0)
        }
    }
}
