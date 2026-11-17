package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.B
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.GID
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.Base64

/**
 * ZC-420-G compat-2124 control (fix-all 2.13.0, train T6b; ZC-420-SPEC §2.3, §3.6 item 2, §7; register ZC-716): the
 * removal notice this build sends to a remaining member, and re-delivers when only a public relay took it, is applied by
 * ZChat 2.12.4, and is refused as not newer by a 2.12.4 phone that already holds its key number. That refusal is why a
 * removal is re-delivered as a removal: a member that got the new key as a plain key first can never apply it afterwards.
 *
 * [Frozen2124] is 2.12.4's code, copied from the tag v2.12.4 (`28713c7b0`, whose ui-lib/src/main equals a1fb4136f):
 * `ZMSGGroupProtocol.kt` lines 66-68 (isGroupMessage), 93-98 (parsePayload), 274-280 (groupKickSignedData) and 564-579
 * (parseGroupKickPayload); `ChatViewModel.kt` lines 8644-8680 (verifyGroupAdminControl), 8688-8734 (processGroupKick:
 * verification, the replay guard, the roster step, the self check), 8740-8756 (processGroupKey's replay guard) and
 * 8763-8780 (adoptRotatedGroupKey); `E2EEncryption.kt` lines 436-453 (verify, KEY_ALGORITHM "EC" at line 120);
 * ZMSGConstants.Prefixes.GROUP "ZMSG:3.0:GROUP:" (line 51). Replaced: the preferences by the fields of [Frozen2124] (one
 * group, resolvePeerAddress = identity, isSelfAddress = the phone's own address), getE2ESharedKey by the session key, the
 * log lines left out.
 *
 * Checked: the GROUP_KICK a removal sends to B (rotateAndNotify), and, once it exists, the memo of its shared builder
 * `removalNoticeMemo` (the repair's re-delivery). Passes before and after the fix: no wire change. All addresses, keys and
 * ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RedeliveredRemovalIsAppliedBy2124Test {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private var world: GroupCopyWorld? = null

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
    }

    @After
    fun tearDown() {
        world?.close()
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.unregisterKeptResender()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    /** The GROUP_KICK C's removal of X sends to B, and the GroupViewModel that sent it. */
    private suspend fun removalToB(): Pair<String, GroupViewModel> {
        val w = GroupCopyWorld(listOf(A to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE, X to MemberStatus.ACTIVE)).also { world = it }
        val f = GroupPaidOnceFixture().apply { scheduler = this@RedeliveredRemovalIsAppliedBy2124Test.scheduler }
        val vm = w.groupVm(f)
        f.awaitNewJobs(vm) { vm.kickMember(GID, X) }
        assertNull("fixture: a removal among members reachable over the relays asked a price", vm.paidActionConfirmation.value)
        val kicks = w.copies(GroupMessageType.GROUP_KICK, B)
        assertEquals("fixture: not one GROUP_KICK to B", 1, kicks.size)
        return kicks.single() to vm
    }

    /** B's 2.12.4 phone at key number [epoch], listing C, B and X. */
    private fun phoneOfB(epoch: Int): Frozen2124 {
        val w = world!!
        val b = w.phones.getValue(B)
        return Frozen2124(
            self = B,
            creator = C,
            adminPublicKey = b.base.peerKeys.getValue(C),
            sessionKey = b.base.sessionWith(C),
            epoch = epoch,
            roster = mutableMapOf(C to MemberStatus.ACTIVE, B to MemberStatus.ACTIVE, X to MemberStatus.ACTIVE),
        )
    }

    private fun assertAppliedAndRefusedOnceHeld(memo: String) {
        val older = phoneOfB(epoch = 1)
        older.receive(memo)
        assertEquals("2.12.4 on the older key did not take X off its roster", MemberStatus.LEFT, older.roster[X])
        assertEquals("2.12.4 did not move to the removal's key number", 2, older.epoch)
        assertEquals("2.12.4 did not adopt the new key", world!!.admin.base.keys["$GID|2"], older.keys[2])

        val holder = phoneOfB(epoch = 2)
        holder.receive(memo)
        assertEquals("2.12.4 holding the key number applied the removal: it is refused as not newer", MemberStatus.ACTIVE, holder.roster[X])
        assertTrue("2.12.4 did not refuse the removal as not newer", holder.refusedAsNotNewer)
    }

    @Test
    fun `the removal a remaining member is sent is applied by 2_12_4 on the older key and refused once it holds that key`() =
        runBlocking {
            val (memo, _) = removalToB()
            assertAppliedAndRefusedOnceHeld(memo)
        }

    @Test
    fun `the memo the repair rebuilds for a removal, once it exists, is read the same way`() =
        runBlocking {
            val (_, vm) = removalToB()
            val w = world!!
            val builder = GroupViewModel::class.java.declaredMethods.firstOrNull { it.name == "removalNoticeMemo" } ?: return@runBlocking
            builder.isAccessible = true
            val args: Array<Any?> =
                builder.parameterTypes
                    .map { type ->
                        when (type) {
                            ByteArray::class.java -> w.admin.base.sessionWith(B)
                            Int::class.javaPrimitiveType -> 2
                            else -> null
                        }
                    }.toTypedArray()
            // (groupId, removed, admin, epoch, keyBase64, sessionKey, ourPrivateKey): the strings in that order.
            val newKey =
                w.admin.base.keys
                    .getValue("$GID|2")
            val strings = ArrayDeque(listOf(GID, X, C, newKey, MA3.keyFor(w.admin, B).privateKey))
            builder.parameterTypes.forEachIndexed { i, type -> if (type == String::class.java) args[i] = strings.removeFirst() }
            val memo =
                try {
                    builder.invoke(vm, *args) as String
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            assertAppliedAndRefusedOnceHeld(memo)
        }

    /** One 2.12.4 phone in one group (see the class KDoc for the lines copied). */
    private class Frozen2124(
        private val self: String,
        private val creator: String,
        private val adminPublicKey: String,
        private val sessionKey: ByteArray,
        var epoch: Int,
        val roster: MutableMap<String, MemberStatus>,
    ) {
        val keys = mutableMapOf<Int, String>()
        var refusedAsNotNewer = false

        fun receive(memo: String) {
            assertTrue("fixture: not a group memo", isGroupMessage(memo))
            processGroupKick(parsePayload(memo)!!)
        }

        // ZMSGGroupProtocol.kt:66-68
        private fun isGroupMessage(memo: String): Boolean = memo.startsWith(GROUP_PREFIX)

        // ZMSGGroupProtocol.kt:93-98
        private fun parsePayload(memo: String): String? {
            if (!isGroupMessage(memo)) return null
            val parts = memo.removePrefix(GROUP_PREFIX).split(":", limit = 3)
            if (parts.size < 3) return null
            return parts[2]
        }

        // ZMSGGroupProtocol.kt:274-280
        private fun groupKickSignedData(
            groupId: String,
            kickedAddress: String,
            kickerAddress: String,
            newEpoch: Int,
            encryptedNewKey: String?
        ): String = "GK|$groupId|$kickedAddress|$kickerAddress|$newEpoch|${encryptedNewKey.orEmpty()}"

        private class GroupKickPayload(
            val kicked: String,
            val kicker: String,
            val newEpoch: Int,
            val encryptedGroupKey: String?,
            val signature: String?,
        )

        // ZMSGGroupProtocol.kt:564-579
        private fun parseGroupKickPayload(payload: String): GroupKickPayload? =
            try {
                val json = JSONObject(payload)
                GroupKickPayload(
                    kicked = json.getString("kicked"),
                    kicker = json.getString("kicker"),
                    newEpoch = json.getInt("new_epoch"),
                    encryptedGroupKey = if (json.has("enc_key")) json.getString("enc_key") else null,
                    signature = if (json.has("sig")) json.getString("sig") else null,
                )
            } catch (e: Exception) {
                null
            }

        // ChatViewModel.kt:8644-8680 (the store reads replaced by the fields)
        private fun verifyGroupAdminControl(
            signerAddress: String,
            signedData: String,
            signature: String?
        ): Boolean {
            if (signature.isNullOrEmpty()) return false
            if (signerAddress.contains('|')) return false
            val signerPub = adminPublicKey
            val authentic = runCatching { verify(signerPub, signedData, signature) }.getOrDefault(false)
            if (!authentic) return false
            if (creator != signerAddress) return false
            return true
        }

        // ChatViewModel.kt:8688-8734 (processGroupKick)
        private fun processGroupKick(payload: String) {
            val kick = parseGroupKickPayload(payload) ?: return
            val signedData = groupKickSignedData(GID, kick.kicked, kick.kicker, kick.newEpoch, kick.encryptedGroupKey)
            if (!verifyGroupAdminControl(kick.kicker, signedData, kick.signature)) return
            // REPLAY GUARD: a kick only ever advances the epoch; refuse a stale/replayed one (the same guard as
            // processGroupKey's, ChatViewModel.kt:8748-8751).
            if (kick.newEpoch <= epoch) {
                refusedAsNotNewer = true
                return
            }
            // Remove the kicked member from the roster (canonical match; a LEFT tombstone when unknown).
            var matched = false
            roster.keys.toList().forEach {
                if (it == kick.kicked) {
                    matched = true
                    roster[it] = MemberStatus.LEFT
                }
            }
            if (!matched) roster[kick.kicked] = MemberStatus.LEFT
            if (kick.kicked == self) return
            adoptRotatedGroupKey(kick.newEpoch, kick.encryptedGroupKey)
        }

        // ChatViewModel.kt:8763-8780 (getE2ESharedKey replaced by the session key)
        private fun adoptRotatedGroupKey(
            newEpoch: Int,
            encryptedKey: String?
        ) {
            if (encryptedKey == null) return
            val newKeyBase64 = runCatching { E2EEncryption.decrypt(encryptedKey, sessionKey) }.getOrNull() ?: return
            keys[newEpoch] = newKeyBase64
            epoch = newEpoch
        }

        // E2EEncryption.kt:436-453
        private fun verify(
            publicKeyBase64: String,
            message: String,
            signatureBase64: String
        ): Boolean =
            try {
                val keyFactory = java.security.KeyFactory.getInstance("EC")
                val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
                val publicKeySpec = java.security.spec.X509EncodedKeySpec(publicKeyBytes)
                val publicKey = keyFactory.generatePublic(publicKeySpec)
                val signature = java.security.Signature.getInstance("SHA256withECDSA")
                signature.initVerify(publicKey)
                signature.update(message.toByteArray(Charsets.UTF_8))
                val signatureBytes = Base64.getDecoder().decode(signatureBase64)
                signature.verify(signatureBytes)
            } catch (e: Exception) {
                false
            }

        private companion object {
            const val GROUP_PREFIX = "ZMSG:3.0:GROUP:"
        }
    }
}
