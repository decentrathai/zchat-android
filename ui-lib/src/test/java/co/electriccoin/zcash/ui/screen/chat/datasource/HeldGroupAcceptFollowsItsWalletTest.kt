package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files

/**
 * Round 7 B3 — HELD-ACCEPT-STORE-LIFECYCLE (Grok Q11, GPT Q11, M4-1.7 / C-3, review question 17).
 *
 * R4-1 moved the held group replies ("I joined", a paid GROUP_ACCEPT waiting for the handshake-spend consent)
 * out of the plaintext conversation-mode file into their own encrypted store, `zchat_held_group_accepts_encrypted`.
 * The conversation-mode file is a wallet-scoped store (set aside on a phrase change, wiped by Reset); the new
 * store was not added to either list, and Delete Group still removed only the old plaintext key.
 *
 * So, with wallet A holding a reply for group G:
 *  - a phrase change A→B leaves A's reply live under B; B has no group G, so the next restore (or the next
 *    attempt to send it) deletes it as "group gone" — and A's set-aside bundle never had it, so when A comes
 *    back the pending join is lost;
 *  - Reset (clearAll) leaves the previous identity's inviter address, own address and signed memo on disk;
 *  - Delete Group leaves the row behind in the encrypted store.
 *
 * Wanted: the held reply follows its wallet — set aside with it, put back with it, wiped by Reset and removed
 * by Delete Group — and wallet B never sees (so can never pay) wallet A's reply.
 *
 * The store under test is the real [ZchatPreferencesImpl]; only its SharedPreferences files are in memory.
 * All ids, addresses and memos here are synthetic.
 */
class HeldGroupAcceptFollowsItsWalletTest {
    @Test
    fun `the held group reply store is one of the stores that move with a wallet`() {
        val (impl, _) = realPreferences()

        val ids = impl.walletScopedStores().map { it.storeId }

        assertTrue(
            "the held group replies must be set aside and put back with their wallet: $ids",
            HELD_STORE_FILE in ids,
        )
    }

    @Test
    fun `Reset wipes the held group replies`() {
        val (impl, files) = realPreferences()
        assertTrue(impl.setHeldGroupAccept(GROUP, RECORD))

        impl.clearAll()

        assertTrue(
            "Reset left the previous identity's held group reply on disk: ${impl.getHeldGroupAccepts()}",
            impl.getHeldGroupAccepts().isEmpty(),
        )
        assertTrue(files.getValue(HELD_STORE_FIELD).snapshot().isEmpty())
    }

    @Test
    fun `Delete Group removes its held group reply`() {
        val (impl, _) = realPreferences()
        impl.saveGroupInfo(GROUP, GROUP_INFO)
        assertTrue(impl.setHeldGroupAccept(GROUP, RECORD))
        assertTrue(impl.setHeldGroupAccept(OTHER_GROUP, OTHER_RECORD))

        impl.deleteGroup(GROUP)

        assertEquals(
            "Delete Group must remove that group's held reply, and only that one",
            mapOf(OTHER_GROUP to OTHER_RECORD),
            impl.getHeldGroupAccepts(),
        )
    }

    @Test
    fun `a phrase change A to B to A keeps A's held reply and B never sees it`() {
        val (impl, _) = realPreferences()
        val ownership =
            ChatWalletOwnership(
                InMemoryOwnerRecord(WALLET_A),
                InMemoryBundles(),
                object : LiveChatStores {
                    override fun stores(): List<WalletScopedStore> = impl.walletScopedStores()

                    override fun reload() = impl.reloadWalletScopedStores()
                },
                { true },
            )
        impl.saveGroupInfo(GROUP, GROUP_INFO)
        assertTrue(impl.setHeldGroupAccept(GROUP, RECORD))

        assertEquals(ChatClaim.STARTED_EMPTY, ownership.claimFor(WALLET_B))

        assertTrue(
            "wallet B can see (and so could pay) wallet A's held group reply: ${impl.getHeldGroupAccepts()}",
            impl.getHeldGroupAccepts().isEmpty(),
        )
        // What B's chat screen does with every held reply on its first tip (ChatViewModel.restoreHeldGroupAccepts):
        // one whose group this wallet does not have is dropped.
        impl
            .getHeldGroupAccepts()
            .keys
            .filter { impl.getGroupInfo(it) == null }
            .forEach { impl.setHeldGroupAccept(it, null) }

        assertEquals(ChatClaim.RESTORED, ownership.claimFor(WALLET_A))

        assertEquals(
            "wallet A's pending 'I joined' must come back with wallet A",
            mapOf(GROUP to RECORD),
            impl.getHeldGroupAccepts(),
        )
    }

    private companion object {
        const val HELD_STORE_FIELD = "heldGroupAcceptPrefs"
        const val HELD_STORE_FILE = "zchat_held_group_accepts_encrypted"
        const val WALLET_A = "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0"
        const val WALLET_B = "b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1"
        const val GROUP = "GSYNTHFOLLOW1"
        const val OTHER_GROUP = "GSYNTHFOLLOW2"
        const val GROUP_INFO = """{"id":"GSYNTHFOLLOW1","name":"synthetic","active":true}"""
        const val RECORD =
            """{"inviter":"usyntheticfollowinviter01","accepter":"usyntheticfollowself01","memo":"synthetic-accept-memo-1"}"""
        const val OTHER_RECORD =
            """{"inviter":"usyntheticfollowinviter02","accepter":"usyntheticfollowself01","memo":"synthetic-accept-memo-2"}"""
    }
}

/**
 * A real [ZchatPreferencesImpl] whose SharedPreferences files are all in memory (by field name), so its own
 * clearAll / walletScopedStores / deleteGroup code runs without the Android Keystore.
 */
internal fun realPreferences(): Pair<ZchatPreferencesImpl, Map<String, MemorySharedPreferences>> {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val impl =
        unsafeClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
    val files = LinkedHashMap<String, MemorySharedPreferences>()
    val prefsDir = Files.createTempDirectory("zchat-held-follow").toFile().apply { deleteOnExit() }
    ZchatPreferencesImpl::class.java
        .declaredFields
        .filter { !Modifier.isStatic(it.modifiers) }
        .forEach { field ->
            field.isAccessible = true
            val value: Any? =
                when {
                    SharedPreferences::class.java.isAssignableFrom(field.type) ->
                        MemorySharedPreferences().also { files[field.name] = it }
                    MutableStateFlow::class.java.isAssignableFrom(field.type) ->
                        if (field.name == "_e2eHandshakeTicks") MutableStateFlow(0L) else MutableStateFlow(emptyMap<String, Any>())
                    field.type == Any::class.java -> Any()
                    // KI-24: the relay and KEX seen stores keep a time or a height per entry and a floor (release caps).
                    field.type == Lazy::class.java && field.name.startsWith("nostrSeenLedger") -> lazyOf(NostrSeenLedger { 20_000L })
                    field.type == Lazy::class.java && field.name.startsWith("kexSeenLedger") -> lazyOf(KexSeenLedger { 500L })
                    field.type == Lazy::class.java && field.name.contains("SeenIds") -> lazyOf(LinkedHashSet<String>())
                    field.type == File::class.java -> prefsDir
                    Context::class.java.isAssignableFrom(field.type) -> ContextWrapper(null)
                    else -> null
                }
            if (value != null) field.set(impl, value)
        }
    return impl to files
}
