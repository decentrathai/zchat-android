package co.electriccoin.zcash.ui.screen.chat.datasource

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ZC-421 (fix-all 2.13.0, train T6b; ZC-420-SPEC §14.5 key decision (5), §14.7): a stored relay row keeps the second and the
 * id of the rumor it published (PendingMessageData.relayRumorAtSec and relayRumorId), in the encrypted pending store.
 *
 * At HEAD the row has neither field, so a Retry after a restart cannot make the same rumor again. Wanted: both fields
 * round-trip through the row's JSON and through the real ZchatPreferencesImpl (commitPendingMessage of a copy keeps them);
 * a row without the keys (2.12.4, an earlier 2.13.0 build) loads them as null; a null field writes no key; the pending store
 * is the encrypted one. The fields do not exist at HEAD, so they are reached by name. Every value is synthetic.
 */
class RelayRowKeepsItsRumorSecondTest {
    private val base =
        ZchatPreferences.PendingMessageData(
            id = "nmsg-rid421store",
            text = "synthetic zc421 stored row",
            timestampMillis = 1_790_000_000_000L,
            peerAddress = "usyntheticzc421store000001",
            isOutgoing = true,
            isPending = false,
            status = "FAILED",
            submitTracked = true,
        )

    /** [base] with the two new fields, through the data class's copy (by name: they do not exist at HEAD). */
    private fun withPair(
        row: ZchatPreferences.PendingMessageData,
        sec: Long?,
        id: String?,
    ): ZchatPreferences.PendingMessageData {
        val copy =
            ZchatPreferences.PendingMessageData::class.java.methods.firstOrNull { m ->
                m.name == "copy" && m.parameterTypes.size == FIELD_COUNT_AFTER
            } ?: throw AssertionError("PendingMessageData has no relayRumorAtSec / relayRumorId (ZC-421)")
        val names = ZchatPreferences.PendingMessageData::class.java.declaredFields.map { it.name }
        val args =
            copy.parameterTypes.indices.map { i ->
                val field = ZchatPreferences.PendingMessageData::class.java.getDeclaredField(names.filter { it in FIELDS }[i])
                field.isAccessible = true
                when (field.name) {
                    "relayRumorAtSec" -> sec
                    "relayRumorId" -> id
                    else -> field.get(row)
                }
            }
        return copy.invoke(row, *args.toTypedArray()) as ZchatPreferences.PendingMessageData
    }

    private fun secOf(row: ZchatPreferences.PendingMessageData): Any? = getter(row, "getRelayRumorAtSec")

    private fun idOf(row: ZchatPreferences.PendingMessageData): Any? = getter(row, "getRelayRumorId")

    private fun getter(
        row: ZchatPreferences.PendingMessageData,
        name: String,
    ): Any? {
        val m =
            row.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?: throw AssertionError("PendingMessageData.$name does not exist (ZC-421)")
        return m.invoke(row)
    }

    @Test
    fun `both fields round-trip through the row's JSON`() {
        val row = withPair(base, 1_790_000_000L, "rid421store")
        val back = ZchatPreferences.PendingMessageData.fromJson(ZchatPreferences.PendingMessageData.toJson(row))
        assertEquals(row, back)
        assertEquals(1_790_000_000L, secOf(back))
        assertEquals("rid421store", idOf(back))
    }

    @Test
    fun `a row without the keys loads both as null, and a null field writes no key`() {
        val old =
            """{"id":"nmsg-rid421old","text":"synthetic old row","timestampMillis":1790000000000,""" +
                """"peerAddress":"usyntheticzc421store000002","isOutgoing":true,"isPending":false,"status":"FAILED"}"""
        val loaded = ZchatPreferences.PendingMessageData.fromJson(old)
        assertNull(secOf(loaded))
        assertNull(idOf(loaded))

        val json = JSONObject(ZchatPreferences.PendingMessageData.toJson(withPair(base, null, null)))
        assertFalse("relayRumorAtSec was written for a null field", json.has("relayRumorAtSec"))
        assertFalse("relayRumorId was written for a null field", json.has("relayRumorId"))
        val one = JSONObject(ZchatPreferences.PendingMessageData.toJson(withPair(base, 1_790_000_001L, null)))
        assertTrue(one.has("relayRumorAtSec"))
        assertFalse(one.has("relayRumorId"))
    }

    @Test
    fun `the real store keeps both fields through commitPendingMessage of a copy`() {
        val (impl, _) = realPreferences()
        val row = withPair(base, 1_790_000_002L, "rid421real")
        impl.commitPendingMessage(row)
        impl.commitPendingMessage(impl.getPendingMessages().single().copy(status = "SENT", failureReason = null))

        val stored = impl.getPendingMessages().single()
        assertEquals("SENT", stored.status)
        assertEquals(1_790_000_002L, secOf(stored))
        assertEquals("rid421real", idOf(stored))
    }

    @Test
    fun `the pending store is the encrypted one`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/java").isDirectory) dir = dir.parentFile
        val source =
            File(
                dir ?: error("repo root not found"),
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt",
            ).readText()
        assertTrue(
            "the pending store is not created with createEncryptedPrefs",
            Regex("""private val pendingMsgPrefs: SharedPreferences = createEncryptedPrefs\(""").containsMatchIn(source),
        )
    }

    private companion object {
        val FIELDS =
            setOf(
                "id",
                "text",
                "timestampMillis",
                "peerAddress",
                "isOutgoing",
                "isPending",
                "status",
                "replyToId",
                "replyToPreview",
                "fileZfileContent",
                "paymentRequestAmountZatoshi",
                "paymentRequestReason",
                "sendAmountZatoshi",
                "submitTracked",
                "submitStartedAtMillis",
                "submitAttemptHeight",
                "submittedTxIds",
                "failureReason",
                "submittedExpiries",
                "attemptProcessId",
                "attemptAccountHash",
                "releaseAnchorHeight",
                "supersededTxIds",
                "resolvedNotSentAtHeight",
                "relayRumorAtSec",
                "relayRumorId",
                "acceptedPriceZatoshi",
                "acceptedAccountKey",
                "acceptedMode",
            )
        val FIELD_COUNT_AFTER = FIELDS.size
    }
}
