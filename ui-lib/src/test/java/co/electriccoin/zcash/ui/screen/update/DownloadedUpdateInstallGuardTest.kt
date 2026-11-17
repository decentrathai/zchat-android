package co.electriccoin.zcash.ui.screen.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression lock for audit fable-20260915 A.6: the foss updater handed whatever it downloaded
 * straight to the system installer. The update server derives the advertised versionCode from the
 * APK's FILENAME and never reads the file, so a wrong file uploaded under the right name is
 * advertised as new. Every install then loops: prompt, ~100 MB download, reinstall of the SAME
 * build (Android accepts a same-code, same-signature reinstall), prompt again.
 *
 * This skew is not hypothetical: the 2.12.4 file published today reports versionCode 161 inside
 * the APK while the server advertises 21204 for it. A 2.12.4 (161) install is prompted (same name,
 * 21204 > 161), downloads 2.12.4 (161) and reinstalls itself, forever.
 *
 * The guard [isDownloadedUpdateInstallable] reads what the FILE declares
 * (`getPackageArchiveInfo`) and pins:
 *  - same package name as the running app — otherwise the installer puts a SECOND app next to it
 *    (e.g. the release `xyz.zsend.zchat.foss` beside an old `xyz.zsend.zchat.foss.debug` install),
 *    which opens as a second, empty wallet;
 *  - a build number strictly above the installed one — equal is the reinstall loop, lower is a
 *    downgrade Android refuses anyway;
 *  - a release name that is not older than the installed one — a newer code on an older release
 *    would roll the app (and its migrated wallet database) backwards;
 *  - fail closed on an unreadable file or unknown codes.
 */
class DownloadedUpdateInstallGuardTest {
    @Test
    fun `a genuinely newer build of the same app is installed`() {
        assertTrue(
            "2.12.4 (161) -> a file that really is 2.13.0 (21300) must be installed",
            installable(installedName = "2.12.4 (161)", installedCode = 161L, fileName = "2.13.0", fileCode = 21300L)
        )
    }

    @Test
    fun `a rebuilt release with a higher build number is installed over a same-name install`() {
        assertTrue(
            "the waitlist 2.13.0 (181) install must take the real 2.13.0 (21300) file",
            installable(installedName = "2.13.0 (181)", installedCode = 181L, fileName = "2.13.0", fileCode = 21300L)
        )
    }

    @Test
    fun `the published 2_12_4 file does not reinstall itself over 2_12_4`() {
        assertFalse(
            "a file that is 2.12.4 (161) must not be installed over 2.12.4 (161): that is the reinstall loop",
            installable(installedName = "2.12.4 (161)", installedCode = 161L, fileName = "2.12.4", fileCode = 161L)
        )
    }

    @Test
    fun `a stale build uploaded under the 2_13_0 name is not installed over itself`() {
        assertFalse(
            "a pre-scheme 2.13.0 (181) file uploaded as zchat-v2.13.0.apk must not reinstall over 2.13.0 (181)",
            installable(installedName = "2.13.0 (181)", installedCode = 181L, fileName = "2.13.0", fileCode = 181L)
        )
    }

    @Test
    fun `a file for a different package is never installed`() {
        assertFalse(
            "the release app must not be installed beside a foss.debug install as a second, empty wallet",
            isDownloadedUpdateInstallable(
                installedPackageName = "xyz.zsend.zchat.foss.debug",
                installedVersionName = "2.13.0-foss-debug (299)",
                installedVersionCode = 299L,
                downloadedPackageName = "xyz.zsend.zchat.foss",
                downloadedVersionName = "2.13.0",
                downloadedVersionCode = 21300L
            )
        )
    }

    @Test
    fun `an older release is never installed even with a higher build number`() {
        assertFalse(
            "a 2.12.4 file must not replace 2.13.0, whatever build number it carries",
            installable(installedName = "2.13.0 (21300)", installedCode = 21300L, fileName = "2.12.4", fileCode = 21301L)
        )
    }

    @Test
    fun `a file with a lower build number than installed is not installed`() {
        assertFalse(
            "a newer name with a lower build number is a downgrade the installer refuses; do not hand it over",
            installable(installedName = "2.13.0 (21300)", installedCode = 21300L, fileName = "2.13.1", fileCode = 181L)
        )
    }

    @Test
    fun `an unreadable downloaded file is not installed`() {
        assertFalse(
            "getPackageArchiveInfo returned null (corrupt or truncated file): fail closed",
            isDownloadedUpdateInstallable(
                installedPackageName = PACKAGE,
                installedVersionName = "2.12.4 (161)",
                installedVersionCode = 161L,
                downloadedPackageName = null,
                downloadedVersionName = null,
                downloadedVersionCode = 0L
            )
        )
    }

    @Test
    fun `unknown build numbers fail closed`() {
        assertFalse(
            "an unknown installed build number must not let a file through",
            installable(installedName = "2.12.4", installedCode = 0L, fileName = "2.13.0", fileCode = 21300L)
        )
        assertFalse(
            "a file with no build number must not be installed",
            installable(installedName = "2.12.4 (161)", installedCode = 161L, fileName = "2.13.0", fileCode = 0L)
        )
        assertFalse(
            "a file with no version name must not be installed",
            isDownloadedUpdateInstallable(
                installedPackageName = PACKAGE,
                installedVersionName = "2.12.4 (161)",
                installedVersionCode = 161L,
                downloadedPackageName = PACKAGE,
                downloadedVersionName = null,
                downloadedVersionCode = 21300L
            )
        )
    }

    private fun installable(
        installedName: String,
        installedCode: Long,
        fileName: String,
        fileCode: Long
    ) = isDownloadedUpdateInstallable(
        installedPackageName = PACKAGE,
        installedVersionName = installedName,
        installedVersionCode = installedCode,
        downloadedPackageName = PACKAGE,
        downloadedVersionName = fileName,
        downloadedVersionCode = fileCode
    )

    private companion object {
        const val PACKAGE = "xyz.zsend.zchat.foss"
    }
}
