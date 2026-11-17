package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC review R2-3; t5b SPECS §2 C-M2-2): the send gate asks the plan check the
 * ChatViewModel is built with. A default value ("Fundable") would switch the gate off silently if the Koin wiring ever
 * missed it, so:
 * - the constructor parameter `firstMessagePlanCheck: FirstMessagePlanCheck` has no default value and comes after
 *   SY-2's `walletSyncClock` (appended last);
 * - Koin builds ChatViewModel with `viewModelOf(::ChatViewModel)` and binds FirstMessagePlanCheck to the real check,
 *   ProbeFirstMessagePlanUseCase, and to nothing else;
 * - ProbeFirstMessagePlanUseCase is a FirstMessagePlanCheck and asks CreateChunkedMessageProposalUseCase's probe.
 * At HEAD none of this exists.
 */
class ChatViewModelWiringUsesRealPlanCheckTest {
    private val vmPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
    private val diDir = "ui-lib/src/main/java/co/electriccoin/zcash/di"

    @Test
    fun `the constructor takes the plan check last, with no default value`() {
        val source = ChatResourceText.source(vmPath)
        val start = source.indexOf("class ChatViewModel(")
        val end = source.indexOf(") : ViewModel()", start)
        assertTrue(start >= 0 && end > start, "ChatViewModel's constructor was not found")
        val params = source.substring(start, end)
        val param =
            assertNotNull(
                Regex("""private val firstMessagePlanCheck\s*:\s*([\w.]*FirstMessagePlanCheck)([^,\n]*)""").find(params),
                "ChatViewModel has no firstMessagePlanCheck: FirstMessagePlanCheck constructor parameter",
            )
        assertTrue('=' !in param.groupValues[2], "firstMessagePlanCheck has a default value: ${param.value}")
        assertTrue(
            params.indexOf("walletSyncClock") in 0 until param.range.first,
            "firstMessagePlanCheck is not appended after walletSyncClock",
        )
        val after = params.substring(param.range.last + 1)
        assertTrue(Regex("""private val \w+\s*:""").find(after) == null, "a parameter follows firstMessagePlanCheck")
    }

    @Test
    fun `Koin wires the real plan check and nothing else`() {
        val modules =
            File(ChatResourceText.repoRoot, diDir)
                .listFiles { f -> f.name.endsWith(".kt") }
                .orEmpty()
                .associate { it.name to it.readText() }
        assertTrue("viewModelOf(::ChatViewModel)" in modules["ViewModelModule.kt"].orEmpty(), "ChatViewModel is not built by viewModelOf")
        val binding = Regex("""(factoryOf|singleOf)\(\s*::ProbeFirstMessagePlanUseCase\s*\)\s*bind\s+FirstMessagePlanCheck::class""")
        val bound = modules.filterValues { binding.containsMatchIn(it) }
        assertEquals(listOf("UseCaseModule.kt"), bound.keys.toList(), "FirstMessagePlanCheck is not bound to ProbeFirstMessagePlanUseCase")
        val mentions = modules.values.sumOf { Regex("""\bFirstMessagePlanCheck\b""").findAll(it).count() }
        assertEquals(2, mentions, "FirstMessagePlanCheck is bound or built somewhere else too (import + binding expected)")
    }

    @Test
    fun `the real check is a FirstMessagePlanCheck that asks the use case's probe`() {
        val iface = FirstMessagePlanApi.cls(FirstMessagePlanApi.CHECK)
        val real = FirstMessagePlanApi.cls(FirstMessagePlanApi.USE_CASE)
        assertTrue(iface.isAssignableFrom(real), "ProbeFirstMessagePlanUseCase is not a FirstMessagePlanCheck")
        val source =
            ChatResourceText.source(
                "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/usecase/ProbeFirstMessagePlanUseCase.kt",
            )
        assertTrue("probeFirstMessagePlan(" in source, "the real check does not ask CreateChunkedMessageProposalUseCase")
    }
}
