package co.electriccoin.zcash.ui.common.model.metadata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object MetadataSimpleSwapAssetV3Serializer : KSerializer<MetadataSimpleSwapAssetV3> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("MetadataSimpleSwapAsset", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MetadataSimpleSwapAssetV3) {
        encoder.encodeString("${value.token}.${value.chain}")
    }

    override fun deserialize(decoder: Decoder): MetadataSimpleSwapAssetV3 {
        val string = decoder.decodeString()
        // Split from the RIGHT: chain tickers never contain a dot, but token tickers can (e.g.
        // "USDT.e", "BTC.b" on avax). A left split corrupted such tokens, and split(".")[1] threw
        // IndexOutOfBounds on a dot-free value.
        return MetadataSimpleSwapAssetV3(
            token = string.substringBeforeLast("."),
            chain = string.substringAfterLast(".")
        )
    }
}
