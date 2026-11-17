package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.CreateGroupState
import co.electriccoin.zcash.ui.screen.chat.model.groupMessageCostFloor
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 10b R10B-3 (F-R9C-6, two-phone checks 2026-09-25, logs/140 and es-127) — New Group with nobody chosen.
 *
 * With no member selected the button read «Create Group (1 member)» (CreateGroupState.memberCount counts the creator)
 * and the cost note quoted «at least 0.00017 ZEC for 1 member» (the note's count was inviteeCount.coerceAtLeast(1)),
 * a cost for a member nobody chose.
 *
 * Wanted: with nobody chosen the button reads group_create_button_none («Create Group», no count; it stays disabled)
 * and the note is group_create_pick_members, with no figure. With one or more chosen nothing changes: one chosen reads
 * «Create Group (2 members)» and quotes 17,000 zatoshi for 1 member.
 */
class NewGroupWithNobodyChosenTest {
    private val member = "usyntheticnewgroupmember000001"

    private fun property(
        state: CreateGroupState,
        name: String,
    ): Int? {
        val getter =
            try {
                CreateGroupState::class.java.getMethod("get" + name.replaceFirstChar { it.uppercase() })
            } catch (e: NoSuchMethodException) {
                throw AssertionError(
                    "CreateGroupState.$name is missing: with nobody chosen the button still names a size and the note " +
                        "still quotes a cost for one member",
                    e,
                )
            }
        return try {
            getter.invoke(state) as Int?
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private val view: String by lazy {
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/CreateGroupView.kt")
    }

    @Test
    fun with_nobody_chosen_the_button_names_no_size_and_the_note_quotes_no_cost() {
        val empty = CreateGroupState(groupName = "Synthetic group")
        assertFalse(empty.isValid, "the button must stay disabled with nobody chosen")
        assertNull(property(empty, "buttonGroupSize"), "the button names a group size with nobody chosen")
        assertNull(property(empty, "costNoteMembers"), "the cost note quotes members with nobody chosen")
    }

    @Test
    fun with_one_chosen_the_button_and_the_note_are_as_before() {
        val one = CreateGroupState(groupName = "Synthetic group", selectedMembers = listOf(member))
        assertTrue(one.isValid)
        assertEquals(2, property(one, "buttonGroupSize"), "one chosen: the group has 2 members, the creator included")
        assertEquals(1, property(one, "costNoteMembers"), "one chosen: the note quotes 1 member, never the creator")
        assertEquals(17_000L, groupMessageCostFloor(1).value)
        assertEquals("Create Group (2 members)", ChatResourceText.englishPlural(R.plurals.group_create_button, 2, listOf(2)))

        val three = CreateGroupState(groupName = "Synthetic group", selectedMembers = listOf(member, member + "b", member + "c"))
        assertEquals(4, property(three, "buttonGroupSize"))
        assertEquals(3, property(three, "costNoteMembers"))
    }

    @Test
    fun the_words_for_nobody_chosen_are_resources_in_english_and_spanish() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals("Create Group", en["group_create_button_none"])
        assertEquals("Crear grupo", es["group_create_button_none"])
        assertEquals(
            "Choose at least one member. Each message is then one on-chain transaction per member, free for members " +
                "reachable over the relays.",
            en["group_create_pick_members"],
        )
        assertEquals(
            "Elige al menos un miembro. Después, cada mensaje es una transacción en la cadena por miembro, gratis para " +
                "los miembros a los que se llega por los relays.",
            es["group_create_pick_members"],
        )
    }

    @Test
    fun the_screen_shows_the_nobody_chosen_words_and_never_forces_one_member() {
        if ("R.string.group_create_button_none" !in view) fail("CreateGroupView never shows group_create_button_none")
        if ("R.string.group_create_pick_members" !in view) fail("CreateGroupView never shows group_create_pick_members")
        assertFalse(
            "inviteeCount.coerceAtLeast(1)" in view,
            "the cost note still quotes one member when nobody is chosen",
        )
        assertTrue("state.buttonGroupSize" in view, "the button does not take its size from CreateGroupState.buttonGroupSize")
        assertTrue(
            "val costMembers = state.costNoteMembers" in view,
            "the cost note does not take its count from CreateGroupState.costNoteMembers",
        )
    }
}
