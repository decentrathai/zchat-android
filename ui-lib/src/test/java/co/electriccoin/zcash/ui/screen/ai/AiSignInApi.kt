package co.electriccoin.zcash.ui.screen.ai

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AI-KEY-D (W-53): how the tests of the AI sign-in key reach the new code, by name, so that the RED commit compiles
 * before `AiSignInKey`, `AiSignInProof` and `AiAuthEndpoint` exist, and the fixed vectors of the design
 * (AI-SIGNIN-KEY-SPEC 2.2). Everything here is public test data: the seeds are the byte sequences 00..1f and 00..3f.
 *
 * [Independent] makes the key a second time from the formula of spec 2.1 with the JDK's own parts only (`Mac`,
 * `BigInteger`, the JDK's P-256 parameters). It shares no line with the app's derivation.
 */
internal object AiSignInApi {
    const val V1_PUBLIC =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKBWgfHW3NmiKtbT+c+oEu5Dx0Z2DLftLOnxZwpcTYJb1NLnDLU4rSWTsl8kyCOIm7/u/ddZQbK" +
            "T6N9maPZpozg=="
    const val V2_PUBLIC =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEReDg8otE6Sot80sl0Nyorg6ugpEKy3h+WyC0AtK9aiOZhJxSU6r8+K2Mtns3B2Pf9Y6EC8Bi98" +
            "/FS913fttEGg=="
    const val SPKI_BYTES = 91
    const val SPKI_HEADER_BYTES = 26
    const val FIELD_BYTES = 32

    private const val PACKAGE = "co.electriccoin.zcash.ui.screen.ai"

    fun bytes(count: Int): ByteArray = ByteArray(count) { it.toByte() }

    /** The two halves of a derived key, read by name: the tests do not depend on the type the app returns. */
    data class Halves(
        val publicKey: String,
        val privateKey: String,
    )

    /** What `AiSignInKey.derive` returns, as it is (ZC-956: the tests of what that object prints and offers). */
    fun deriveRaw(seed: ByteArray): Any =
        call("AiSignInKey", "derive", seed) ?: throw AssertionError("derive gave null")

    fun derive(seed: ByteArray): Halves {
        val key = deriveRaw(seed)
        return Halves(half(key, "getPublicKey"), half(key, "getPrivateKey"))
    }

    private fun half(
        key: Any,
        getter: String,
    ): String =
        key.javaClass.methods
            .firstOrNull { it.name == getter && it.parameterCount == 0 }
            ?.invoke(key) as? String
            ?: throw AssertionError("the derived key has no $getter")

    fun keyId(publicKeyBase64: String): String = call("AiSignInKey", "keyId", publicKeyBase64) as String

    fun serviceName(): String = call("AiSignInProof", "getServiceName") as String

    @Suppress("LongParameterList")
    fun message(
        purpose: String,
        nonce: String,
        exp: Long,
        keyId: String,
        account: String = "-",
        wallet: String = "-",
        create: Boolean = false,
    ): String = call("AiSignInProof", "message", purpose, nonce, exp, keyId, account, wallet, create) as String

    @Suppress("LongParameterList")
    fun sign(
        privateKeyBase64: String,
        purpose: String,
        nonce: String,
        exp: Long,
        keyId: String,
        account: String = "-",
        wallet: String = "-",
        create: Boolean = false,
    ): String =
        call("AiSignInProof", "sign", privateKeyBase64, purpose, nonce, exp, keyId, account, wallet, create) as String

    fun endpoint(member: String, vararg args: Any): Any? = call("AiAuthEndpoint", member, *args)

    private fun call(
        objectName: String,
        member: String,
        vararg args: Any,
    ): Any? {
        val type =
            try {
                Class.forName("$PACKAGE.$objectName")
            } catch (e: ClassNotFoundException) {
                throw AssertionError("$objectName does not exist", e)
            }
        val method =
            type.methods.firstOrNull { it.name == member && it.parameterCount == args.size }
                ?: throw AssertionError("$objectName has no $member with ${args.size} parameters")
        return try {
            method.invoke(type.getField("INSTANCE").get(null), *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    fun der(publicKeyBase64: String): ByteArray = Base64.getDecoder().decode(publicKeyBase64)

    /** The X coordinate of an uncompressed P-256 point in its 91-byte DER form. */
    fun xOf(publicKeyBase64: String): ByteArray =
        der(publicKeyBase64).copyOfRange(SPKI_HEADER_BYTES + 1, SPKI_HEADER_BYTES + 1 + FIELD_BYTES)

    /** The private number of a PKCS#8 private key, read by the JDK. */
    fun privateNumber(privateKeyBase64: String): BigInteger {
        val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64))
        return (KeyFactory.getInstance("EC").generatePrivate(spec) as ECPrivateKey).s
    }

    // ------------------------------------------------------------------------------------------ the source

    val uiLib: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        generateSequence(start) { it.parentFile }
            .take(PARENTS_TO_TRY)
            .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
            .first { it.resolve("src/main/res/ui/common/values").isDirectory }
    }

    private const val PARENTS_TO_TRY = 8
    private const val AI_FOLDER = "java/co/electriccoin/zcash/ui/screen/ai"

    fun mainAiFile(name: String): File = uiLib.resolve("src/main/$AI_FOLDER/$name")

    fun variantAiFile(
        sourceSet: String,
        name: String,
    ): File = uiLib.resolve("src/$sourceSet/$AI_FOLDER/$name")

    fun read(file: File): String {
        if (!file.isFile) throw AssertionError("${file.relativeTo(uiLib)} does not exist")
        return file.readText()
    }

    /** Every Kotlin file of the app's own code: shared, debug-only and release-only. */
    fun productSources(): List<File> =
        listOf("main", "debug", "release", "foss", "store")
            .map { uiLib.resolve("src/$it") }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** Spec 2.1, a second time, with the JDK alone. */
    object Independent {
        const val SALT = "ZCHAT_AI_SIGNIN_SEED"
        const val LABEL = "zchat-ai-signin-v1:zsend.xyz"
        private const val OKM_BYTES = 48
        private const val HMAC = "HmacSHA256"

        val p256: ECParameterSpec by lazy {
            AlgorithmParameters
                .getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
        }

        private fun hmac(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)

        /** RFC 5869 with SHA-256: extract, then two blocks of expand cut to 48 bytes. */
        private fun hkdf(
            seed: ByteArray,
            salt: String,
            label: String,
        ): ByteArray {
            val prk = hmac(salt.toByteArray(Charsets.US_ASCII), seed)
            val info = label.toByteArray(Charsets.US_ASCII)
            val first = hmac(prk, info + byteArrayOf(1))
            val second = hmac(prk, first + info + byteArrayOf(2))
            return (first + second).copyOf(OKM_BYTES)
        }

        /** d = (OS2IP(okm) mod (n - 1)) + 1, with the JDK's own n. */
        fun privateNumber(
            seed: ByteArray,
            salt: String = SALT,
            label: String = LABEL,
        ): BigInteger = BigInteger(1, hkdf(seed, salt, label)).mod(p256.order.subtract(BigInteger.ONE)).add(BigInteger.ONE)
    }
}
