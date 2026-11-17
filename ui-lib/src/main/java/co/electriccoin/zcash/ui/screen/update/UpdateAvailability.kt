package co.electriccoin.zcash.ui.screen.update

/**
 * The "is there a newer build than the one installed?" decision used by [UpdateCheckOverlay],
 * kept as a pure function so it can be unit-tested without Compose or a device.
 *
 * Two keys, in order:
 *  1. the release NAME, compared segment by segment ("2.13.0" is newer than "2.12.4"). Names are
 *     the only thing comparable across the whole install base: versionCode used to be the git
 *     commit count, so an old build can carry a code far above — or far below — today's scheme.
 *     Ordering on the code alone would refuse to update exactly those installs.
 *  2. within one release name, the real installed BUILD NUMBER against the published one, so a
 *     rebuilt "2.13.0" reaches every install that already carries that name (waitlist builds,
 *     hand-installed test builds). The name alone can never answer this.
 *
 * Fails closed: anything unparseable, missing, or non-positive means no prompt rather than a guess,
 * and it never reports an update for a build that is older than the installed one — Android would
 * refuse to install it anyway.
 *
 * @param installedVersionName as rendered by `VersionInfo.new()`, e.g. `"2.13.0 (21300)"`.
 * @param installedVersionCode the real installed `PackageInfo.versionCode`; 0 when unknown.
 * @param publishedVersionName `versionName` from the update server, e.g. `"2.13.0"`.
 * @param publishedVersionCode `versionCode` from the update server, e.g. `21300`.
 */
internal fun isUpdateAvailable(
    installedVersionName: String,
    installedVersionCode: Long,
    publishedVersionName: String,
    publishedVersionCode: Int
): Boolean =
    isNewerRelease(
        installedVersionName = installedVersionName,
        installedVersionCode = installedVersionCode,
        candidateVersionName = publishedVersionName,
        candidateVersionCode = publishedVersionCode.toLong()
    )

/** [isUpdateAvailable]'s ordering: release name first, then build number within one name. */
private fun isNewerRelease(
    installedVersionName: String,
    installedVersionCode: Long,
    candidateVersionName: String,
    candidateVersionCode: Long
): Boolean {
    val installed = parseVersionName(installedVersionName) ?: return false
    val candidate = parseVersionName(candidateVersionName) ?: return false

    val byName = compareVersionNames(candidate, installed)
    if (byName != 0) return byName > 0

    // Same release name, so only the build number can separate the two. Both must be real:
    // PackageManager reports 0 when it has nothing, and an unknown code must never prompt.
    if (installedVersionCode <= 0L || candidateVersionCode <= 0L) return false
    return candidateVersionCode > installedVersionCode
}

/**
 * The "may the file we just downloaded be handed to the system installer?" decision used by
 * [UpdateCheckOverlay] between the download finishing and the install prompt, kept pure so it can
 * be unit-tested without a device.
 *
 * The downloaded identity is what the APK FILE itself declares (`PackageManager.getPackageArchiveInfo`),
 * not what the update server advertised for it.
 *
 * The update server advertises a version derived from the APK's FILENAME and never reads the file,
 * so a wrong file uploaded under the right name is advertised as new. Installing it anyway made
 * every install loop: prompt, ~100 MB download, reinstall of the same build (Android accepts a
 * same-code, same-signature reinstall), prompt again. All of these must hold, or the file is not
 * handed to the installer:
 *  1. the file is readable and is THIS app: same package name. Another package would be installed
 *     beside this one — e.g. the release `xyz.zsend.zchat.foss` next to an old
 *     `xyz.zsend.zchat.foss.debug` — and open as a second, empty wallet.
 *  2. its build number is strictly above the installed one. Equal is the reinstall loop; lower is a
 *     downgrade the system installer refuses anyway. Unknown (0) on either side fails closed.
 *  3. it is a newer release by the same ordering as [isUpdateAvailable]: never an older release name
 *     whatever its build number, since that would roll the app and its migrated wallet database back.
 *
 * @param installedPackageName `Context.packageName` of the running app.
 * @param installedVersionName as rendered by `VersionInfo.new()`, e.g. `"2.13.0 (21300)"`.
 * @param installedVersionCode the real installed `PackageInfo.versionCode`; 0 when unknown.
 * @param downloadedPackageName the archive's package name; null when the file could not be parsed.
 * @param downloadedVersionName the archive's `versionName`; null when absent.
 * @param downloadedVersionCode the archive's `versionCode`; 0 when unknown.
 */
@Suppress("LongParameterList", "ReturnCount")
internal fun isDownloadedUpdateInstallable(
    installedPackageName: String,
    installedVersionName: String,
    installedVersionCode: Long,
    downloadedPackageName: String?,
    downloadedVersionName: String?,
    downloadedVersionCode: Long
): Boolean {
    if (downloadedPackageName == null || downloadedPackageName != installedPackageName) return false
    if (downloadedVersionName == null) return false
    if (installedVersionCode <= 0L || downloadedVersionCode <= installedVersionCode) return false
    return isNewerRelease(
        installedVersionName = installedVersionName,
        installedVersionCode = installedVersionCode,
        candidateVersionName = downloadedVersionName,
        candidateVersionCode = downloadedVersionCode
    )
}

/** Longer than any real version segment; refuses absurd input instead of overflowing Int. */
private const val MAX_SEGMENT_DIGITS = 9

/**
 * Splits a version name into comparable numeric segments, or null when it carries no version at
 * all. Handles `"2.13.0"`, `"2.13.0 (21300)"` as rendered by `VersionInfo`, and debug suffixes
 * such as `"2.13.0-foss-debug"`.
 */
private fun parseVersionName(versionName: String): List<Int>? {
    val raw =
        versionName
            .trim()
            .substringBefore(' ')
            .removePrefix("v")
            .removePrefix("V")

    val segments = mutableListOf<Int>()
    for (part in raw.split('.')) {
        val digits = part.takeWhile { it.isDigit() }
        // Stop at the first segment that does not begin with a number: "2.x" is "2", and a
        // segment too long to be a real component ends the version rather than overflowing.
        if (digits.isEmpty() || digits.length > MAX_SEGMENT_DIGITS) break
        segments.add(digits.toInt())
    }
    return segments.takeIf { it.isNotEmpty() }
}

/** Compares two parsed names segment by segment, treating a missing segment as 0 ("2.13" == "2.13.0"). */
private fun compareVersionNames(
    first: List<Int>,
    second: List<Int>
): Int {
    for (index in 0 until maxOf(first.size, second.size)) {
        val result = first.getOrElse(index) { 0 }.compareTo(second.getOrElse(index) { 0 })
        if (result != 0) return result
    }
    return 0
}
