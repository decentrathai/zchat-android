package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.GroupAdminCheck
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * KI-25 pair 2, ZC-572 (KI-25-SPEC 3.6b R14; section 4.2 class 12, case (f)) — the key change through the real paths:
 * a chat whose contact created a group on this phone, with the contact's key stored as the group's creator key. Then:
 * - this phone's Reset encryption with that contact, after which another key of the contact is stored;
 * - the accept of a new key the contact offered (`acceptRefusedKey`, ZC-545).
 * Each time the group reads ORPHANED, and **no group record was written by the key change itself**.
 *
 * The chat is [KeyChangeAcceptChat]'s (the real store for the keys); the group's record is answered by a view over its
 * store that adds the group calls ([withGroup]). At the base (pairs 1 and 1b) the group reads UNKNOWN.
 *
 * Every address, key and id is synthetic.
 */
class AdminKeyChangeThroughTheRealPathsTest : KeyChangeAcceptTestBase() {
    /** [chat]'s store with the group [GID] created by the chat's contact, whose stored creator key is [creatorKey]. */
    private fun withGroup(
        chat: KeyChangeAcceptChat,
        creatorKey: String,
    ): ZchatPreferences {
        val record =
            ZMSGGroupProtocol.serializeGroupInfo(
                GroupInfo(
                    groupId = GID,
                    name = "Synthetic group",
                    creatorAddress = PEER,
                    createdAt = Instant.ofEpochMilli(AdminCheck.T),
                    isActive = true,
                    creatorPublicKey = creatorKey,
                ),
            )
        return Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            val a = args ?: emptyArray()
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a.getOrNull(0)
                "toString" -> "GroupOverKeyChangeChat"
                "getGroupInfo" -> if (a[0] == GID) record else null
                "isGroupSelfCreated", "isSelfAddress" -> false
                "getGroupInviteNeedsJoin" -> null
                "getAllPeerToConvIdMappings" -> mapOf(PEER to KeyChangeAcceptChat.CONV_ID)
                "getAllConversationPeerAddresses" -> listOf(PEER)
                "resolvePeerAddress" -> a[0]
                else ->
                    try {
                        method.invoke(chat.impl, *a)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
    }

    private fun state(store: ZchatPreferences): String = GroupAdminCheck.keyState(store, emptyList(), GID).name

    private fun groupWrites(chat: KeyChangeAcceptChat): List<String> = chat.writes.filter { it.contains("GroupInfo") }

    @Test
    fun `f - Reset with the creator's contact, then another key stored - ORPHANED, and no group record written`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val group = withGroup(chat, chat.oldContact.publicKey)
            val vm = chat.viewModel()
            assertEquals("fixture: before the key change the group reads OK", "OK", state(group))

            KeyAcceptApi.resetWipe(vm, PEER)
            val newKeys = E2EEncryption.generateKeyPair()
            chat.impl.setE2EPeerPublicKey(PEER, newKeys.publicKey)

            assertEquals("after a Reset and another key the group does not read ORPHANED", "ORPHANED", state(group))
            assertTrue("the key change wrote a group record: ${groupWrites(chat)}", groupWrites(chat).isEmpty())
        }

    @Test
    fun `f - the accept of a new key of the creator's contact - ORPHANED, and no group record written`() =
        runBlocking {
            val chat = KeyChangeAcceptChat(PEER)
            val group = withGroup(chat, chat.oldContact.publicKey)
            val vm = chat.viewModel()
            assertEquals("fixture: before the key change the group reads OK", "OK", state(group))
            val newKeys = E2EEncryption.generateKeyPair()

            chat.kexAckFromContact(vm, newKeys, TXID)
            chat.accept(vm, newKeys.publicKey)
            chat.assertAdopted(newKeys, "after the accept")

            assertEquals("after the accept of a new key the group does not read ORPHANED", "ORPHANED", state(group))
            assertTrue("the key change wrote a group record: ${groupWrites(chat)}", groupWrites(chat).isEmpty())
        }

    private companion object {
        const val PEER = "usyntheticzc572realpaths0001"
        const val TXID = "synthetic-zc572-realpaths-ack"
        const val GID = "zgrp_572a3b4c5d6e7f8091a2b3c4"
    }
}
