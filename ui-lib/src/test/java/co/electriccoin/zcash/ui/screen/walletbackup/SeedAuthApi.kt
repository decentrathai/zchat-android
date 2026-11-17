package co.electriccoin.zcash.ui.screen.walletbackup

import co.electriccoin.zcash.ui.R
import java.io.File
import java.lang.reflect.Method

/**
 * SEED-AUTH (fix-all 2.13.0, train T4b): the strict "confirm before a secret" gate as the SEED-AUTH tests reach it,
 * by name, so the tests compile before the gate exists (the RED commit). Every call fails with
 * ClassNotFoundException / NoSuchMethodException / NoSuchFieldException until the fix adds it.
 */
internal object SeedAuthApi {
    private const val REPOSITORY_PACKAGE = "co.electriccoin.zcash.ui.common.repository"

    /** The file class of `common/repository/SecretAccessGate.kt`. */
    private fun gateFile(): Class<*> = Class.forName("$REPOSITORY_PACKAGE.SecretAccessGateKt")

    private fun gateMethod(
        name: String,
        count: Int
    ): Method =
        gateFile()
            .declaredMethods
            .firstOrNull { it.name == name && it.parameterCount == count }
            ?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("SecretAccessGateKt.$name/$count")

    /** What the gate decides: "NoScreenLock", or "Prompt" with the authenticators it asks the system prompt for. */
    data class Decision(
        val kind: String,
        val authenticators: Int?
    )

    fun gate(
        isDeviceSecure: Boolean,
        canAuthenticateStatus: Int,
        sdkInt: Int
    ): Decision {
        val result =
            checkNotNull(gateMethod("secretAccessGate", 3).invoke(null, isDeviceSecure, canAuthenticateStatus, sdkInt)) {
                "secretAccessGate returned null"
            }
        val kind = result.javaClass.simpleName
        val authenticators =
            result.javaClass.methods
                .firstOrNull { it.name == "getAuthenticators" && it.parameterCount == 0 }
                ?.invoke(result) as Int?
        return Decision(kind, authenticators)
    }

    /** True when a biometric-prompt error on a secret request falls back to the phone's screen-lock screen. */
    fun credentialFallback(errorCode: Int): Boolean = gateMethod("secretCredentialFallback", 1).invoke(null, errorCode) as Boolean

    /** The constant [name] of the `SecretAccess` enum (CONFIRMED, NO_SCREEN_LOCK). */
    fun secretAccess(name: String): Any =
        Class
            .forName("$REPOSITORY_PACKAGE.SecretAccess")
            .enumConstants!!
            .first { (it as Enum<*>).name == name }

    /** The `securityNotice` of a screen state (NoSuchMethodException until the state has one). */
    fun securityNotice(state: WalletBackupState): Any? {
        val getter =
            state.javaClass.methods.firstOrNull { it.name == "getSecurityNotice" && it.parameterCount == 0 }
                ?: throw NoSuchMethodException("WalletBackupState.securityNotice")
        return getter.invoke(state)
    }

    /** `R.string.<name>` of ui-lib (NoSuchFieldException until the string exists). */
    fun stringId(name: String): Int = R.string::class.java.getField(name).getInt(null)

    /** Calls `onScreenStopped()` of the screen's view model (NoSuchMethodException until it exists). */
    fun screenStopped(viewModel: WalletBackupViewModel) {
        viewModel.javaClass.getMethod("onScreenStopped").invoke(viewModel)
    }

    /** The text of a ui-lib source file, [path] relative to `src/main/java/co/electriccoin/zcash/ui`. */
    fun source(path: String): String = mainRoot().resolve("src/main/java/co/electriccoin/zcash/ui/$path").readText()

    /** The text of a ui-lib resource file, [path] relative to `src/main/res`. */
    fun resource(path: String): String = mainRoot().resolve("src/main/res/$path").readText()

    /** Every Kotlin file under ui-lib's `src/main/java`. */
    fun mainKotlinFiles(): List<File> =
        mainRoot()
            .resolve("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun mainRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .take(8)
            .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
            .first { it.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory }

    /** The text between the braces of the first `{` block after [signature] in [source]. */
    fun bodyAfter(
        source: String,
        signature: String
    ): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "signature not found: $signature" }
        val open = source.indexOf('{', start + signature.length)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        error("unbalanced braces after: $signature")
    }
}
