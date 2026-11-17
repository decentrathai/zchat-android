package co.electriccoin.zcash.ui.screen.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression lock for the P0 that keeps stranded users stranded: the in-app updater (foss flavour
 * only, [co.electriccoin.zcash.ui.BuildConfig.IS_SELF_UPDATE_ENABLED]) decided whether a newer
 * build exists by rebuilding a code from the installed version NAME
 * (`major*10000 + minor*100 + patch`) and comparing the server's real `versionCode` against that.
 * The installed `PackageInfo.versionCode` was read into `VersionInfo.versionCode` and then thrown
 * away.
 *
 * Consequence: any install already carrying the name "2.13.0" — a waitlist build, a hand-installed
 * test build, anything built before the versionCode scheme was fixed — synthesises 21300 from its
 * own name, sees the published 21300, and is told it is up to date forever. Those are exactly the
 * users who have been unable to send since 2026-07-28 and who can only be reached by this prompt.
 *
 * The behaviour these tests pin: the release NAME orders releases (so an install whose historical
 * commit-count versionCode is *higher* than the published one is still offered the newer release —
 * codes used to be the git commit count, e.g. 181, and were never comparable across schemes), and
 * the BUILD NUMBER decides within one release name. Anything unparseable fails closed: no prompt,
 * no crash, and never an offer to move backwards.
 */
class UpdatePromptVersionTest {
    @Test
    fun `same name but a higher build number must offer the update`() {
        // The stranded waitlist install: name "2.13.0", commit-count era code 181.
        assertTrue(
            "an install named 2.13.0 with build 181 must be offered the published 2.13.0 build 21300",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (181)",
                installedVersionCode = 181L,
                publishedVersionName = "2.13.0",
                publishedVersionCode = 21300
            )
        )
    }

    @Test
    fun `same name and a higher build number must offer the update even with a debug name suffix`() {
        assertTrue(
            "a hand-installed 2.13.0 debug build must still be offered the published 2.13.0 build",
            isUpdateAvailable(
                installedVersionName = "2.13.0-foss-debug (299)",
                installedVersionCode = 299L,
                publishedVersionName = "2.13.0",
                publishedVersionCode = 21300
            )
        )
    }

    @Test
    fun `same name and the same build number must not offer the update`() {
        assertFalse(
            "the shipped 2.13.0 install must not be prompted to reinstall itself",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (21300)",
                installedVersionCode = 21300L,
                publishedVersionName = "2.13.0",
                publishedVersionCode = 21300
            )
        )
    }

    @Test
    fun `a lower published build number than installed must not offer the update`() {
        assertFalse(
            "a published build older than the installed one is a downgrade, never a prompt",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (21400)",
                installedVersionCode = 21400L,
                publishedVersionName = "2.13.0",
                publishedVersionCode = 21300
            )
        )
    }

    @Test
    fun `an older published release name must not offer the update whatever its build number`() {
        // A stale commit-count-era code left on the server must not drag users backwards.
        assertFalse(
            "2.12.4 must never be offered to a 2.13.0 install, even with a larger build number",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (21300)",
                installedVersionCode = 21300L,
                publishedVersionName = "2.12.4",
                publishedVersionCode = 99999
            )
        )
    }

    @Test
    fun `a newer published release name must offer the update`() {
        assertTrue(
            "the 2.12.4 installs stuck since 2026-07-28 must be offered 2.13.0",
            isUpdateAvailable(
                installedVersionName = "2.12.4 (299)",
                installedVersionCode = 299L,
                publishedVersionName = "2.13.0",
                publishedVersionCode = 21300
            )
        )
    }

    @Test
    fun `an installed build number above the published one still gets a newer release name`() {
        // The historical hazard: versionCode used to be the git commit count, so an older build can
        // carry a HIGHER code than 21300. Ordering on the code alone would silently refuse to
        // update precisely these installs.
        assertTrue(
            "a 2.12.4 install with historical code 31000 must still be offered 2.13.0 (21300)",
            isUpdateAvailable(
                installedVersionName = "2.12.4 (31000)",
                installedVersionCode = 31000L,
                publishedVersionName = "2.13.0",
                publishedVersionCode = 21300
            )
        )
    }

    @Test
    fun `an unknown installed version must fail safe and not prompt`() {
        // VersionInfo.new() renders "null" and code 0 when PackageManager gives it nothing.
        assertFalse(
            "an unreadable installed version must not trigger a prompt",
            isUpdateAvailable(
                installedVersionName = "null",
                installedVersionCode = 0L,
                publishedVersionName = "2.13.0",
                publishedVersionCode = 21300
            )
        )
        assertFalse(
            "a blank installed version must not trigger a prompt",
            isUpdateAvailable(
                installedVersionName = "",
                installedVersionCode = 0L,
                publishedVersionName = "2.13.0",
                publishedVersionCode = 21300
            )
        )
    }

    @Test
    fun `a malformed published version must fail safe and not prompt`() {
        assertFalse(
            "a published version name that is not a version must not trigger a prompt",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (21300)",
                installedVersionCode = 21300L,
                publishedVersionName = "not-a-version",
                publishedVersionCode = 99999
            )
        )
        assertFalse(
            "an empty published version name must not trigger a prompt",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (21300)",
                installedVersionCode = 21300L,
                publishedVersionName = "",
                publishedVersionCode = 99999
            )
        )
    }

    @Test
    fun `absurd version input must not crash the check`() {
        // Anything here throwing would take down the first-composition LaunchedEffect.
        assertFalse(
            "an out-of-range numeric segment must not crash and must not prompt",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (21300)",
                installedVersionCode = 21300L,
                publishedVersionName = "99999999999999999999.0.0",
                publishedVersionCode = 99999
            )
        )
        assertFalse(
            "a negative published build number must not prompt",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (21300)",
                installedVersionCode = 21300L,
                publishedVersionName = "2.13.0",
                publishedVersionCode = -1
            )
        )
        assertFalse(
            "separators alone must not crash and must not prompt",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (21300)",
                installedVersionCode = 21300L,
                publishedVersionName = "...",
                publishedVersionCode = 99999
            )
        )
    }

    @Test
    fun `a shorter published name compares by segment, not by string length`() {
        assertTrue(
            "3.0 is newer than 2.13.0",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (21300)",
                installedVersionCode = 21300L,
                publishedVersionName = "3.0",
                publishedVersionCode = 30000
            )
        )
        assertFalse(
            "2.13 and 2.13.0 are the same release, so the build number decides",
            isUpdateAvailable(
                installedVersionName = "2.13.0 (21300)",
                installedVersionCode = 21300L,
                publishedVersionName = "2.13",
                publishedVersionCode = 21300
            )
        )
    }
}
