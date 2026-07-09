package co.electriccoin.zcash.ui.common.model.metadata.v2

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object SwapProviderV2Serializer : KSerializer<SwapProviderV2> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SwapProvider", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SwapProviderV2) {
        encoder.encodeString("${value.provider}.${value.token}.${value.chain}")
    }

    override fun deserialize(decoder: Decoder): SwapProviderV2 {
        val string = decoder.decodeString()
        // Format is "provider.token.chain". provider and chain never contain a dot, but token can
        // (e.g. "USDT.e"), so peel provider from the LEFT and chain from the RIGHT, leaving the token
        // as the middle. A plain left split corrupted dotted tokens. Missing-delimiter fallbacks keep
        // legacy dot-free values reading exactly as before.
        val provider = string.substringBefore(".", string)
        val remainder = string.substringAfter(".", "")
        return SwapProviderV2(
            provider = provider,
            token = remainder.substringBeforeLast(".", remainder),
            chain = remainder.substringAfterLast(".", ""),
        )
    }
}
