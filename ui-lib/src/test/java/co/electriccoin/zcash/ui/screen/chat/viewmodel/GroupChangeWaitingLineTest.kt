package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.M1
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.M2
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.admin
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.member
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupChangeRaceFixture.Companion.waitingGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KI-22 — once a second change of a group waits for the first (one change lock per group for the whole app), the
 * screen whose change waits says so: "Waiting for the other change to this group to finish", in English and Spanish.
 * The screen whose own change is running does not show it, and the line goes away when the wait ends.
 *
 * The waiting state (`groupsWaitingForChange`) is reached by name, so this file compiles before it exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupChangeWaitingLineTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        vms.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `the screen whose change waits for another change of the group shows the waiting line until it runs`() =
        runBlocking {
            val f = GroupChangeRaceFixture("zgrp_ki22_waiting", listOf(admin(), member(M1), member(M2)), setOf(M1, M2))
            val settings = f.groupViewModel().also { vms += it }
            val groupScreen = f.groupViewModel().also { vms += it }

            val kick = f.startRotation(settings, M1)
            f.awaitFirstSubmit()
            val rotation = f.startRotation(groupScreen, null)

            assertTrue(
                "the group screen's rotation must wait for the settings screen's kick and say so",
                f.waitUntil(timeoutMs = 3_000) { f.groupId in waitingGroups(groupScreen) },
            )
            assertEquals("the screen whose change is running is not waiting", emptySet<String>(), waitingGroups(settings))

            f.release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) {
                kick.join()
                rotation.join()
            }
            assertEquals("the line goes away when the wait ends", emptySet<String>(), waitingGroups(groupScreen))
        }

    @Test
    fun `the waiting line has its English and Spanish text and both group screens show it`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val key = "group_waiting_for_other_change"
        assertEquals("Waiting for the other change to this group to finish…", en[key])
        assertTrue("the Spanish text is missing", !es[key].isNullOrBlank())
        assertNotEquals("the Spanish text is the English one", en[key], es[key])

        val chat = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"
        for (view in listOf("$chat/view/GroupDetailView.kt", "$chat/view/GroupSettingsView.kt")) {
            assertTrue("$view does not show the waiting line", ChatResourceText.source(view).contains("R.string.$key"))
        }
        assertTrue(
            "AndroidChat does not hand the waiting state to the group screens",
            ChatResourceText.source("$chat/AndroidChat.kt").contains("groupsWaitingForChange"),
        )
    }
}
