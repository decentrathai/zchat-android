package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.A
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.C
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MA3.NPUB
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.NOW_MS
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.own
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.ownInvite
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RebuiltGroupFixture.ownKeyChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-757 follow-up (register, medium: "a rebuilt group cannot send in practice") — in a group this phone rebuilt, after
 * its new key reached the members, a message goes only to members this phone has seen write since the rebuild (it
 * cannot know who still reads the group). Until one has, a send goes nowhere.
 *
 * At e613f712d the refusal is the ordinary "Not sent: A hasn't joined this group yet", which is false there: A joined
 * long ago, this phone only lost the record. Wanted: the refusal in a rebuilt group says plainly why the message goes
 * nowhere and what changes it (a member counts again once they write here), nothing was spent and the text is kept;
 * GroupSendResult.NoActiveRecipients carries `rebuilt` for it, and the banner shows `group_send_rebuilt_nobody_wrote`
 * (EN + ES, no placeholder; the Spanish an agent draft). A group that was not rebuilt keeps today's sentence.
 *
 * All addresses, keys, ids and times are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuiltGroupSendSaysWhyItGoesNowhereTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val net = RelayNet()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        net.register()
        RebuiltGroupFixture.setClock(NOW_MS, NOW_MS - 3_600_000L)
    }

    @After
    fun tearDown() {
        RebuiltGroupFixture.setClock(null, 0L)
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `a send in a rebuilt group nobody wrote in since its new key says why it goes nowhere, not that A has not joined`() =
        runBlocking {
            val admin = AnnouncerPhone(C)
            RebuiltGroupFixture.member(A, admin, epoch = 3)
            val vm = MA3.chatVm(admin)
            own(vm, ownInvite(admin, A), tx = 1, to = A, height = 100)
            own(vm, ownKeyChange(admin, A, 3), tx = 2, to = A, height = 110)
            val f = GroupPaidOnceFixture().apply { scheduler = this@RebuiltGroupSendSaysWhyItGoesNowhereTest.scheduler }
            val gvm = MA3.groupVm(admin, f, AnnounceSeam())
            RebuiltGroupFixture.rotate(gvm, f)
            assertEquals("fixture: the new key did not reach A", 1, RebuiltGroupFixture.keyChangesTo(net, NPUB.getValue(A)).size)

            val result = RebuiltGroupFixture.sendResult(gvm, f)
            assertTrue("fixture: the send did not end with no recipients: $result", result is GroupSendResult.NoActiveRecipients)
            val rebuilt = runCatching { result!!.javaClass.getMethod("getRebuilt").invoke(result) as Boolean }.getOrDefault(false)
            assertTrue("the refusal in a rebuilt group is the ordinary 'has not joined yet'", rebuilt)
            assertEquals("a message went out", 0, net.ofType(GroupMessageType.GROUP_MSG).size)
            assertEquals("something was proposed for payment", 0, f.seam.proposalRequests.get())
        }

    @Test
    fun `the sentence exists in English and Spanish, says why and what changes it, and the banner shows it`() {
        val en = ChatResourceText.strings("values")[KEY]
        val es = ChatResourceText.strings("values-es")[KEY]
        assertNotNull("no English $KEY", en)
        assertNotNull("no Spanish $KEY", es)
        for (text in listOf(en!!, es!!)) assertFalse("$KEY has a placeholder", Regex("""%(\d+\$)?[sd]""").containsMatchIn(text))
        for (required in listOf("Not sent", "rebuilt", "write here", "Nothing was spent")) {
            assertTrue("$KEY omits '$required'", required in en)
        }
        for (forbidden in listOf("hasn't joined", "haven't joined", "restored", "automatically")) {
            assertFalse("$KEY says '$forbidden'", forbidden in en)
        }
        val banner = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupSendBanner.kt")
        assertTrue("the banner does not show the rebuilt group's sentence", "R.string.$KEY" in banner)
    }

    private companion object {
        const val KEY = "group_send_rebuilt_nobody_wrote"
    }
}
