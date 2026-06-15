package co.electriccoin.zcash.ui.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Minimal NIP-01 event serializer / signer / verifier. Hand-rolled JSON to avoid
 * adding kotlinx-serialization to ui-lib for one structure — the NIP-01 canonical
 * form is byte-stable so escapes must match the spec exactly.
 *
 * Event = {
 *   "id":         sha256(canonical([0, pubkey, created_at, kind, tags, content])),
 *   "pubkey":     hex of 32-byte x-only secp256k1,
 *   "created_at": unix seconds,
 *   "kind":       integer,
 *   "tags":       [[string, ...], ...],
 *   "content":    string,
 *   "sig":        hex of Schnorr signature over id,
 * }
 */
internal object NostrEvent {

    /** Build, sign, and serialize a NIP-01 event JSON, returning the wire string. */
    fun signAndSerialize(
        pubkeyHex: String,
        privateKey: ByteArray,
        createdAtSec: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String {
        val tagsJson = tagsToJson(tags)
        val canonical = "[0,\"$pubkeyHex\",$createdAtSec,$kind,$tagsJson,${jsonString(content)}]"
        val eventId = sha256(canonical.toByteArray(Charsets.UTF_8))
        val idHex = eventId.toLowerHex()

        val auxRand = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sig = Secp256k1.signSchnorr(eventId, privateKey, auxRand).toLowerHex()

        return buildString {
            append('{')
            append("\"id\":\"$idHex\",")
            append("\"pubkey\":\"$pubkeyHex\",")
            append("\"created_at\":$createdAtSec,")
            append("\"kind\":$kind,")
            append("\"tags\":$tagsJson,")
            append("\"content\":${jsonString(content)},")
            append("\"sig\":\"$sig\"")
            append('}')
        }
    }

    /** Build an unsigned event (a "rumor" in NIP-59 vocab). No id/sig fields. */
    fun unsignedSerialize(
        pubkeyHex: String,
        createdAtSec: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String {
        val tagsJson = tagsToJson(tags)
        val canonical = "[0,\"$pubkeyHex\",$createdAtSec,$kind,$tagsJson,${jsonString(content)}]"
        val idHex = sha256(canonical.toByteArray(Charsets.UTF_8)).toLowerHex()
        return buildString {
            append('{')
            append("\"id\":\"$idHex\",")
            append("\"pubkey\":\"$pubkeyHex\",")
            append("\"created_at\":$createdAtSec,")
            append("\"kind\":$kind,")
            append("\"tags\":$tagsJson,")
            append("\"content\":${jsonString(content)}")
            append('}')
        }
    }

    /**
     * Verify a signed NIP-01 event's id and Schnorr signature.
     * Returns the parsed Event on success. Throws on any validation failure.
     */
    fun verify(json: String): Event {
        val pubkeyHex = parseStringField(json, "pubkey")
        val createdAt = parseLongField(json, "created_at")
        val kind = parseIntField(json, "kind")
        val tagsRaw = parseRawField(json, "tags")
        val content = parseStringField(json, "content")
        val id = parseStringField(json, "id")
        val sig = parseStringField(json, "sig")

        // Recompute id and check.
        val canonical = "[0,\"$pubkeyHex\",$createdAt,$kind,$tagsRaw,${jsonString(content)}]"
        val expectedId = sha256(canonical.toByteArray(Charsets.UTF_8)).toLowerHex()
        require(expectedId == id) { "event id mismatch (expected $expectedId, got $id)" }

        val ok = Secp256k1.verifySchnorr(
            signature = hexToBytes(sig),
            data = hexToBytes(id),
            pub = hexToBytes(pubkeyHex),
        )
        require(ok) { "Schnorr signature invalid" }

        return Event(
            id = id,
            pubkeyHex = pubkeyHex,
            createdAtSec = createdAt,
            kind = kind,
            tagsRaw = tagsRaw,
            content = content,
            sig = sig,
        )
    }

    data class Event(
        val id: String,
        val pubkeyHex: String,
        val createdAtSec: Long,
        val kind: Int,
        val tagsRaw: String,
        val content: String,
        val sig: String,
    )

    fun parsePubkey(json: String): String = parseStringField(json, "pubkey")
    fun parseCreatedAt(json: String): Long = parseLongField(json, "created_at")
    fun parseContent(json: String): String = parseStringField(json, "content")
    fun parseKind(json: String): Int = parseIntField(json, "kind")
    /** The NIP-01 event id (sha256 of the canonical serialization). For a rumor this is a STABLE,
     *  content-derived id both sender and recipient can agree on — unlike the per-recipient gift-wrap id. */
    fun parseId(json: String): String = parseStringField(json, "id")

    private fun tagsToJson(tags: List<List<String>>): String =
        tags.joinToString(",", prefix = "[", postfix = "]") { tag ->
            tag.joinToString(",", prefix = "[", postfix = "]") { jsonString(it) }
        }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun parseStringField(json: String, key: String): String {
        val needle = "\"$key\":\""
        val start = json.indexOf(needle).let { idx ->
            require(idx >= 0) { "missing field: $key" }
            idx + needle.length
        }
        val sb = StringBuilder()
        var i = start
        while (i < json.length) {
            val ch = json[i]
            if (ch == '"') return sb.toString()
            if (ch == '\\' && i + 1 < json.length) {
                when (val next = json[i + 1]) {
                    '"', '\\', '/' -> sb.append(next)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'u' -> {
                        require(i + 5 < json.length) { "bad unicode escape" }
                        val hex = json.substring(i + 2, i + 6)
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(next)
                }
                i += 2
                continue
            }
            sb.append(ch)
            i++
        }
        throw IllegalStateException("unterminated string for $key")
    }

    private fun parseIntField(json: String, key: String): Int {
        val needle = "\"$key\":"
        val start = json.indexOf(needle).let {
            require(it >= 0) { "missing field: $key" }
            it + needle.length
        }
        var end = start
        while (end < json.length && (json[end].isDigit() || json[end] == '-')) end++
        return json.substring(start, end).toInt()
    }

    private fun parseLongField(json: String, key: String): Long {
        val needle = "\"$key\":"
        val start = json.indexOf(needle).let {
            require(it >= 0) { "missing field: $key" }
            it + needle.length
        }
        var end = start
        while (end < json.length && (json[end].isDigit() || json[end] == '-')) end++
        return json.substring(start, end).toLong()
    }

    private fun parseRawField(json: String, key: String): String {
        val needle = "\"$key\":"
        val start = json.indexOf(needle).let {
            require(it >= 0) { "missing field: $key" }
            it + needle.length
        }
        // Field is a JSON array. Match brackets.
        require(json[start] == '[') { "field $key not an array" }
        var depth = 0
        var i = start
        while (i < json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return json.substring(start, i + 1) }
                '"' -> {
                    // skip string content
                    i++
                    while (i < json.length && json[i] != '"') {
                        if (json[i] == '\\') i++
                        i++
                    }
                }
            }
            i++
        }
        throw IllegalStateException("unterminated tags array")
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun ByteArray.toLowerHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
