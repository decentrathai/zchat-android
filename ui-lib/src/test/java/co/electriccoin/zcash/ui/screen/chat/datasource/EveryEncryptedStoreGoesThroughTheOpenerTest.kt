package co.electriccoin.zcash.ui.screen.chat.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-733-A (register ZC-733, medium; store-opener design, revision 2a, rule S8, section 3.6 items 1 to 3, 9; class
 * A1): every encrypted store the app opens goes through the one opener with the app's environment and through the one
 * function that opens a file. Today the contact book, the picture index and the AI store build the master key and call
 * the library's create themselves, outside the opener: no typed error, no retry, no count, no kept copy, no guard.
 *
 * A source scan of every module's src/main, comments stripped. A further file that calls the library fails until it
 * is listed here.
 */
class EveryEncryptedStoreGoesThroughTheOpenerTest {
    private val sources = StoreOpenerApi.mainSources()
    private val datasource = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource"
    private val threeStores =
        listOf(
            "$datasource/ContactBookImpl.kt",
            "$datasource/AvatarStore.kt",
            "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiPreferences.kt",
        )
    private val create = Regex("""EncryptedSharedPreferences\s*\.\s*create\(""")
    private val masterKey = Regex("""MasterKey\s*\.\s*Builder\(""")

    @Test
    fun `the three stores call neither the library's create nor the master-key call`() {
        threeStores.forEach { path ->
            val code = sources[path] ?: error("missing $path")
            assertTrue("$path calls the library's create", !create.containsMatchIn(code))
            assertTrue("$path builds a master key", !masterKey.containsMatchIn(code))
        }
    }

    @Test
    fun `each of the three opens through LazyEncryptedStore, which opens through the opener and openEncryptedFile`() {
        threeStores.forEach { path ->
            assertTrue("$path does not open through LazyEncryptedStore", "LazyEncryptedStore(" in sources.getValue(path))
        }
        val holder = sources["$datasource/LazyEncryptedStore.kt"] ?: error("there is no LazyEncryptedStore.kt")
        val opens = StoreOpenerApi.calls(holder, "openEncryptedStore")
        assertTrue("LazyEncryptedStore does not call openEncryptedStore(", opens.isNotEmpty())
        opens.forEach {
            assertTrue("an open of the holder has no env = appStoreOpenEnv(: ${it.arguments}", "env = appStoreOpenEnv(" in it.arguments)
        }
        assertTrue("LazyEncryptedStore does not open the file with openEncryptedFile(", "openEncryptedFile(" in holder)
    }

    @Test
    fun `the library's create is called in ui-lib in one place only, and in the preferences module`() {
        val callers = sources.filter { (_, code) -> create.containsMatchIn(code) }.keys
        assertEquals(
            setOf(
                "$datasource/EncryptedStoreOpener.kt",
                "preference-impl-android-lib/src/main/java/co/electriccoin/zcash/preference/AndroidPreferenceProvider.kt",
            ),
            callers,
        )
    }

    @Test
    fun `the AI store is read from one holder in AiPreferences' companion, and no second holder exists for it`() {
        val ai = sources.getValue("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiPreferences.kt")
        val companion = ai.substringAfter("companion object", "")
        assertTrue("AiPreferences' companion builds no LazyEncryptedStore", "LazyEncryptedStore(" in companion)
        assertEquals("AiPreferences builds more than one LazyEncryptedStore", 1, Regex("""LazyEncryptedStore\(""").findAll(ai).count())
        val others = sources.filter { (path, code) -> path != threeStores[2] && "zchat_ai_prefs" in code }.keys
        assertEquals("another file names the AI store", emptySet<String>(), others)
    }

    @Test
    fun `the seam for the three stores is read in the opener only, beside KEYSTORE_OUT`() {
        val readers = sources.filter { (_, code) -> "FaultSeam.KEYSTORE_OUT_OTHER_STORES" in code }.keys
        assertEquals(setOf("$datasource/EncryptedStoreOpener.kt"), readers)
        val opener = sources.getValue("$datasource/EncryptedStoreOpener.kt")
        val near = opener.substringAfter("FaultSeam.KEYSTORE_OUT)").take(SEAM_DISTANCE)
        assertTrue("KEYSTORE_OUT_OTHER_STORES is not read beside KEYSTORE_OUT", "FaultSeam.KEYSTORE_OUT_OTHER_STORES" in near)
    }

    private companion object {
        const val SEAM_DISTANCE = 600
    }
}
