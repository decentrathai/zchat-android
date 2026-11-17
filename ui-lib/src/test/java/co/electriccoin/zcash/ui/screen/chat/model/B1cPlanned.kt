package co.electriccoin.zcash.ui.screen.chat.model

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.Base64

/**
 * OT213n batch B1c test support (ZC-1474 as a feature, owner W-123; FIX_PLAN REVISION 2): reaches the planned API by
 * name, so these tests compile before it exists and fail on an assertion that names the missing piece.
 *
 * Planned names (package co.electriccoin.zcash.ui.screen.chat.model):
 * - `object AttachmentTransport { fun choose(mode: ConversationMode, peerNostrKeyBound: Boolean, peerCap2130: Boolean,
 *   hasE2ESharedKey: Boolean): Route }` with `enum Route { NOSTR_ZATT, E2E_WRAP, REFUSE }` (compared by name);
 * - `ZATTMessage` with `companion { const PREFIX = "ZATT|v1|"; fun build(hash: String, type: ZFILEType, size: Long,
 *   url: String, fileKey: ByteArray, blurhash: String, viewOnce: Boolean): String; fun parse(raw: String): ZATTMessage? }`
 *   and the properties hash, type, size, url, fileKey, blurhash, viewOnce.
 * Wire: `ZATT|v1|<hash 32 hex>|<type code>|<ciphertext size>|<viewOnce 0/1>|<https url>|<file key base64>|<blurhash>`.
 * The blurhash is last because its alphabet contains '|'. Every value in these tests is synthetic.
 */
internal object B1cPlanned {
    const val TRANSPORT = "co.electriccoin.zcash.ui.screen.chat.model.AttachmentTransport"
    const val ZATT = "co.electriccoin.zcash.ui.screen.chat.model.ZATTMessage"

    /** A synthetic 32-byte AES file key (never a real one). */
    val FILE_KEY: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }
    val FILE_KEY_B64: String = Base64.getEncoder().encodeToString(FILE_KEY)
    const val HASH = "abababababababababababababababab"
    const val URL = "https://nostr.download/synthetic-zatt-blob"

    /** The wire text the plan fixes, written out by hand (so receiver tests do not depend on the builder). */
    fun wire(
        hash: String = HASH,
        typeCode: String = ZFILEType.JPEG.code,
        size: Long = 4_096L,
        viewOnce: Boolean = false,
        url: String = URL,
        keyB64: String = FILE_KEY_B64,
        blurhash: String = "",
    ): String = "ZATT|v1|$hash|$typeCode|$size|${if (viewOnce) "1" else "0"}|$url|$keyB64|$blurhash"

    fun klass(name: String): Class<*> =
        try {
            Class.forName(name)
        } catch (e: ClassNotFoundException) {
            throw AssertionError("planned API $name does not exist yet (FIX_PLAN REVISION 2, W-123)", e)
        }

    /** Calls [method] with [args] on the Kotlin object, the companion, or a static of [className]. */
    fun call(
        className: String,
        method: String,
        vararg args: Any?,
    ): Any? {
        val k = klass(className)
        val receivers =
            listOfNotNull(
                runCatching { k.getField("INSTANCE").get(null) }.getOrNull(),
                runCatching { k.getField("Companion").get(null) }.getOrNull(),
            )
        for (receiver in receivers) {
            val m = receiver.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == args.size } ?: continue
            return invoke(m, receiver, args)
        }
        val static =
            k.methods.firstOrNull { it.name == method && it.parameterCount == args.size && Modifier.isStatic(it.modifiers) }
                ?: throw AssertionError("planned $className.$method with ${args.size} parameters does not exist yet")
        return invoke(static, null, args)
    }

    private fun invoke(
        m: java.lang.reflect.Method,
        receiver: Any?,
        args: Array<out Any?>,
    ): Any? =
        try {
            m.invoke(receiver, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    /** Reads Kotlin property [name] of [obj] (getX / isX / x). */
    fun prop(
        obj: Any,
        name: String,
    ): Any? {
        val cap = name.replaceFirstChar { it.uppercase() }
        val m =
            obj.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name in setOf("get$cap", "is$cap", name) }
                ?: throw AssertionError("${obj.javaClass.simpleName} has no property $name")
        return m.invoke(obj)
    }

    /** AttachmentTransport.choose(...) by name, as the Route constant's name. */
    fun choose(
        mode: ConversationMode,
        peerNostrKeyBound: Boolean,
        peerCap2130: Boolean,
        hasE2ESharedKey: Boolean,
    ): String = call(TRANSPORT, "choose", mode, peerNostrKeyBound, peerCap2130, hasE2ESharedKey).toString()

    fun build(
        hash: String = HASH,
        type: ZFILEType = ZFILEType.JPEG,
        size: Long = 4_096L,
        url: String = URL,
        fileKey: ByteArray = FILE_KEY,
        blurhash: String = "",
        viewOnce: Boolean = false,
    ): String = call(ZATT, "build", hash, type, size, url, fileKey, blurhash, viewOnce) as String

    fun parse(raw: String): Any? = call(ZATT, "parse", raw)
}
