package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptEntry
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptKind
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptState
import co.electriccoin.zcash.ui.common.sendattempt.StoredSendAttemptLedger
import org.json.JSONArray
import org.json.JSONObject

/**
 * F8 (ZC-112, ZC-600): the send-attempt ledger on disk, one JSON string per entry under [PREFIX] in the store it is
 * given. [ZchatPreferencesImpl] gives it the encrypted held-reply store, so nothing of an entry (its subject, identity,
 * payload or ids) is ever in a plain file; that store is wallet-scoped and wiped with it. The ledger reads only its own
 * prefix, and the held-reply readers read only theirs.
 *
 * Every write is a checked commit(). A commit that returns false and one that throws (the store cannot encrypt) are the
 * same failed write; a read that throws is an unreadable ledger. An entry that cannot be decoded reads as unreadable
 * for its key, never as "no entry": a doubt pays nothing.
 */
class PrefsSendAttemptLedger(
    private val prefs: () -> SharedPreferences,
) : StoredSendAttemptLedger() {
    override fun load(key: String): SendAttemptEntry? = prefs().getString(PREFIX + key, null)?.let(::decode)

    override fun loadAll(): List<SendAttemptEntry> =
        prefs()
            .all
            .filterKeys { it.startsWith(PREFIX) }
            .values
            .mapNotNull { value -> (value as? String)?.let { runCatching { decode(it) }.getOrNull() } }

    override fun store(
        key: String,
        entry: SendAttemptEntry?,
    ): Boolean {
        val editor = prefs().edit()
        if (entry == null) editor.remove(PREFIX + key) else editor.putString(PREFIX + key, encode(entry))
        return editor.commit()
    }

    companion object {
        const val PREFIX = "sendattempt:"

        fun encode(entry: SendAttemptEntry): String =
            JSONObject()
                .apply {
                    put("kind", entry.kind.name)
                    put("subject", entry.subject)
                    put("identity", entry.identity)
                    put("startedAt", entry.startedAtMillis)
                    entry.attemptHeight?.let { put("attemptHeight", it) }
                    entry.processId?.let { put("processId", it) }
                    entry.accountHash?.let { put("accountHash", it) }
                    put("txIds", JSONArray(entry.txIds))
                    put("expiries", JSONObject().apply { entry.expiries.forEach { (id, height) -> put(id, height) } })
                    entry.releaseAnchorHeight?.let { put("releaseAnchorHeight", it) }
                    put("supersededTxIds", JSONArray(entry.supersededTxIds))
                    entry.provenNotSentAtHeight?.let { put("provenNotSentAtHeight", it) }
                    put("state", entry.state.name)
                    entry.minedHeight?.let { put("minedHeight", it) }
                    put("idsBeforeSubmit", entry.idsBeforeSubmit)
                    entry.payload?.let { put("payload", it) }
                    entry.reason?.let { put("reason", it) }
                }.toString()

        fun decode(record: String): SendAttemptEntry {
            val json = JSONObject(record)

            fun long(name: String): Long? = if (json.has(name)) json.getLong(name) else null

            fun text(name: String): String? = if (json.has(name)) json.getString(name) else null

            fun list(name: String): List<String> {
                val array = json.optJSONArray(name) ?: return emptyList()
                return (0 until array.length()).map { array.getString(it) }
            }

            val expiries = json.optJSONObject("expiries")
            return SendAttemptEntry(
                kind = SendAttemptKind.valueOf(json.getString("kind")),
                subject = json.getString("subject"),
                identity = json.getString("identity"),
                startedAtMillis = json.getLong("startedAt"),
                attemptHeight = long("attemptHeight"),
                processId = text("processId"),
                accountHash = text("accountHash"),
                txIds = list("txIds"),
                expiries = expiries?.keys()?.asSequence()?.associateWith { expiries.getLong(it) } ?: emptyMap(),
                releaseAnchorHeight = long("releaseAnchorHeight"),
                supersededTxIds = list("supersededTxIds"),
                provenNotSentAtHeight = long("provenNotSentAtHeight"),
                state = SendAttemptState.valueOf(json.getString("state")),
                minedHeight = long("minedHeight"),
                idsBeforeSubmit = json.optBoolean("idsBeforeSubmit", false),
                payload = text("payload"),
                reason = text("reason"),
            )
        }
    }
}
