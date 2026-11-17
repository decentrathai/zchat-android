package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.exception.CompactBlockProcessorException
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** ZC-538: only the SDK branch mismatch and the node's exact consensus text can block spends. */
class NetworkUpgradeGuardTriggersOnlyOnTheSdkBranchMismatchTest {
    @Before
    fun setUp() = NetworkUpgradeTestApi.resetIfPresent()

    @After
    fun tearDown() = NetworkUpgradeTestApi.resetIfPresent()

    @Test
    fun direct_and_wrapped_sdk_mismatches_latch() {
        val errors =
            listOf(
                NetworkUpgradeTestApi.mismatch(),
                RuntimeException("outer", RuntimeException("middle", NetworkUpgradeTestApi.mismatch())),
            )
        for (error in errors) {
            NetworkUpgradeTestApi.resetIfPresent()
            NetworkUpgradeTestApi.setup(error)
            assertNotNull(NetworkUpgradeTestApi.notice(), "SDK branch mismatch was discarded")
            NetworkUpgradeTestApi.assertRefusal(NetworkUpgradeTestApi.refusal())
        }
    }

    @Test
    fun node_phrase_latches_for_minus25_minus1_and_other_codes_ignoring_case() {
        for (code in listOf(-25, -1, 0, -999)) {
            NetworkUpgradeTestApi.resetIfPresent()
            NetworkUpgradeTestApi.submit(
                SubmitResult.Failure(listOf("synthetic-zc538-stored"), code, "node: INCORRECT CONSENSUS BRANCH ID")
            )
            assertNotNull(NetworkUpgradeTestApi.notice(), "exact node verdict must latch regardless of code $code")
        }
    }

    @Test
    fun partial_node_status_latches_without_reclassifying_the_stored_outcome() {
        val result =
            SubmitResult.Partial(
                listOf("synthetic-zc538-leg"),
                listOf("success", "code: -25 desc: incorrect consensus branch id"),
            )
        NetworkUpgradeTestApi.submit(result)
        assertNotNull(NetworkUpgradeTestApi.notice())
        assertTrue(result.txIds.isNotEmpty(), "the first rejected attempt remains stored")
    }

    @Test
    fun blank_nonhex_too_long_and_equal_server_branch_ids_never_latch() {
        for (server in listOf("", " ", "not-hex", "77190ad90", "37A5165B")) {
            NetworkUpgradeTestApi.resetIfPresent()
            NetworkUpgradeTestApi.setup(CompactBlockProcessorException.MismatchedConsensusBranch("37a5165b", server))
            assertNull(NetworkUpgradeTestApi.notice(), "invalid/equal server id $server latched")
            assertNull(NetworkUpgradeTestApi.refusal())
        }
    }

    @Test
    fun one_and_eight_digit_hex_server_ids_are_supported() {
        for (server in listOf("a", "ABCDEF09")) {
            NetworkUpgradeTestApi.resetIfPresent()
            NetworkUpgradeTestApi.setup(CompactBlockProcessorException.MismatchedConsensusBranch("37a5165b", server))
            assertNotNull(NetworkUpgradeTestApi.notice())
        }
    }

    @Test
    fun unrelated_sdk_setup_errors_never_latch() {
        val names =
            listOf(
                "MismatchedNetwork",
                "MismatchedSaplingActivationHeight",
                "BadBlockHeight",
                "NoAccount",
                "FailedDownloadException",
                "ConfigurationException",
            )
        for (name in names) {
            NetworkUpgradeTestApi.resetIfPresent()
            NetworkUpgradeTestApi.setup(NetworkUpgradeTestApi.sdkError(name))
            assertNull(NetworkUpgradeTestApi.notice(), "$name is not an update signal")
        }
    }

    @Test
    fun control_real_sdk_negative_types_and_grpc_unavailable_fixture_are_constructible() {
        val names =
            listOf(
                "MismatchedNetwork",
                "MismatchedSaplingActivationHeight",
                "BadBlockHeight",
                "NoAccount",
                "FailedDownloadException",
                "ConfigurationException",
            )
        for (name in names) {
            assertTrue(NetworkUpgradeTestApi.sdkError(name).javaClass.simpleName == name)
        }
        val transport = NetworkUpgradeTestApi.grpcUnavailable()
        assertTrue(transport.javaClass.name.startsWith("io.grpc.Status"))
        assertTrue(transport.cause is IOException)
    }

    @Test
    fun transport_gateway_configuration_cancellation_and_null_never_latch() {
        val errors =
            listOf(
                IOException("download"),
                SocketTimeoutException("timeout"),
                UnknownHostException("unreachable"),
                NetworkUpgradeTestApi.grpcUnavailable(),
                RuntimeException("502 gateway"),
                IllegalArgumentException("configuration"),
                CancellationException("cancelled"),
                CancellationException("cancelled mismatch operation").apply {
                    initCause(NetworkUpgradeTestApi.mismatch())
                },
                null,
            )
        for (error in errors) {
            NetworkUpgradeTestApi.resetIfPresent()
            NetworkUpgradeTestApi.setup(error)
            assertNull(NetworkUpgradeTestApi.notice(), "$error is not a consensus signal")
        }
    }

    @Test
    fun cause_depth_five_is_included_but_six_is_not() {
        var error: Throwable = NetworkUpgradeTestApi.mismatch()
        repeat(5) { error = RuntimeException("wrapper", error) }
        NetworkUpgradeTestApi.setup(error)
        assertNotNull(NetworkUpgradeTestApi.notice(), "root depth 0: five cause links are included")
        NetworkUpgradeTestApi.resetIfPresent()
        error = RuntimeException("sixth", error)
        NetworkUpgradeTestApi.setup(error)
        assertNull(NetworkUpgradeTestApi.notice())
    }

    @Test(timeout = 2_000)
    fun cyclic_cause_chain_terminates_without_a_false_latch() {
        val cyclic =
            object : RuntimeException("cycle") {
                override val cause: Throwable get() = this
            }
        NetworkUpgradeTestApi.setup(cyclic)
        assertNull(NetworkUpgradeTestApi.notice())
    }

    @Test
    fun sdk_notice_keeps_both_branch_ids_and_a_support_source() {
        NetworkUpgradeTestApi.latch()
        val notice = assertNotNull(NetworkUpgradeTestApi.notice())
        val values =
            notice.javaClass.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map {
                    it.isAccessible = true
                    it.get(notice)?.toString()
                }
        assertTrue("37a5165b" in values, "notice lost client branch id")
        assertTrue("77190ad9" in values, "notice lost server branch id")
        assertTrue(
            values.any { !it.isNullOrBlank() && it !in listOf("37a5165b", "77190ad9") },
            "notice lost its support source",
        )
    }

    @Test
    fun unrelated_node_text_and_other_submit_variants_never_latch() {
        val failures =
            listOf(
                "expiring soon",
                "fee too small",
                "inputs spent",
                "mempool conflict",
                "mempool full",
                "branch id does not match",
                "incorrect consensus branch",
                "",
                null,
            )
        val outcomes =
            failures.map { SubmitResult.Failure(emptyList(), -25, it) } +
                listOf(
                    SubmitResult.GrpcFailure(emptyList()),
                    SubmitResult.SentUnconfirmed(emptyList(), "incorrect consensus branch id"),
                    SubmitResult.Success(emptyList()),
                    SubmitResult.Partial(emptyList(), listOf("timeout")),
                )
        for (result in outcomes) {
            NetworkUpgradeTestApi.resetIfPresent()
            NetworkUpgradeTestApi.submit(result)
            assertNull(NetworkUpgradeTestApi.notice(), "$result is not the node consensus verdict")
        }
    }

    @Test
    fun two_signals_leave_one_notice_and_only_a_new_synchronizer_clears_it() {
        NetworkUpgradeTestApi.latch()
        val state = NetworkUpgradeTestApi.state()
        NetworkUpgradeTestApi.submit(SubmitResult.Failure(emptyList(), -1, "incorrect consensus branch id"))
        assertSame(state, NetworkUpgradeTestApi.state(), "both signals must feed one process-wide StateFlow")
        assertNotNull(state.value)
        NetworkUpgradeTestApi.setup(IOException("later error"))
        assertNotNull(state.value, "a later non-trigger must not clear the latch")
        NetworkUpgradeTestApi.started()
        assertNull(state.value)
        assertNull(NetworkUpgradeTestApi.refusal())
        NetworkUpgradeTestApi.latch()
        assertNotNull(state.value, "a repeated real mismatch must latch again")
    }
}

/** Reflection keeps permanent RED tests compilable before the new production API exists. */
internal object NetworkUpgradeTestApi {
    const val MODEL = "co.electriccoin.zcash.ui.common.model."
    const val MESSAGE =
        "Nothing was sent or paid: this version of ZChat can't send on the upgraded Zcash network. " +
            "Update ZChat to send. Your balance is safe."

    private fun typeOrNull(): Class<*>? =
        try {
            Class.forName(MODEL + "NetworkUpgradeGuard")
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun type(): Class<*> =
        assertNotNull(typeOrNull(), "ZC-538: NetworkUpgradeGuard is absent; incompatible sends remain enabled")

    fun resetIfPresent() {
        val type = typeOrNull() ?: return
        val reset =
            assertNotNull(
                type.declaredMethods.firstOrNull { it.name.startsWith("resetForTest") && it.parameterCount == 0 },
                "guard needs its test reset",
            )
        reset.isAccessible = true
        reset.invoke(type.getField("INSTANCE").get(null))
    }

    private fun call(name: String, vararg args: Any?): Any? {
        val type = type()
        val method =
            assertNotNull(
                type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size },
                "NetworkUpgradeGuard.$name is absent",
            )
        method.isAccessible = true
        return try {
            method.invoke(type.getField("INSTANCE").get(null), *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    fun setup(error: Throwable?) = call("onSetupError", error)

    fun submit(result: SubmitResult) = call("onSubmitResult", result)

    fun started() = call("onSynchronizerStarted")

    fun state(): StateFlow<*> = call("getState") as StateFlow<*>

    fun notice(): Any? = state().value

    fun refusal(): Throwable? = call("refusal") as Throwable?

    fun mismatch() = CompactBlockProcessorException.MismatchedConsensusBranch("37a5165b", "77190ad9")

    fun latch() {
        setup(mismatch())
        assertNotNull(notice(), "SDK mismatch must latch before driving a spend")
    }

    fun assertRefusal(error: Throwable?) {
        assertNotNull(error, "incompatible spend was not refused")
        assertTrue(
            generateSequence(error) { it.cause }
                .take(20)
                .any { it.javaClass.name == MODEL + "NetworkUpgradeRequiredException" },
            "expected typed update refusal, got $error",
        )
    }

    fun exception(): Throwable {
        latch()
        return assertNotNull(refusal())
    }

    fun sdkError(name: String): Throwable {
        // These negative cases need the SDK's actual type, not guessed text. No JNI constructor runs.
        val type = Class.forName("cash.z.ecc.android.sdk.exception.CompactBlockProcessorException" + '$' + name)
        return allocate(type) as Throwable
    }

    fun allocate(type: Class<*>): Any {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return assertNotNull(unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }

    fun grpcUnavailable(): Throwable {
        // gRPC is an existing runtime dependency of the SDK stack, not a ui-lib compile dependency.
        val statusType = Class.forName("io.grpc.Status")
        val unavailable = statusType.getField("UNAVAILABLE").get(null)
        val withCause =
            statusType.getMethod("withCause", Throwable::class.java).invoke(unavailable, IOException("synthetic transport"))
        return statusType.getMethod("asRuntimeException").invoke(withCause) as Throwable
    }
}
