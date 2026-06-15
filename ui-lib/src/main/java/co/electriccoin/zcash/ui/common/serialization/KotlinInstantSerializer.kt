package co.electriccoin.zcash.ui.common.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * Serializes [kotlin.time.Instant] as an ISO-8601 string (e.g. "2025-01-01T00:00:00Z").
 *
 * The 1Click NEAR API encodes `deadline`/`timestamp` as ISO-8601 strings, which is exactly
 * the format produced by [Instant.toString] / parsed by [Instant.parse]. This matches the
 * runtime behavior of kotlinx.serialization 1.9.0's built-in Instant serializer, but is
 * provided explicitly because the bundled serialization-core (1.8.x) ships no serializer for
 * [kotlin.time.Instant]; without it nested deserialization of these DTOs fails at runtime.
 */
object KotlinInstantSerializer : KSerializer<Instant> {
    // Descriptor name must NOT be "kotlin.time.Instant": kotlinx-serialization's runtime already
    // registers a built-in InstantSerializer under that exact name, and PrimitiveSerialDescriptor
    // rejects a duplicate ("...should uniquely identify associated serializer"), which threw at
    // class-init and broke deserialization of every quote/status response. Use a unique name.
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("co.electriccoin.zcash.KotlinInstantIso8601", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
