package co.electriccoin.zcash.ui.common.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: BigDecimal
    ) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        // 1Click sends some BigDecimal fields as bare JSON numbers (e.g. `"price": 2.42`) and
        // others as quoted strings. Reading the primitive content handles both forms; plain
        // decodeString() throws on an unquoted number under the (strict) default Json.
        val content =
            (decoder as? JsonDecoder)?.decodeJsonElement()?.jsonPrimitive?.content
                ?: decoder.decodeString()
        return BigDecimal(content)
    }
}
