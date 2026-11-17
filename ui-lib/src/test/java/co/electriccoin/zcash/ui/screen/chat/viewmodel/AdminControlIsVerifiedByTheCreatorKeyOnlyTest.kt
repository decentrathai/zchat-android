package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.M
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.STRANGER
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.X_OLD
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AdminCheck.Y
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KI-25 pair 1, ZC-022 (KI-25-SPEC 3.6b R1; section 4.1 class 1) — a signed admin change is the admin's when its
 * signature verifies against the group's creator key, whatever address text it names, and never otherwise.
 *
 * The harm: a member's phone stored its group's admin under one address text (X_OLD) and holds the admin's key under
 * another (Y). The admin's phone writes X_OLD into its changes. At `00f1a349b` the gate
 * (ChatViewModel.verifyGroupAdminControl) first looks up a key under the address the change names; there is none
 * under X_OLD, so every removal and key change of the admin is dropped, although the group's record holds the very
 * key that signed it. The same when the named address holds another contact's key.
 *
 * Wanted (R1): the unsigned test and the `'|'` test as today, then one test: the signature verifies against the
 * group's creator key. No case here has a group without a stored creator key and with a key at the creator address
 * (that group belongs to pair 3).
 *
 * All addresses and keys are synthetic; nothing is sent or paid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminControlIsVerifiedByTheCreatorKeyOnlyTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val WRAPPED = "d3JhcHBlZC1uZXcta2V5LWJhc2U2NA=="
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun kickData(naming: String): String = ZMSGGroupProtocol.groupKickSignedData(GID, M, naming, 2, WRAPPED)

    @Test
    fun `a - a kick signed with the stored creator key that names the keyless creator address is the admin's`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val data = kickData(naming = X_OLD)

            assertTrue(
                "a kick the group's creator key signed was refused because it names an address with no key (X_OLD)",
                AdminCheck.verifyAdminControl(vm, X_OLD, data, E2EEncryption.sign(p.admin.privateKey, data)),
            )
        }

    @Test
    fun `b - the same kick naming an address that holds another contact's key is the admin's`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            p.base.link(STRANGER)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val data = kickData(naming = STRANGER)

            assertTrue(
                "a kick the group's creator key signed was refused because the address it names holds another contact's key",
                AdminCheck.verifyAdminControl(vm, STRANGER, data, E2EEncryption.sign(p.admin.privateKey, data)),
            )
        }

    @Test
    fun `c - a kick naming the creator address and signed with a stranger's key is refused`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val stranger = p.base.link(STRANGER)
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val data = kickData(naming = X_OLD)

            assertFalse(
                "a kick signed with a key that is not the group's creator key was accepted",
                AdminCheck.verifyAdminControl(vm, X_OLD, data, E2EEncryption.sign(stranger.privateKey, data)),
            )
        }

    @Test
    fun `d - an unsigned change, an empty signature and an address with a delimiter are refused`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val data = kickData(naming = Y)

            assertFalse("an unsigned kick was accepted", AdminCheck.verifyAdminControl(vm, Y, data, null))
            assertFalse("a kick with an empty signature was accepted", AdminCheck.verifyAdminControl(vm, Y, data, ""))
            val piped = "$Y|$M"
            val pipedData = kickData(naming = piped)
            assertFalse(
                "a kick whose signer address holds the signed data's delimiter was accepted",
                AdminCheck.verifyAdminControl(vm, piped, pipedData, E2EEncryption.sign(p.admin.privateKey, pipedData)),
            )
        }

    @Test
    fun `e - with no creator key and no key under the creator address, a kick naming the admin's chat is refused`() =
        runBlocking {
            val p = AdminCheck.driftedPhone()
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val data = kickData(naming = Y)

            assertFalse(
                "a group with no creator key accepted a kick because a chat's key signed it",
                AdminCheck.verifyAdminControl(vm, Y, data, E2EEncryption.sign(p.admin.privateKey, data)),
            )
        }

    @Test
    fun `f - control - creator key stored and held under the creator address, a rotation naming it is the admin's`() =
        runBlocking {
            val p = AdminCheck.pinnedPhone()
            // The healthy shape: the admin's key is also held under the stored creator address.
            p.base.ourKeys[X_OLD] = p.base.ourKeys.getValue(Y)
            p.base.peerKeys[X_OLD] = p.admin.publicKey
            val vm = AdminCheck.chatVm(p, HandshakeSeam(Y))
            val data = ZMSGGroupProtocol.groupKeySignedData(GID, X_OLD, 2, WRAPPED, "rotation")

            assertTrue(
                "a healthy group refused its admin's rotation",
                AdminCheck.verifyAdminControl(vm, X_OLD, data, E2EEncryption.sign(p.admin.privateKey, data)),
            )
        }
}
