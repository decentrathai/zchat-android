package co.electriccoin.zcash.ui.qa

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * P2c plan change 2: the Android 12+ data-extraction rules must cover phone-to-phone transfer.
 *
 * When `auto_backup_config_android_12.xml` has no `<device-transfer>` section, Android copies ALL app
 * data on a device-to-device move — including the EncryptedSharedPreferences files that hold chat keys.
 * Their Android Keystore master key never leaves the old phone, so on the new phone those files cannot be
 * decrypted: the chat stores open broken there (and the old self-heal wiped them). The transfer rules
 * must copy the address book and metadata folders and nothing else — no shared preferences, databases,
 * root or other file paths.
 *
 * ZC-692 (train T6b): the cloud rules no longer copy those two folders; they copy nothing. So the transfer
 * rules are pinned to the two folders by name instead of "the same as the cloud", and a section with no
 * include must exclude every domain by name (a section with neither copies everything).
 */
class DeviceTransferKeepsKeyStoresTest {
    private val rulesFile: File by lazy { appDir.resolve("src/main/res/xml/auto_backup_config_android_12.xml") }

    private fun section(tag: String): Element? {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile)
        val nodes = doc.documentElement.getElementsByTagName(tag)
        return if (nodes.length == 0) null else nodes.item(0) as Element
    }

    private fun rules(section: Element, kind: String): Set<Pair<String, String>> {
        val nodes = section.getElementsByTagName(kind)
        return (0 until nodes.length)
            .map {
                val e = nodes.item(it) as Element
                e.getAttribute("domain") to e.getAttribute("path")
            }.toSet()
    }

    @Test
    fun `the Android 12 rules have a device-transfer section`() {
        assertTrue(
            section("device-transfer") != null,
            "no <device-transfer> section: a phone-to-phone move copies every chat key store without its Keystore key"
        )
    }

    @Test
    fun `device transfer copies exactly the address book and metadata folders and cloud backup copies nothing`() {
        val cloud = section("cloud-backup") ?: fail("no <cloud-backup> section")
        val transfer = section("device-transfer") ?: fail("no <device-transfer> section")

        assertEquals(setOf("file" to "address_book/.", "file" to "metadata/."), rules(transfer, "include"))
        assertEquals(emptySet(), rules(transfer, "exclude"), "device-transfer must stay include-only")
        assertEquals(emptySet(), rules(cloud, "include"), "cloud-backup must include nothing (ZC-692)")
    }

    @Test
    fun `no backup or transfer rule includes shared preferences, databases or the whole data folder`() {
        val forbidden = setOf("sharedpref", "database", "root", "device_sharedpref", "device_database", "device_root")
        val everyDomain = forbidden + setOf("file", "external", "device_file")
        for (tag in listOf("cloud-backup", "device-transfer")) {
            val section = section(tag) ?: continue
            val includes = rules(section, "include")
            val excludedWhole = rules(section, "exclude").filter { it.second == "." }.map { it.first }.toSet()
            assertTrue(
                includes.isNotEmpty() || excludedWhole == everyDomain,
                "<$tag> without includes copies everything unless it excludes every domain by name (has $excludedWhole)"
            )
            includes.forEach { (domain, path) ->
                assertTrue(domain !in forbidden, "<$tag> includes $domain:$path")
                assertTrue(path.isNotBlank() && path != "." && path != "/", "<$tag> includes the whole $domain domain")
            }
        }
    }

    private companion object {
        val appDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var dir: File? = start
            repeat(8) {
                val current = dir ?: return@repeat
                val app = current.resolve("app")
                if (app.resolve("src/main/res/xml").isDirectory) return@lazy app
                if (current.name == "app" && current.resolve("src/main/res/xml").isDirectory) return@lazy current
                dir = current.parentFile
            }
            fail("could not locate the app module from ${start.path}")
        }
    }
}
