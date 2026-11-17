package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.KeptNamesApi
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.chainTip
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver.plaintextInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.START_HEIGHT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.kexMemoFrom
import co.electriccoin.zcash.ui.screen.enhanceddestroy.KeptNamesScreenApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * DESTROY-ADV-N (register ZC-722; owner W-41, W-43, W-47, W-52; Destroy Advanced spec revision 2c, decision B,
 * sections 4.1 to 4.4 and 10.1) — a kept name is a NAME, not a trust decision.
 *
 * A Destroy with the "keep names" tick (batch DESTROY-ADV-W) copies names and addresses into one list under a key of
 * its own and deletes the contact book. The next wallet's code that decides whether a first key exchange waits for
 * Accept, whether a group invitation needs Join and whether anything is paid reads the contact book and NEVER the
 * list; only the user's own "Save as contact" tap, one name per tap, moves a name into the contact book.
 *
 *  (1) source: the list's class is named only by its own file, the kept-names screen's files, AdvancedSettingsVM.kt,
 *      DestroyManager.kt and DestroyWipe.kt; never by ChatViewModel.kt, GroupViewModel.kt, the relay bridge,
 *      SyncForegroundService.kt, ZchatComposeVM.kt, AndroidSharePicker.kt or ContactBookImpl.kt. FAILS today: no list.
 *  (2) the screen's view model, three kept names and a fake contact book: nothing is saved before a tap; "Save as
 *      contact" adds that one address (chat contact book and the wallet's Contacts list, chain "zcash") and takes it
 *      off the list; a contact-book write that does not take leaves it on the list; nothing saves more than one entry
 *      per call. FAILS today: no screen.
 *  (3) CONTROLS on the real ChatViewModel, with a list that names address A and an empty contact book: a key exchange
 *      from A is stored and waits for Accept and nothing is sent or paid; a group invitation from A with no
 *      authenticated sender waits for Join and nothing is paid. Pass before and after.
 *  (4) guard: isFirstContact's body is the three conditions it has today. Passes before and after.
 *  (5) first-contact rows (FIRST-CONTACT-ROWS-FOR-RESET-AND-DESTROY.md): "Save as contact" writes none of the
 *      first-contact marks (keyclaim:, kexasked:, own:, rekeyed_after_rebuild): the screen is given no chat store.
 *
 * All addresses and names are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AKeptNameIsNotATrustDecisionTest {
    private val sendScopes = SendHarnessScopes()
    private lateinit var folder: File

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        folder = Files.createTempDirectory("kept-names-trust").toFile()
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        folder.deleteRecursively()
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    // ---- (1) who may name the list ------------------------------------------------------------------------------------

    @Test
    fun `source - the list's class is named only by its own file, its screen, Advanced Settings and the Destroy code`() {
        val root = ChatResourceText.repoRoot
        assertTrue("there is no ${KeptNamesApi.SOURCE}", File(root, KeptNamesApi.SOURCE).isFile)
        val word = Regex("""\bKeptNames\b""")
        val main = File(root, "ui-lib/src/main/java")
        val naming =
            main
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { word.containsMatchIn(SourceScan.stripComments(it.readText())) }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        val allowed =
            naming.filter { path ->
                path == KeptNamesApi.SOURCE ||
                    path.endsWith("/advancedsettings/AdvancedSettingsVM.kt") ||
                    path.endsWith("/chat/util/DestroyManager.kt") ||
                    path.endsWith("/chat/util/DestroyWipe.kt") ||
                    (path.contains("/screen/enhanceddestroy/") && "KeptNames" in path.substringAfterLast('/'))
            }
        assertEquals("a file outside the allowed set names the kept list", emptyList<String>(), naming - allowed.toSet())
        for (forbidden in FORBIDDEN) {
            val file = File(root, forbidden)
            assertTrue("fixture: $forbidden exists", file.isFile)
            assertFalse("$forbidden names the kept list", word.containsMatchIn(SourceScan.stripComments(file.readText())))
        }
    }

    // ---- (2) the screen's Save as contact ----------------------------------------------------------------------------

    @Test
    fun `Save as contact saves the one tapped name and only then takes it off the list`() {
        val store = threeKeptNames()
        val book = KeptNamesScreenApi.Book()
        val addressBook = KeptNamesScreenApi.AddressBook()
        val vm = KeptNamesScreenApi.viewModel(store, book, addressBook)

        assertTrue("before any tap the contact book is as it was", book.contacts.isEmpty())
        assertTrue("before any tap nothing was added", book.adds.isEmpty())
        assertTrue("before any tap the wallet's Contacts list is as it was", addressBook.saves.isEmpty())
        assertEquals(listOf(A, B, C), KeptNamesScreenApi.shownAddresses(vm))

        KeptNamesScreenApi.call(vm, "onSaveAsContact", B)

        assertEquals("one tap adds that one address to the contact book", listOf(B), book.adds)
        assertEquals("with its kept name", "Bo", book.contacts[B])
        assertEquals("and to the wallet's Contacts list, chain zcash", listOf(Triple("Bo", B, "zcash")), addressBook.saves)
        assertEquals("nothing else is asked of the wallet's Contacts list", emptyList<String>(), addressBook.other)
        assertEquals(
            "the saved name is off the list",
            setOf(A to "Ann", C to "Cy"),
            KeptNamesApi.entries(KeptNamesApi.read(store)).toSet()
        )
        assertEquals(listOf(A, C), KeptNamesScreenApi.shownAddresses(vm))
        assertEquals(KeptNamesScreenApi.stringId("kept_names_saved"), KeptNamesScreenApi.notice(vm))
    }

    @Test
    fun `a contact-book write that does not take leaves the entry on the list`() {
        val store = threeKeptNames()
        val book = KeptNamesScreenApi.Book(takes = false)
        val vm = KeptNamesScreenApi.viewModel(store, book, KeptNamesScreenApi.AddressBook())

        KeptNamesScreenApi.call(vm, "onSaveAsContact", A)

        assertEquals("the write was tried once", listOf(A), book.adds)
        assertEquals(
            "a write that did not take leaves the name on the list",
            setOf(A to "Ann", B to "Bo", C to "Cy"),
            KeptNamesApi.entries(KeptNamesApi.read(store)).toSet()
        )
        assertEquals(listOf(A, B, C), KeptNamesScreenApi.shownAddresses(vm))
        assertEquals(KeptNamesScreenApi.stringId("kept_names_save_failed"), KeptNamesScreenApi.notice(vm))
    }

    @Test
    fun `nothing saves more than one entry per call, and there is no save all`() {
        val store = threeKeptNames()
        val book = KeptNamesScreenApi.Book()
        val addressBook = KeptNamesScreenApi.AddressBook()
        val vm = KeptNamesScreenApi.viewModel(store, book, addressBook)

        KeptNamesScreenApi.call(vm, "onSaveAsContact", A)
        assertEquals(1, book.adds.size)
        assertEquals(1, addressBook.saves.size)
        KeptNamesScreenApi.call(vm, "onSaveAsContact", A)
        assertEquals("a name already saved is not saved again", 1, book.adds.size)

        val vmClass = vm.javaClass
        val saveAll = vmClass.methods.filter { Regex("(?i)save.*all|all.*save|import").containsMatchIn(it.name) }
        assertEquals("the screen offers no save all", emptyList<String>(), saveAll.map { it.name })
        val source = KeptNamesScreenApi.vmSource()
        assertEquals("the view model adds a contact in one place", 1, Regex("""\.addContact\(""").findAll(source).count())
        assertFalse("the contact book is written in a loop", Regex("""(forEach|for \()[^\n]*\n[^\n]*addContact""").containsMatchIn(source))
    }

    // ---- (3) controls on the real ChatViewModel -------------------------------------------------------------------------

    @Test
    fun `control - a key exchange from a kept address waits for Accept and nothing is sent or paid`() =
        runBlocking {
            val kept = SyntheticUnifiedAddress.mainnet(seed = 7221)
            keepIfTheListExists(kept)
            val prefs = HandshakePrefs(kept, firstContact = true)
            val seam = HandshakeSeam(kept)
            val vm = handshakeViewModel(prefs, seam)

            handleKexMemo(vm, kexMemoFrom(kept, E2EEncryption.generateKeyPair()), txId = "synthetic-kept-kex-1")
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertTrue("the kept address's key is stored", prefs.e2ePeerKey.containsKey(kept))
            assertEquals("nothing is built or paid for a kept address", 0, seam.proposalRequests.get())
            assertEquals("nothing is broadcast", 0, seam.broadcasts.size)
            assertEquals("no KEXACK budget is used", 0, prefs.budgetConsumed.get())
            val awaiting =
                ChatViewModel::class.java
                    .getMethod("isInboundKexAwaitingAccept", String::class.java)
                    .invoke(vm, kept) as Boolean
            assertTrue("the kept address waits for Accept like any first contact", awaiting)
        }

    @Test
    fun `control - a group invitation from a kept address with no authenticated sender waits for Join and pays nothing`() =
        runBlocking {
            val kept = SyntheticUnifiedAddress.mainnet(seed = 7222)
            keepIfTheListExists(kept)
            val seam = HandshakeSeam(kept)
            val prefs = StrangerInvitePrefs(consent = true)
            val vm =
                chatViewModelWith(prefs.proxy, seam).also { vm ->
                    AutoSpendVm.wireBootstrapFields(vm)
                    AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
                }

            GroupInviteDriver.processInvite(vm, "GKEPT7222", plaintextInvite(kept))
            awaitIdle(vm)
            chainTip(vm, START_HEIGHT + 1)

            assertTrue("fixture sanity: the group is saved", prefs.base.groupInfo.containsKey("GKEPT7222"))
            assertTrue("an invitation from a kept address waits for Join", prefs.needsJoin.containsKey("GKEPT7222"))
            assertEquals("nothing is paid before Join", 0, seam.submits.get())
            assertEquals("nothing is built for the chain before Join", 0, seam.proposalRequests.get())
        }

    // ---- (4) the money gate is not edited -----------------------------------------------------------------------------

    @Test
    fun `guard - isFirstContact is the three conditions it has today`() {
        val code = SourceScan.stripComments(File(ChatResourceText.repoRoot, CHAT_VM).readText())
        val at = code.indexOf("private fun isFirstContact(peerAddress: String): Boolean =")
        assertTrue("fixture: isFirstContact", at >= 0)
        val body = code.substring(at, code.indexOf("\n\n", at)).replace(Regex("""\s+"""), " ").trim()
        assertEquals(
            "private fun isFirstContact(peerAddress: String): Boolean = " +
                "zchatPreferences.getE2EOurPublicKey(peerAddress) == null && " +
                "!zchatPreferences.isE2EEnabled(peerAddress) && " +
                "!contactBook.hasContact(peerAddress)",
            body
        )
    }

    // ---- (5) the first-contact marks --------------------------------------------------------------------------------

    @Test
    fun `Save as contact writes no first-contact mark - the screen is given no chat store`() {
        val type =
            runCatching { Class.forName(KeptNamesScreenApi.VM) }.getOrNull()
                ?: throw AssertionError("there is no ${KeptNamesScreenApi.VM}")
        for (constructor in type.constructors) {
            assertFalse(
                "the kept-names screen is given the chat stores",
                constructor.parameterTypes.any { it == ZchatPreferences::class.java }
            )
        }
        val source = KeptNamesScreenApi.vmSource()
        for (mark in listOf("keyclaim", "kexasked", "\"own:", "rekeyed_after_rebuild", "rebuilt_key_reached_member", "ZchatPreferences")) {
            assertFalse("the kept-names screen names $mark", mark in source)
        }
    }

    private fun threeKeptNames(): Any {
        val store = KeptNamesApi.store(folder, KeptNamesApi.MemoryBox())
        assertTrue(KeptNamesApi.add(store, listOf(A to "Ann", B to "Bo", C to "Cy")))
        return store
    }

    /** The controls hold before the list exists too: the list is written when its class is there. */
    private fun keepIfTheListExists(address: String) {
        val type = runCatching { Class.forName(KeptNamesApi.STORE) }.getOrNull() ?: return
        assertNotNull(type)
        val store = KeptNamesApi.store(folder, KeptNamesApi.MemoryBox())
        assertTrue(KeptNamesApi.add(store, listOf(address to "Kept Synthetic")))
    }

    private companion object {
        const val A = "zs1keptalpha0000"
        const val B = "zs1keptbeta00000"
        const val C = "zs1keptgamma0000"
        const val CHAT_VM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val UI = "ui-lib/src/main/java/co/electriccoin/zcash/ui"

        val FORBIDDEN =
            listOf(
                "$UI/screen/chat/viewmodel/ChatViewModel.kt",
                "$UI/screen/chat/viewmodel/GroupViewModel.kt",
                "$UI/nostr/NostrChatBridge.kt",
                "$UI/service/SyncForegroundService.kt",
                "$UI/screen/chat/viewmodel/ZchatComposeVM.kt",
                "$UI/screen/chat/datasource/ContactBookImpl.kt",
            ) + listOf("AndroidSharePicker.kt").map { name -> findSource(name) }

        private fun findSource(name: String): String =
            File(ChatResourceText.repoRoot, UI)
                .walkTopDown()
                .firstOrNull { it.isFile && it.name == name }
                ?.relativeTo(ChatResourceText.repoRoot)
                ?.invariantSeparatorsPath
                ?: "missing/$name"
    }
}
