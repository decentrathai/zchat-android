package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AnnounceDriver
import co.electriccoin.zcash.ui.screen.chat.viewmodel.AutoSpendVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupInviteDriver
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeSeam
import co.electriccoin.zcash.ui.screen.chat.viewmodel.OddIdPhone
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SavedContacts
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendHarnessScopes
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SyntheticUnifiedAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * ZC-708, the compatibility pin — every group id a 2.12.4 phone makes has the shape this build asks of a group memo,
 * and an invitation a 2.12.4 phone builds is still joined.
 *
 * The 2.12.4 phone is [Frozen2124]: verbatim copies from the tag `v2.12.4` (commit a1fb4136f), frozen here so the pin
 * keeps modelling that build whatever this one changes. Green before and after the fix. All addresses and keys are
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupIdsOf2124PassTheDoorTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        GroupInviteDriver.resetKeystoneExplanation()
    }

    @Test
    fun `500 group ids made by 2_12_4 have the shape of a group id`() {
        val creator = SyntheticUnifiedAddress.mainnet(seed = 70840)
        val odd = (1..500).map { Frozen2124.generateGroupId(creator) }.filterNot { GroupInfo.isGroupIdShaped(it) }
        assertEquals("ids made by 2.12.4 that this build would drop", emptyList<String>(), odd)
    }

    @Test
    fun `an invitation built by 2_12_4 over a finished key exchange is joined`() =
        runBlocking {
            val inviter = SyntheticUnifiedAddress.mainnet(seed = 70841)
            val phone = OddIdPhone()
            val session = GroupInviteDriver.finishedKeyExchange(phone.inner, inviter)
            val seam = HandshakeSeam(inviter)
            val vm =
                HandshakeHarness.chatViewModelWith(phone.proxy, seam).also { vm ->
                    AutoSpendVm.wireBootstrapFields(vm)
                    AutoSpendVm.setField(vm, "contactBook", SavedContacts(emptySet()))
                }
            val groupId = Frozen2124.generateGroupId(inviter)
            val memo =
                Frozen2124.createGroupInviteCompact(
                    groupId = groupId,
                    groupName = "Synthetic 2.12.4 group",
                    inviterAddress = inviter,
                    keyEpoch = 0,
                    encryptedGroupKey = E2EEncryption.encrypt(GroupInviteDriver.GROUP_KEY, session),
                    isSessionEncrypted = true,
                )
            val mark = phone.mark()

            AnnounceDriver.receive(vm, memo, Instant.EPOCH, AnnounceDriver.txId(40))
            HandshakeHarness.awaitIdle(vm)

            assertTrue("the group of a 2.12.4 invitation was not stored", phone.groups.containsKey(groupId))
            assertTrue("its roster was not stored", phone.rosters.containsKey(groupId))
            assertTrue("its group key was not stored", "saveGroupKey" in phone.writesSince(mark))
            assertEquals("nothing is paid for it without Join", 0, seam.submits.get())
        }

    /** Verbatim from `v2.12.4` (a1fb4136f). */
    @Suppress(
        "ktlint:standard:chain-method-continuation",
        "ktlint:standard:multiline-expression-wrapping",
        "ktlint:standard:string-template",
    )
    private object Frozen2124 {
        private const val GROUP_PREFIX = "ZMSG:3.0:GROUP:" // ZMSGConstants.kt@v2.12.4, Prefixes.GROUP
        private const val MAX_GROUP_NAME_BYTES = 100 // ZMSGGroupProtocol.kt@v2.12.4:41

        // GroupModels.kt@v2.12.4:73-81
        fun generateGroupId(creatorAddress: String): String {
            val timestamp = System.currentTimeMillis()
            val random = java.util.UUID.randomUUID().toString().take(8)
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$creatorAddress$timestamp$random".toByteArray())
                .take(12)
                .joinToString("") { "%02x".format(it) }
            return "zgrp_$hash"
        }

        // ZMSGGroupProtocol.kt@v2.12.4:49-56
        fun boundGroupName(name: String): String {
            if (name.toByteArray(Charsets.UTF_8).size <= MAX_GROUP_NAME_BYTES) return name
            var result = name
            while (result.isNotEmpty() && result.toByteArray(Charsets.UTF_8).size > MAX_GROUP_NAME_BYTES) {
                result = result.substring(0, result.length - 1)
            }
            return result
        }

        // ZMSGGroupProtocol.kt@v2.12.4:144-162
        fun createGroupInviteCompact(
            groupId: String,
            groupName: String,
            inviterAddress: String,
            keyEpoch: Int,
            encryptedGroupKey: String,
            isSessionEncrypted: Boolean
        ): String {
            val payload = JSONObject().apply {
                put("name", boundGroupName(groupName))
                put("inviter", inviterAddress)
                put("key_epoch", keyEpoch)
                if (isSessionEncrypted) {
                    put("k2", encryptedGroupKey)
                } else {
                    put("group_key", encryptedGroupKey)
                }
            }
            return "${GROUP_PREFIX}GI:$groupId:${payload}"
        }
    }
}
