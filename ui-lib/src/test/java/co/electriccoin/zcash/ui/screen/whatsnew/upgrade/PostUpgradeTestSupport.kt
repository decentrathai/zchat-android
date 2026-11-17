package co.electriccoin.zcash.ui.screen.whatsnew.upgrade

import java.io.File
import kotlin.test.assertNotNull

/**
 * The on-disk store, in memory: it survives a "kill" (a new [PostUpgradeNotice] on the same store), like the
 * real SharedPreferences file does. [failWrites] makes every write fail and leave the stored values alone.
 */
internal class InMemoryPostUpgradeStore(
    var state: PostUpgradeState = PostUpgradeState(),
) : PostUpgradeStore {
    var failWrites = false
    var writes = 0

    override fun read(): PostUpgradeState = state

    override fun write(state: PostUpgradeState): Boolean {
        writes++
        if (failWrites) return false
        this.state = state
        return true
    }
}

internal const val V2130 = 21300L

/** One cold start of the app process: a new object over the same store, then the launch decision. */
internal fun InMemoryPostUpgradeStore.coldStart(
    walletExists: Boolean,
    versionCode: Long = V2130,
): PostUpgradeNotice = PostUpgradeNotice(this, versionCode).also { it.onLaunch(walletExists) }

internal val repoRoot: File by lazy {
    var dir: File? = File("").absoluteFile
    while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
        dir = dir.parentFile
    }
    assertNotNull(dir, "repo root not found above the test working dir")
}

internal fun resourceStrings(xml: String): Map<String, String> =
    Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(xml)
        .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
