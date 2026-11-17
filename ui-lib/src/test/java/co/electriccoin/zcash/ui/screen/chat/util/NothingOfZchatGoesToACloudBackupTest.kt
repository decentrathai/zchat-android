package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-692 (fix-all 2.13.0, train T6b, batch 1 of 4 for ZC-692) — nothing of ZCHAT goes into a cloud backup.
 *
 * The harm: the app's manifest says android:allowBackup="true" and both backup rule files include the folders
 * files/address_book and files/metadata, so Android copies the wallet's Contacts list and its transaction notes
 * into the phone owner's cloud backup and writes them back at the next install. After Destroy All, an uninstall and
 * a new install, a file of the destroyed wallet was back on the phone before the first launch (seen on the Fold,
 * Android 15, 6 Oct 2026).
 *
 * Wanted:
 * - the switch: android:allowBackup="false" on the app's <application>, and no manifest of the repo turns it on
 *   again or brings a backup agent;
 * - the second lock: both rule files exclude every folder Android could copy for the cloud, each of the nine domains
 *   by name (an empty or missing section means "copy everything"), so that the switch turned on by mistake still
 *   sends nothing out;
 * - the phone-to-phone rules of Android 12 and newer stay as they are: the two folders, nothing else.
 *
 * A source scan, like [DestroyCanShowTheUninstallScreenTest]: it reads the manifests, the two rule files and the two
 * storage providers. It cannot see a library's manifest (a different value there is a merge error, so the build is
 * the pin for that), and it does not show what a phone does: the phone check does (bmgr backupnow is refused).
 */
class NothingOfZchatGoesToACloudBackupTest {
    private val appManifestPath = "app/src/main/AndroidManifest.xml"
    private val rulesAndroid11Path = "app/src/main/res/xml/auto_backup_config.xml"
    private val rulesAndroid12Path = "app/src/main/res/xml/auto_backup_config_android_12.xml"

    /** Every domain a rule file can name: the app's five folders and their four device-protected twins. */
    private val nineDomains =
        setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )

    private val twoFolders = setOf("file" to "address_book/.", "file" to "metadata/.")

    private val backupAttributes =
        setOf("android:allowBackup", "android:fullBackupContent", "android:dataExtractionRules")

    private fun parse(file: File): Element =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement

    private fun parse(path: String): Element = parse(File(ChatResourceText.repoRoot, path))

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Element.descendants(): List<Element> = children().flatMap { listOf(it) + it.descendants() }

    private fun Element.attributeNames(): List<String> = (0 until attributes.length).map { attributes.item(it).nodeName }

    private fun Element.rules(tag: String): List<Pair<String, String>> =
        children().filter { it.tagName == tag }.map { it.getAttribute("domain") to it.getAttribute("path") }

    private fun Element.usesRequireFlags(): Boolean =
        (listOf(this) + descendants()).any { "requireFlags" in it.attributeNames() }

    /** The names a tools:replace or tools:remove value lists. */
    private fun Element.listed(attribute: String): List<String> =
        getAttribute(attribute).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Every AndroidManifest.xml under a module's src folder (build output is not under src). */
    private fun sourceManifests(): List<File> =
        ChatResourceText.repoRoot
            .listFiles { f -> f.isDirectory && File(f, "src").isDirectory }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { module ->
                File(module, "src")
                    .walkTopDown()
                    .filter { it.isFile && it.name == "AndroidManifest.xml" }
                    .sortedBy { it.path }
                    .toList()
            }

    private fun appApplication(): Element {
        val applications = parse(appManifestPath).children().filter { it.tagName == "application" }
        assertEquals(1, applications.size, "fixture: one <application> in $appManifestPath")
        return applications.single()
    }

    private fun assertExcludesEverything(
        section: Element,
        where: String,
    ) {
        val includes = section.rules("include")
        assertTrue(includes.isEmpty(), "$where still includes $includes: Android copies it into the cloud backup")
        assertTrue(!section.usesRequireFlags(), "$where uses requireFlags: it works only while the switch is on")
        val excludedWhole = section.rules("exclude").filter { it.second == "." }.map { it.first }
        assertEquals(
            nineDomains,
            excludedWhole.toSet(),
            "$where must exclude path=\".\" for each of the nine domains (a domain that is not named is copied)",
        )
        assertEquals(nineDomains.size, excludedWhole.size, "$where names a domain twice: $excludedWhole")
    }

    @Test
    fun the_switch_is_off_and_no_manifest_of_the_repo_turns_it_on_again() {
        assertEquals(
            "false",
            appApplication().getAttribute("android:allowBackup"),
            "$appManifestPath: android:allowBackup on <application> (Android's switch for cloud backup and restore)",
        )

        val manifests = sourceManifests()
        assertTrue(
            manifests.any { it.relativeTo(ChatResourceText.repoRoot).path == appManifestPath } && manifests.size > 1,
            "fixture: the manifests under every module's src folder (found ${manifests.size})",
        )
        val offenders =
            manifests.flatMap { file ->
                val root = parse(file)
                val name = file.relativeTo(ChatResourceText.repoRoot).path
                (listOf(root) + root.descendants()).flatMap { element ->
                    val found = mutableListOf<String>()
                    if (element.getAttribute("android:allowBackup") == "true") found += "allowBackup=\"true\""
                    if (element.hasAttribute("android:backupAgent")) found += "a backupAgent"
                    if (element.hasAttribute("android:fullBackupOnly")) found += "fullBackupOnly"
                    if (element.getAttribute("android:hasFragileUserData") == "true") {
                        found += "hasFragileUserData=\"true\""
                    }
                    val removed = element.listed("tools:remove").filter { it in backupAttributes }
                    if (removed.isNotEmpty()) found += "tools:remove of $removed"
                    if (element.tagName == "application") {
                        val replaced = element.listed("tools:replace").filter { it != "android:allowBackup" }
                        if (replaced.isNotEmpty()) found += "tools:replace of $replaced on <application>"
                        val node = element.getAttribute("tools:node")
                        if (node == "remove" || node == "replace" || node == "removeAll") {
                            found += "tools:node=\"$node\" on <application>"
                        }
                    }
                    found.map { "$name <${element.tagName}>: $it" }
                }
            }
        assertTrue(offenders.isEmpty(), "a manifest turns the backup on again or takes a backup attribute away: $offenders")
    }

    @Test
    fun the_manifest_names_exactly_the_two_rule_files() {
        // Guard (green before the fix): the two rule files this test reads are the ones Android reads.
        val application = appApplication()
        assertEquals("@xml/auto_backup_config", application.getAttribute("android:fullBackupContent"))
        assertEquals("@xml/auto_backup_config_android_12", application.getAttribute("android:dataExtractionRules"))
    }

    @Test
    fun on_Android_8_1_to_11_the_rule_file_excludes_every_folder() {
        val root = parse(rulesAndroid11Path)
        assertEquals("full-backup-content", root.tagName, "fixture: the root of $rulesAndroid11Path")
        assertExcludesEverything(root, rulesAndroid11Path)
    }

    @Test
    fun on_Android_12_and_newer_the_cloud_section_excludes_every_folder() {
        val cloud = parse(rulesAndroid12Path).children().filter { it.tagName == "cloud-backup" }
        assertEquals(1, cloud.size, "fixture: one <cloud-backup> section in $rulesAndroid12Path")
        assertExcludesEverything(cloud.single(), "$rulesAndroid12Path <cloud-backup>")
        assertEquals(
            "true",
            cloud.single().getAttribute("disableIfNoEncryptionCapabilities"),
            "$rulesAndroid12Path <cloud-backup> lost disableIfNoEncryptionCapabilities=\"true\"",
        )
    }

    @Test
    fun on_Android_12_and_newer_a_phone_to_phone_move_carries_the_two_folders_and_nothing_else() {
        // Guard (green before the fix): the <device-transfer> section is not edited by ZC-692.
        val root = parse(rulesAndroid12Path)
        assertEquals("data-extraction-rules", root.tagName, "fixture: the root of $rulesAndroid12Path")
        assertEquals(
            listOf("cloud-backup", "device-transfer"),
            root.children().map { it.tagName }.sorted(),
            "$rulesAndroid12Path holds one <cloud-backup> and one <device-transfer> section and no third one",
        )
        val transfer = root.children().single { it.tagName == "device-transfer" }
        assertEquals(
            listOf("include", "include"),
            transfer.children().map { it.tagName },
            "$rulesAndroid12Path <device-transfer> holds two includes, no exclude and nothing else",
        )
        assertEquals(twoFolders, transfer.rules("include").toSet(), "$rulesAndroid12Path <device-transfer> includes")
        assertTrue(!transfer.usesRequireFlags(), "$rulesAndroid12Path <device-transfer> uses requireFlags")
    }

    @Test
    fun no_other_copy_of_a_rule_file_can_take_the_place_of_the_two() {
        // Guard (green before the fix): a res/xml-v28 variant or a flavor's own copy would be read instead.
        val copies =
            File(ChatResourceText.repoRoot, "app/src")
                .walkTopDown()
                .filter { it.isFile && it.name.startsWith("auto_backup_config") }
                .map { it.relativeTo(ChatResourceText.repoRoot).path }
                .toSet()
        val variants = copies.filter { "/xml-" in it }
        assertTrue(variants.isEmpty(), "a res/xml-* variant of a backup rule file exists: $variants")
        assertEquals(setOf(rulesAndroid11Path, rulesAndroid12Path), copies, "the backup rule files under app/src")
    }

    @Test
    fun the_two_folders_of_the_rules_are_the_ones_the_app_writes() {
        // Guard (green before the fix): the phone-to-phone includes name the folders the storage providers build.
        val providers = "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider"
        for ((file, folder) in listOf("AddressBookStorageProvider.kt" to "address_book", "MetadataStorageProvider.kt" to "metadata")) {
            val src = ChatResourceText.source("$providers/$file")
            assertTrue(
                "val filesDir = context.filesDir" in src && "File(filesDir, \"$folder\")" in src,
                "$file no longer builds \"$folder\" under the files folder: the <device-transfer> includes name it",
            )
            assertTrue(("file" to "$folder/.") in twoFolders, "fixture: $folder is one of the two folders")
        }
    }
}
