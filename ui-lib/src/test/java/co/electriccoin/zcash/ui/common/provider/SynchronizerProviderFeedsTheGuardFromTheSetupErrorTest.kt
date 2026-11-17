package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeTestApi
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ZC-538: provider callbacks retain their old semantics while feeding a separate upgrade latch. */
@OptIn(ExperimentalCoroutinesApi::class)
class SynchronizerProviderFeedsTheGuardFromTheSetupErrorTest {
    @Before
    fun setUp() = NetworkUpgradeTestApi.resetIfPresent()

    @After
    fun tearDown() = NetworkUpgradeTestApi.resetIfPresent()

    private class Callbacks {
        val handlers = mutableMapOf<String, Any?>()
        val synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { proxy, method, args ->
                when {
                    method.name.startsWith("setOn") -> {
                        handlers[method.name.removePrefix("set")] = args?.get(0)
                        null
                    }
                    method.name.startsWith("getOn") -> handlers[method.name.removePrefix("get")]
                    method.name == "toString" -> "SyntheticCallbacks"
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.name == "equals" -> proxy === args?.get(0)
                    else -> throw AssertionError("unexpected synchronizer call ${method.name}")
                }
            } as Synchronizer

        @Suppress("UNCHECKED_CAST")
        fun pipeline(): Flow<SynchronizerError?> {
            // The callback registrar uses no coordinator or provider state: bypass constructor background work.
            val provider = NetworkUpgradeTestApi.allocate(SynchronizerProviderImpl::class.java)
            val method =
                SynchronizerProviderImpl::class.java.declaredMethods.single {
                    it.name.startsWith("initializeErrorHandling") &&
                        !Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes.contentEquals(arrayOf(Synchronizer::class.java))
                }
            method.isAccessible = true
            return method.invoke(provider, synchronizer) as Flow<SynchronizerError?>
        }

        @Suppress("UNCHECKED_CAST")
        fun handler(name: String): (Throwable?) -> Boolean =
            assertNotNull(handlers[name], "$name callback is not registered") as (Throwable?) -> Boolean

        @Suppress("UNCHECKED_CAST")
        fun resolved() = (assertNotNull(handlers["OnProcessorErrorResolved"]) as () -> Unit).invoke()
    }

    @Test
    fun control_setup_still_returns_false_and_publishes_setup_then_processor_resolved_clears_error() =
        runTest {
            val fixture = Callbacks()
            val values = mutableListOf<SynchronizerError?>()
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.pipeline().collect { values += it }
                }
            try {
                val error = IOException("ordinary setup error")
                assertFalse(fixture.handler("OnSetupErrorHandler")(error))
                val setup = values.last() as? SynchronizerError.Setup
                assertNotNull(setup, "existing Setup publication was lost")
                assertEquals(error, setup.cause)
                fixture.resolved()
                assertNull(values.last())
            } finally {
                collector.cancel()
                collector.join()
            }
        }

    @Test
    fun setup_mismatch_latches_without_changing_setup_return_or_publication_and_resolved_does_not_unlatch() =
        runTest {
            val fixture = Callbacks()
            val values = mutableListOf<SynchronizerError?>()
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.pipeline().collect { values += it }
                }
            try {
                val mismatch = NetworkUpgradeTestApi.mismatch()
                assertFalse(fixture.handler("OnSetupErrorHandler")(mismatch))
                assertTrue(values.last() is SynchronizerError.Setup)
                assertNotNull(NetworkUpgradeTestApi.notice(), "provider discarded real SDK mismatch")
                fixture.resolved()
                assertNull(values.last())
                assertNotNull(NetworkUpgradeTestApi.notice(), "processor resolution cleared the upgrade latch")
                fixture.handler("OnProcessorErrorHandler")(RuntimeException("processor", mismatch))
                assertTrue(values.last() is SynchronizerError.Processor)
                assertNotNull(NetworkUpgradeTestApi.notice())
            } finally {
                collector.cancel()
                collector.join()
            }
        }

    @Test
    fun critical_classifies_only_the_mismatch_returns_false_and_publishes_nothing() =
        runTest {
            val fixture = Callbacks()
            val values = mutableListOf<SynchronizerError?>()
            val collector =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.pipeline().collect { values += it }
                }
            try {
                val critical = fixture.handler("OnCriticalErrorHandler")
                assertFalse(critical(IOException("critical transport error")))
                assertNull(NetworkUpgradeTestApi.notice())
                assertFalse(critical(NetworkUpgradeTestApi.mismatch()))
                assertNotNull(NetworkUpgradeTestApi.notice())
                assertEquals(listOf<SynchronizerError?>(null), values, "Critical must not enter the published error pipeline")
            } finally {
                collector.cancel()
                collector.join()
            }
        }

    @Test
    fun processor_mismatch_is_not_a_trigger_and_new_synchronizers_clear_before_registering_callbacks() =
        runTest {
            val fixture = Callbacks()
            fixture.pipeline()
            fixture.handler("OnProcessorErrorHandler")(NetworkUpgradeTestApi.mismatch())
            assertNull(NetworkUpgradeTestApi.notice(), "Processor is not an SDK setup signal")
            val source = SourceScan.stripComments(ChatResourceText.source(PROVIDER))
            val start = SourceScan.braceBlock(source, "if (synchronizer != null)")
            assertTrue(
                start.indexOf("NetworkUpgradeGuard.onSynchronizerStarted()") in
                    0 until start.indexOf("initializeErrorHandling(synchronizer)")
            )
            val close = SourceScan.braceBlock(source, "awaitClose {")
            val callbackNames =
                listOf(
                    "onCriticalErrorHandler",
                    "onSetupErrorHandler",
                    "onProcessorErrorHandler",
                    "onProcessorErrorResolved",
                    "onChainErrorHandler",
                )
            for (name in callbackNames) {
                assertTrue(Regex("""synchronizer\?\.$name\s*=\s*null""").containsMatchIn(close), "awaitClose did not clear $name")
            }
            assertTrue(Regex("""internal fun initializeErrorHandling""").containsMatchIn(source))
        }

    private companion object {
        const val PROVIDER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/common/provider/SynchronizerProvider.kt"
    }
}
