package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.GroupDetailState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * P-G1 r1, phone check of train T6b (Fold creator, a fresh wallet with no transactions, 15 Oct 15:01-15:02) — after the
 * group screen was left and opened again, the creator's own message was drawn as an incoming bubble under its own
 * address, and stayed so (two screen reads 10 s apart).
 *
 * A group screen is a new GroupViewModel, whose own address is read in the background at its start
 * (loadCurrentUserAddress). The screen state carries the address it had when it was built (`_currentUserAddress.value ?:
 * ""`), and the screen draws a message as ours only when its sender equals that text. On a wallet whose history is read
 * at once (no transactions) both screen states are built before the address arrives, and nothing builds the state again
 * when it does: our own message stays on the left.
 *
 * Wanted: once our address is known, the shown group carries it, so our own message is drawn as ours.
 *
 * Every address, key and group id is synthetic. Nothing pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupReopenShowsOwnMessageAsOwnTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private val net = RelayNet()

    @Suppress("UNCHECKED_CAST")
    private fun address(s: GroupScreen): MutableStateFlow<String?> =
        GroupViewModel::class.java
            .getDeclaredField("_currentUserAddress")
            .apply { isAccessible = true }
            .get(s.vm) as MutableStateFlow<String?>

    @Test
    fun `a reopened group screen whose address arrives after the group was loaded draws our message as ours`() =
        runBlocking {
            val world = GroupLedgerWorld.twoActive(scheduler)
            listOf(MA3.A, MA3.B).forEach { world.admin.base.nostrKeys[it] = MA3.NPUB.getValue(it) }
            net.register()
            val s = world.screen()
            world.sendMessage(s, "own group text")

            // The screen is opened again: a new ViewModel, whose address is not read yet when the group is loaded.
            val s2 = world.screen()
            address(s2).value = null
            world.open(s2)
            // The address arrives.
            address(s2).value = world.admin.self

            val state = s2.vm.groupDetailState.value as GroupDetailState.Success
            val own = state.conversation.messages.single { it.decryptedContent == "own group text" }
            assertEquals("fixture sanity: the stored message is ours", world.admin.self, own.senderAddress)
            assertEquals(
                "our address arrived after the reopened group was loaded, and the shown group still has none: our own " +
                    "message is drawn as an incoming one",
                own.senderAddress,
                state.currentUserAddress,
            )
        }
}
