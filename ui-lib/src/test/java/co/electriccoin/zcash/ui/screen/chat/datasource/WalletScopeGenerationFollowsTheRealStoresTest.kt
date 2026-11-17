package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.changeidentity.IdentityManagerImpl
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Round 8 D6 (REVIEW_7C F5): the wallet-scope generation is tied to the real reload and the real wipe.
 *
 * C2 made `walletScopeGeneration()` the only guard that stops a held GROUP_ACCEPT (a paid "I joined" reply) of one
 * wallet from being written into, or paid by, the next wallet after a change of recovery phrase (REVIEW_B1-B3 item 9).
 * The generation rises in ZchatPreferencesImpl.reloadWalletScopedStores (called by LiveChatStoresImpl.reload, which
 * ChatWalletOwnership.claimFor calls after it moved the stores) and in ZchatPreferencesImpl.clearAll (Reset). Every
 * ViewModel fixture answers the generation from its own counter, so nothing failed when any of those three links was
 * removed.
 *
 * Here the real ZchatPreferencesImpl (its SharedPreferences files in memory, see [realPreferences]) and the real
 * LiveChatStoresImpl, over the real contact book, avatar store, address cache and identity manager (their files in
 * memory or in a temporary folder), are driven through the real ChatWalletOwnership:
 *  - a claim for a different wallet raises the generation (STARTED_EMPTY and RESTORED);
 *  - Reset (clearAll) raises it;
 *  - KEPT and ADOPTED, which move nothing, leave it as it is.
 *
 * Deleting `walletScopeReloads++` from reloadWalletScopedStores or from clearAll, or the
 * `zchatPreferences.reloadWalletScopedStores()` line from LiveChatStoresImpl.reload, fails a case here.
 *
 * Round 8 D11 (Grok CLI review, G14): the generation rose only in the reload, AFTER every store was imported. A held
 * GROUP_ACCEPT write checked against wallet A's generation (ChatViewModel checks the generation, then writes) that
 * lands while the claim imports the stores therefore wrote A's signed "I joined" reply into wallet B's just-imported
 * store; if B later consents, B's ZEC pays a memo signed by A towards A's inviter (a cross-wallet spend and an
 * on-chain link between the two wallets). Wanted: the generation rises before anything is exported or imported.
 * All wallet tags and entries are synthetic.
 */
class WalletScopeGenerationFollowsTheRealStoresTest {
    /** The phone's files: every SharedPreferences file in memory, the files folder a temporary one. */
    private class PhoneContext : ContextWrapper(null) {
        val files = LinkedHashMap<String, MemorySharedPreferences>()
        private val filesDir: File = Files.createTempDirectory("zchat-scope-generation").toFile().apply { deleteOnExit() }

        override fun getSharedPreferences(
            name: String,
            mode: Int
        ): SharedPreferences = synchronized(files) { files.getOrPut(name) { MemorySharedPreferences() } }

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = filesDir
    }

    private class Phone(
        val preferences: ZchatPreferencesImpl,
        val record: InMemoryOwnerRecord,
        val bundles: InMemoryBundles,
        val ownership: ChatWalletOwnership,
    )

    /**
     * The real stores of one phone whose chats belong to [owner]. The contact book and the avatar store build their
     * Keystore-backed file lazily; it is replaced by an in-memory file before first use (nothing else is changed).
     */
    private fun phone(
        owner: String?,
        contactFile: SharedPreferences = MemorySharedPreferences(),
    ): Phone {
        val context = PhoneContext()
        val (preferences, _) = realPreferences()
        val contactBook = ContactBookImpl(context).withPrefs(contactFile)
        val avatarStore = AvatarStore(context).withPrefs(MemorySharedPreferences())
        val live =
            LiveChatStoresImpl(
                zchatPreferences = { preferences },
                contactBook = { contactBook },
                avatarStore = { avatarStore },
                addressCache = { AddressCacheImpl(context) },
                identityManager = { IdentityManagerImpl(context) },
            )
        val record = InMemoryOwnerRecord(owner)
        val bundles = InMemoryBundles()
        preferences.saveGroupInfo(GROUP, GROUP_INFO)
        return Phone(preferences, record, bundles, ChatWalletOwnership(record, bundles, live, InMemoryUntaggedMarks()))
    }

    private fun <T : Any> T.withPrefs(prefs: SharedPreferences): T =
        apply {
            javaClass.getDeclaredField("prefs\$delegate").apply { isAccessible = true }.set(this, lazyOf(prefs))
        }

    @Test
    fun a_claim_for_a_different_wallet_raises_the_generation() {
        val phone = phone(WALLET_A)
        val before = phone.preferences.walletScopeGeneration()

        assertEquals(ChatClaim.STARTED_EMPTY, phone.ownership.claimFor(WALLET_B))

        val afterB = phone.preferences.walletScopeGeneration()
        assertTrue("the generation did not rise when wallet B's (empty) chats replaced wallet A's: $before -> $afterB", afterB > before)
        assertEquals("wallet B must not see wallet A's group", null, phone.preferences.getGroupInfo(GROUP))

        assertEquals(ChatClaim.RESTORED, phone.ownership.claimFor(WALLET_A))

        val backToA = phone.preferences.walletScopeGeneration()
        assertTrue("the generation did not rise when wallet A's chats were put back: $afterB -> $backToA", backToA > afterB)
        assertEquals("wallet A's group did not come back", GROUP_INFO, phone.preferences.getGroupInfo(GROUP))
    }

    @Test
    fun reset_raises_the_generation() {
        val phone = phone(WALLET_A)
        val before = phone.preferences.walletScopeGeneration()

        phone.preferences.clearAll()

        assertTrue("Reset (clearAll) did not raise the generation", phone.preferences.walletScopeGeneration() > before)
    }

    // Control: a claim that moves nothing must not make a held reply of the same wallet look like another wallet's.
    @Test
    fun a_claim_that_moves_nothing_leaves_the_generation_as_it_is() {
        val kept = phone(WALLET_A)
        val keptBefore = kept.preferences.walletScopeGeneration()
        assertEquals(ChatClaim.KEPT, kept.ownership.claimFor(WALLET_A))
        assertEquals("KEPT changed the generation", keptBefore, kept.preferences.walletScopeGeneration())

        val adopted = phone(null)
        val adoptedBefore = adopted.preferences.walletScopeGeneration()
        assertEquals(ChatClaim.ADOPTED, adopted.ownership.claimFor(WALLET_A))
        assertEquals("ADOPTED changed the generation", adoptedBefore, adopted.preferences.walletScopeGeneration())
        assertEquals("ADOPTED must keep the chats in place", GROUP_INFO, adopted.preferences.getGroupInfo(GROUP))
    }

    /**
     * A preferences file whose next confirmed write (commit()) runs [hook] once, after the write: the moment a store is
     * imported during a claim.
     */
    private class HookedPrefs(
        private val base: SharedPreferences,
    ) : SharedPreferences by base {
        @Volatile var hook: (() -> Unit)? = null

        override fun edit(): SharedPreferences.Editor = HookedEditor(base.edit())

        // Every chained call returns this editor (not the wrapped one), so the commit() at the end is this one.
        private inner class HookedEditor(
            private val editor: SharedPreferences.Editor,
        ) : SharedPreferences.Editor by editor {
            override fun putString(
                key: String?,
                value: String?
            ) = apply { editor.putString(key, value) }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ) = apply { editor.putStringSet(key, values) }

            override fun putInt(
                key: String?,
                value: Int
            ) = apply { editor.putInt(key, value) }

            override fun putLong(
                key: String?,
                value: Long
            ) = apply { editor.putLong(key, value) }

            override fun putFloat(
                key: String?,
                value: Float
            ) = apply { editor.putFloat(key, value) }

            override fun putBoolean(
                key: String?,
                value: Boolean
            ) = apply { editor.putBoolean(key, value) }

            override fun remove(key: String?) = apply { editor.remove(key) }

            override fun clear() = apply { editor.clear() }

            override fun commit(): Boolean {
                val written = editor.commit()
                hook?.also { hook = null }?.invoke()
                return written
            }
        }
    }

    @Test
    fun a_held_reply_checked_against_wallet_a_is_never_written_into_wallet_b_while_the_claim_imports_the_stores() {
        val contactFile = HookedPrefs(MemorySharedPreferences())
        val phone = phone(WALLET_A, contactFile)
        val heldUnderA = phone.preferences.walletScopeGeneration()
        var writtenIntoB = false
        // The contact book is imported after the chat stores (the held replies' store included). A held-reply write
        // of wallet A lands then, the way ChatViewModel writes one: it checks the generation, then writes.
        contactFile.hook = {
            if (phone.preferences.walletScopeGeneration() == heldUnderA) {
                writtenIntoB = phone.preferences.setHeldGroupAccept(GROUP, A_REPLY)
            }
        }

        assertEquals(ChatClaim.STARTED_EMPTY, phone.ownership.claimFor(WALLET_B))

        assertEquals("fixture sanity: the claim imported the contact book", null, contactFile.hook)
        assertEquals(
            "wallet A's held reply was written into wallet B's stores while the claim imported them (written=$writtenIntoB)",
            null,
            phone.preferences.getHeldGroupAccepts()[GROUP],
        )
    }

    private companion object {
        const val A_REPLY = "{\"inviter\":\"usyntheticinviter\",\"accepter\":\"usyntheticwalleta\",\"memo\":\"synthetic\"}"
        const val WALLET_A = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0"
        const val WALLET_B = "b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1"
        const val GROUP = "GSYNTHSCOPE1"
        const val GROUP_INFO = """{"id":"GSYNTHSCOPE1","name":"synthetic","active":true}"""
    }
}
