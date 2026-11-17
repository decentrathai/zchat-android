package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * GR-OPEN G1 (fold-specs/groups SPEC §1.2, review A-C) — the two pure rules of the removal hold.
 *
 * [ZMSGGroupProtocol.isPreRemovalNonMember]: on a phone whose roster is the whole group (the creator's, or a member's
 * whose roster the creator announced), a message that decrypts only under a key from before a removal is held when its
 * sender is no key-holding row, is not us, and was not mined before the removal. An authenticated sender is not an
 * authorised one: the relay seal proves who sent the message, not that the sender is a member.
 *
 * [ZMSGGroupProtocol.isRemovedByRelayKey]: on any phone, a relay message whose seal key is the relay key of a LEFT row
 * is held whatever address it maps to, unless the same key also belongs to a non-LEFT key-holding row (review C: a
 * contact code can bind one relay key to two addresses).
 *
 * Both functions are new: they are reached by name so this file compiles before they exist. All values are synthetic.
 */
class PreRemovalSenderMustBeMemberTest {
    private fun fn(name: String): Method {
        val m = ZMSGGroupProtocol::class.java.methods.firstOrNull { it.name == name }
        assertNotNull("ZMSGGroupProtocol.$name does not exist", m)
        return m!!
    }

    private fun call(
        name: String,
        vararg args: Any?,
    ): Boolean =
        try {
            fn(name).invoke(ZMSGGroupProtocol, *args) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun preRemovalNonMember(
        decryptedEpoch: Int,
        removalFloor: Int,
        rosterAuthoritative: Boolean,
        senderIsKeyHolderRow: Boolean,
        isSelf: Boolean,
        minedBeforeRemoval: Boolean,
    ): Boolean =
        call(
            "isPreRemovalNonMember",
            decryptedEpoch,
            removalFloor,
            rosterAuthoritative,
            senderIsKeyHolderRow,
            isSelf,
            minedBeforeRemoval,
        )

    @Test
    fun `isPreRemovalNonMember - held only for a non-row, non-self sender under a retired key on an authoritative roster`() {
        // The full truth table over the six inputs.
        for (epochBelow in listOf(true, false)) {
            for (authoritative in listOf(true, false)) {
                for (row in listOf(true, false)) {
                    for (self in listOf(true, false)) {
                        for (mined in listOf(true, false)) {
                            val expected = epochBelow && authoritative && !row && !self && !mined
                            val got =
                                preRemovalNonMember(
                                    decryptedEpoch = if (epochBelow) 1 else 2,
                                    removalFloor = 2,
                                    rosterAuthoritative = authoritative,
                                    senderIsKeyHolderRow = row,
                                    isSelf = self,
                                    minedBeforeRemoval = mined,
                                )
                            assertEquals(
                                "epochBelow=$epochBelow authoritative=$authoritative row=$row self=$self minedBefore=$mined",
                                expected,
                                got,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `isPreRemovalNonMember - floor 0 (no removal ever) never holds`() {
        assertEquals(false, preRemovalNonMember(0, 0, true, false, false, false))
        assertEquals(false, preRemovalNonMember(-1, 0, true, false, false, false))
    }

    @Test
    fun `isPreRemovalNonMember - a message at the floor epoch or above is never held`() {
        assertEquals(false, preRemovalNonMember(3, 3, true, false, false, false))
        assertEquals(false, preRemovalNonMember(4, 3, true, false, false, false))
        assertEquals(true, preRemovalNonMember(2, 3, true, false, false, false))
    }

    private fun removedByRelayKey(
        senderRelayKey: String?,
        left: Set<String>,
        holders: Set<String>,
    ): Boolean = call("isRemovedByRelayKey", senderRelayKey, left, holders)

    @Test
    fun `isRemovedByRelayKey - a LEFT row's relay key that no key holder shares is held`() {
        assertEquals(true, removedByRelayKey(KEY_X, setOf(KEY_X), setOf(KEY_B)))
        assertEquals(true, removedByRelayKey(KEY_X, setOf(KEY_X, KEY_OTHER), emptySet()))
    }

    @Test
    fun `isRemovedByRelayKey - matching ignores case, as relay keys are hex`() {
        assertEquals(true, removedByRelayKey(KEY_X.uppercase(), setOf(KEY_X), emptySet()))
        assertEquals(false, removedByRelayKey(KEY_X.uppercase(), setOf(KEY_X), setOf(KEY_X)))
    }

    @Test
    fun `isRemovedByRelayKey - a key a non-LEFT key holder also holds is not held (review C)`() {
        assertEquals(false, removedByRelayKey(KEY_X, setOf(KEY_X), setOf(KEY_X, KEY_B)))
    }

    @Test
    fun `isRemovedByRelayKey - no relay key, or a key no LEFT row holds, is not held`() {
        assertEquals(false, removedByRelayKey(null, setOf(KEY_X), emptySet()))
        assertEquals(false, removedByRelayKey(KEY_B, setOf(KEY_X), emptySet()))
        assertEquals(false, removedByRelayKey(KEY_B, emptySet(), emptySet()))
    }

    private companion object {
        val KEY_X = "ab".repeat(32)
        val KEY_B = "cd".repeat(32)
        val KEY_OTHER = "ef".repeat(32)
    }
}
