package co.electriccoin.zcash.global

/**
 * ZC-706: which process of the app is the one that starts the app.
 *
 * Android also runs `Application.onCreate` in every further process of the package, for example the
 * crash-report process (`<package>:crash`) that only writes a report. Only the main process may build the
 * dependency graph, open the wallet and start the checkers; a second process doing the same would run a
 * second synchronizer on the same wallet database.
 */
object MainProcess {
    /**
     * True when [processName] is the app's main process, whose name is the [packageName] itself.
     *
     * A name that could not be determined (null) counts as main: reading the name can fail in rare cases,
     * and the app must never refuse to start because of it.
     */
    fun isMain(
        processName: String?,
        packageName: String
    ): Boolean = processName == null || processName == packageName
}
