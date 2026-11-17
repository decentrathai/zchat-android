package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.SelfAddressRegistry
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OpenGroup.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * ZC-348 (register, medium; seen on phones 30 Sep and twice on 13 Oct) — after the admin removes member X, a staying
 * member B whose wallet also holds X's account (an imported viewing key, selected once, so X's address was saved as that
 * account's chat identity) shows "You were removed from this group" and cannot read the admin's next message.
 *
 * The self-address registry is wallet-wide by design (every saved identity of every account counts as "me", which the
 * money paths rely on to refuse paying oneself). The kick handler asks it "is the removed member me?", gets yes for X,
 * marks the group removed and skips the new key the kick carried for B.
 *
 * Wanted: a kick that carries the new key was sent to a staying member (the removed member is only ever told with a kick
 * that carries no key, in every version since 2.12.4), so it never removes this phone: B keeps the group, takes the new
 * key, lists X as LEFT, raises its removal floor and reads the next message. A kick of B itself (no key) still removes it.
 * The registry and isSelfAddress are not narrowed.
 *
 * The member's phone answers isSelfAddress through the real SelfAddressRegistry. All addresses, keys and ids are
 * synthetic; nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportedAccountIdentityIsNotRemovedWithItsOwnerTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        net.register()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** B's wallet: B's own account and X's account imported and selected once, each with its saved chat identity. */
    private fun walletWithImportedAccount(): SelfAddressRegistry =
        SelfAddressRegistry(MemorySharedPreferences()).apply {
            pinIdentityIfAbsent("synthetic-account-b", B)
            pinIdentityIfAbsent("synthetic-account-x-imported", X)
        }

    /** [base] with isSelfAddress and registerSelfAddress answered by [registry], as ZchatPreferencesImpl does. */
    private fun withRegistry(
        base: ZchatPreferences,
        registry: SelfAddressRegistry,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "isSelfAddress" -> registry.isSelf(args!![0] as String)
                "registerSelfAddress" -> registry.register(args!![0] as String)
                else ->
                    try {
                        method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    @Test
    fun `the admin's kick of X keeps B in the group with the new key, and B reads the next message`() =
        runBlocking {
            val adminPhone = OpenGroup.creator(listOf(B, X))
            val f = GroupPaidOnceFixture().apply { scheduler = this@ImportedAccountIdentityIsNotRemovedWithItsOwnerTest.scheduler }
            val admin = MA3.groupVm(adminPhone, f, AnnounceSeam())
            val (bPhone, bChat) = OpenGroup.plainMember(B)
            val registry = walletWithImportedAccount()
            assertTrue("fixture: the wallet-wide registry does not count X's saved identity as self", registry.isSelf(X))
            AutoSpendVm.setField(bChat, "zchatPreferences", withRegistry(bPhone.proxy, registry))

            OpenGroup.kick(admin, f, X)
            val kicks = net.ofType(GroupMessageType.GROUP_KICK, OpenGroup.npub(B))
            assertTrue("fixture: no kick reached B over the relays", kicks.isNotEmpty())
            kicks.forEach { MA3.relay(bChat, it, sender = C) }

            assertFalse("B was told it was removed (the kick named X, an imported account's identity)", bPhone.info(GID).removedByAdmin)
            assertTrue("B's group is no longer active", bPhone.info(GID).isActive)
            assertEquals("B did not take the new key", 2, bPhone.epochs[GID])
            assertEquals("B's phone does not list X as removed", MemberStatus.LEFT, bPhone.status(GID, X))
            assertEquals("B's removal floor did not move", 2, bPhone.floors[GID])

            net.clear()
            f.awaitNewJobs(admin) { admin.sendGroupMessage(GID, "admin at epoch 2") }
            if (admin.paidActionConfirmation.value != null) f.awaitNewJobs(admin) { admin.confirmPaidAction() }
            val msgs = net.ofType(GroupMessageType.GROUP_MSG, OpenGroup.npub(B))
            assertTrue("fixture: the admin's message was not published to B", msgs.isNotEmpty())
            msgs.forEach { MA3.relay(bChat, it, sender = C) }
            assertTrue(
                "B cannot read the admin's message after X's removal",
                OpenGroup.rows(bPhone.messages).any { it.optString("decrypted") == "admin at epoch 2" },
            )
        }

    /** Control, GREEN before and after: the admin's notice of B's own removal (no key) still removes B's phone. */
    @Test
    fun `control - a kick of B itself, with no key, still removes B's phone`() =
        runBlocking {
            val (bPhone, bChat) = OpenGroup.plainMember(B)
            AutoSpendVm.setField(bChat, "zchatPreferences", withRegistry(bPhone.proxy, walletWithImportedAccount()))

            MA3.relay(bChat, OpenGroup.sealedKick(B, newEpoch = 2, gk = null), sender = C)

            assertFalse("B's own removal did not make the group inactive", bPhone.info(GID).isActive)
            assertTrue("B's own removal is not saved as removed", bPhone.info(GID).removedByAdmin)
        }
}
