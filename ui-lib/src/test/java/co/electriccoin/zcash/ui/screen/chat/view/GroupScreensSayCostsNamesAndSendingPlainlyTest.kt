package co.electriccoin.zcash.ui.screen.chat.view

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageCost
import co.electriccoin.zcash.ui.screen.chat.model.PeerName
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA2 batch B4 (owner W-136 / W-137, 10 Nov 2026) — what the group screens say and how large they say it.
 *
 * - Q2-58 (ZC-1648): New Group's cost note for one invitee says "per member … for 1 member", next to "Create Group (2
 *   members)". Wanted: a one-member note that names "the other member" and one figure, and an other-form that names
 *   "the N others"; figures stay onChainSendMinimumCost() / groupMessageCostFloor() (TrueCostFiguresTest).
 * - Q2-59 (ZC-1597): the note is 13.sp under the Create button at the bottom edge. Wanted: above the button, 14.sp,
 *   textSecondary at full alpha.
 * - Q2-60 (ZC-1649): the picker shows the short address twice and avatar "U1" when the stored contact name IS the
 *   address (or its short form); Add Member cuts 10…6 while every other screen uses PeerName.shortAddress (8...6).
 *   Wanted: PeerName.realName(name, address) treats such a name as blank (hash initials, no second line), and both
 *   pickers use it and PeerName.shortAddress.
 * - Q2-61 (ZC-1650): during a group send nothing says "sending". Wanted: group_composer_sending in EN and ES, shown by
 *   GroupDetailView.
 * - Q2-62 (ZC-1651): a member's phone shows the uncounted "Free right now for the members this phone knows…". Wanted:
 *   the counted line group_message_cost_free_known_count ("…for the N members this phone has heard from…").
 * - Q2-63 (ZC-898): Add Member offers "Done" with nothing chosen and says who can be added only when nobody can.
 *   Wanted: a "Close" button and a permanent hint.
 * - Q2-64 (ZC-029, ZC-1023): the remove dialog says nothing about what is sent or what it costs. Wanted: the new key
 *   goes to the others, free over the relays, the price shown first when a member is on-chain only (kickMember asks
 *   PaidAction.GROUP_REMOVE_MEMBER before rotateAndNotify, so the sentence is true).
 * - Q2-68 / Q2-65 size part (ZC-1597, ZC-1652): group bubbles' sender and notes 11.sp, time and status glyph 10.sp at
 *   alpha 0.8, cost lines and the 2.12.4 line 11.sp. Wanted: 12.sp, full alpha.
 *
 * Spanish lines are drafts for the native review (app-texts rule 9). The new seam PeerName.realName is reached by
 * reflection so this file compiles before the fix. Addresses are synthetic, built at run time.
 */
class GroupScreensSayCostsNamesAndSendingPlainlyTest {
    private val chatRoot = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"

    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }
    private val enPlurals by lazy { ChatResourceText.plurals("values") }
    private val esPlurals by lazy { ChatResourceText.plurals("values-es") }

    // ── Q2-58 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Q2-58 the create-group note speaks of the other member, or of the N others`() {
        val enNote = assertNotNull(enPlurals["create_group_cost_note"], "no English create_group_cost_note")
        assertEquals(
            "Each message to the other member costs at least %2\$s ZEC if it is sent as an on-chain transaction. " +
                "It is free when it can be sent over the relays.",
            enNote["one"],
        )
        assertEquals(
            "Each message needs one on-chain transaction for every other member the relays cannot reach: at " +
                "least %2\$s ZEC per transaction. If all %1\$d other members need that route, the total is at " +
                "least %3\$s ZEC. Sending over the relays is free.",
            enNote["other"],
        )
        val esNote = assertNotNull(esPlurals["create_group_cost_note"], "no Spanish create_group_cost_note")
        assertEquals(
            "Cada mensaje al otro miembro cuesta al menos %2\$s ZEC si se envía en una transacción en la cadena. " +
                "Es gratis si se puede enviar por los relays.",
            esNote["one"],
        )
        val esOther =
            "Cada mensaje requiere una transacción en la cadena por cada uno de los demás miembros a los que no se " +
                "pueda llegar por los relays: al menos %2\$s ZEC por transacción. Si los otros %1\$d miembros " +
                "necesitan esa vía, el total es de al menos %3\$s ZEC. El envío por los relays es gratis."
        assertEquals(esOther, esNote["other"])
        esNote["many"]?.let { assertEquals(esOther, it, "es/many must match es/other") }
    }

    // ── Q2-59 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Q2-59 the create-group note stands above the button at 14sp, full alpha`() {
        val view = ChatResourceText.source("$chatRoot/view/CreateGroupView.kt")
        val note = view.indexOf("R.plurals.create_group_cost_note")
        val button = view.indexOf("onClick = onCreateGroup")
        assertTrue(note >= 0 && button >= 0, "anchors not found in CreateGroupView")
        assertTrue(note < button, "the cost note is still drawn under the Create button")
        val noteStyle = view.substring(note, view.indexOf("modifier", note).takeIf { it > note } ?: view.length)
        assertTrue("fontSize = 14.sp" in noteStyle, "the cost note is not 14.sp: $noteStyle")
        assertTrue("colors.textSecondary" in noteStyle, "the cost note is not textSecondary: $noteStyle")
        assertFalse("alpha" in noteStyle, "the cost note is not at full alpha: $noteStyle")
    }

    // ── Q2-60 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Q2-60 a stored name that is the address or its short form is no name`() {
        val address = syntheticAddress('a')
        assertEquals("Ana", realName("Ana", address))
        assertEquals("Ana", realName("  Ana ", address))
        assertNull(realName("", address))
        assertNull(realName("   ", address))
        assertNull(realName(address, address), "the full address was taken for a name")
        assertNull(realName(PeerName.shortAddress(address), address), "the 8...6 short address was taken for a name")
        assertNull(realName("${address.take(10)}…${address.takeLast(6)}", address), "the 10…6 short address was taken")
        assertNull(realName("${address.take(8)}…${address.takeLast(6)}", address), "the 8…6 short address was taken")
        assertNull(realName(address.uppercase(), address), "the address in capitals was taken for a name")
        // The avatar then falls back to the per-address hash initials, never "U1".
        val initials = avatarInitials(address, realName(PeerName.shortAddress(address), address))
        assertEquals(avatarInitials(address, null), initials)
        assertNotEquals("U1", initials)
    }

    @Test
    fun `Q2-60 the New Group picker and Add Member use realName and the 8 dots 6 short address`() {
        val create = ChatResourceText.source("$chatRoot/view/CreateGroupView.kt")
        val item = functionBody(create, "ContactSelectionItem")
        assertTrue("PeerName.realName(" in item, "the New Group picker does not use PeerName.realName")
        assertFalse("displayName = contact.name," in item, "the picker's avatar still gets the raw stored name")

        val settings = ChatResourceText.source("$chatRoot/view/GroupSettingsView.kt")
        val dialog = addMemberDialog(settings)
        assertFalse("take(10)" in dialog, "Add Member still cuts the address 10…6")
        assertTrue("PeerName.shortAddress(" in dialog, "Add Member does not use PeerName.shortAddress")
        assertTrue("PeerName.realName(" in dialog, "Add Member does not use PeerName.realName")
    }

    // ── Q2-61 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Q2-61 a group send says it is sending and that the text stays`() {
        assertEquals("Sending… your message stays here until it is sent.", en["group_composer_sending"])
        assertEquals("Enviando… tu mensaje se queda aquí hasta que se envíe.", es["group_composer_sending"])
        val view = ChatResourceText.source("$chatRoot/view/GroupDetailView.kt")
        assertTrue("R.string.group_composer_sending" in view, "GroupDetailView never says it is sending")
    }

    // ── Q2-62 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Q2-62 on a member's phone the free line counts the members this phone has heard from`() {
        val two = costLine(GroupMessageCost(recipients = 2, onChainMessages = 0, totalCost = Zatoshi(0), knownOnly = true))
        assertEquals(
            "Free right now for the 2 members this phone has heard from. " +
                "Members it hasn't heard from yet won't get this message.",
            two,
        )
        val one = costLine(GroupMessageCost(recipients = 1, onChainMessages = 0, totalCost = Zatoshi(0), knownOnly = true))
        assertEquals(
            "Free right now for the 1 member this phone has heard from. " +
                "Members it hasn't heard from yet won't get this message.",
            one,
        )
        en["group_message_cost_free_known"]?.let { assertNotEquals(it, two, "still the uncounted free line") }

        val esCount = assertNotNull(esPlurals["group_message_cost_free_known_count"], "no Spanish counted free line")
        val esOther =
            "Gratis ahora para los %1\$d miembros de los que este teléfono ha recibido algo. " +
                "Los miembros de los que aún no ha recibido nada no recibirán este mensaje."
        assertEquals(esOther, esCount["other"])
        esCount["many"]?.let { assertEquals(esOther, it, "es/many must match es/other") }
        val esOne = assertNotNull(esCount["one"], "no Spanish one-form")
        assertTrue("%1\$d" in esOne && "recibido algo" in esOne && "no recibirán este mensaje" in esOne, esOne)
    }

    // ── Q2-63 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Q2-63 Add Member closes with Close and always says who can be added`() {
        assertEquals("Close", en["group_add_member_close"])
        assertEquals("Cerrar", es["group_add_member_close"])
        assertEquals(
            "People you've chatted with or saved as contacts who aren't in this group yet.",
            en["group_add_member_hint"],
        )
        assertEquals(
            "Personas con las que has chateado o que guardaste como contactos, que todavía no están en este grupo.",
            es["group_add_member_hint"],
        )
        val dialog = addMemberDialog(ChatResourceText.source("$chatRoot/view/GroupSettingsView.kt"))
        assertTrue("R.string.group_add_member_close" in dialog, "Add Member's button is not Close")
        assertFalse("R.string.group_done" in dialog, "Add Member still offers Done")
        assertTrue("R.string.group_add_member_hint" in dialog, "Add Member shows no permanent hint")
    }

    // ── Q2-64 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Q2-64 the remove dialog says what is sent and when it costs`() {
        assertEquals(
            "Remove %1\$s from the group? The other members get a new group key so %1\$s can't read new messages — " +
                "free over the relays; ZChat shows the price first if a member can only be reached on-chain.",
            en["group_remove_member_body"],
        )
        assertEquals(
            "¿Quitar a %1\$s del grupo? Los demás miembros recibirán una nueva clave de grupo para que %1\$s ya no " +
                "pueda leer los mensajes nuevos. Enviar la clave por los relays es gratis; si solo se puede enviar a " +
                "algún miembro por la cadena, ZChat te mostrará el precio antes.",
            es["group_remove_member_body"],
        )
    }

    @Test
    fun `Q2-64 the price is asked before the key rotation is sent`() {
        val vm = ChatResourceText.source("$chatRoot/viewmodel/GroupViewModel.kt")
        val quoted = vm.indexOf("fun kickMemberQuoted(")
        assertTrue(quoted >= 0, "kickMemberQuoted not found")
        val ask = vm.indexOf("askBeforePaying(PaidAction.GROUP_REMOVE_MEMBER", quoted)
        val rotate = vm.indexOf("rotateAndNotify(", quoted)
        assertTrue(ask > 0 && rotate > 0, "anchors not found in kickMemberQuoted")
        assertTrue(ask < rotate, "the rotation is sent before the price is asked")
    }

    // ── Q2-68 (+ Q2-65 size) ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Q2-68 group bubbles use 12sp at full alpha for sender, notes, time and status`() {
        val bubble = functionBody(ChatResourceText.source("$chatRoot/view/GroupDetailView.kt"), "GroupMessageBubble")
        assertFalse("fontSize = 10.sp" in bubble, "the bubble's time or status glyph is still 10.sp")
        assertFalse("fontSize = 11.sp" in bubble, "the bubble's sender or note line is still 11.sp")
        assertFalse("alpha = 0.8f" in bubble, "the bubble's time or status is still at alpha 0.8")
    }

    @Test
    fun `Q2-68 the group cost lines and the 2_12_4 line are 12sp`() {
        val view = ChatResourceText.source("$chatRoot/view/GroupDetailView.kt")
        for (anchor in listOf("R.string.upgrade_group_line", "groupMessageCostText(", "R.plurals.group_members_cannot_read")) {
            val at = view.indexOf(anchor)
            assertTrue(at >= 0, "$anchor not found in GroupDetailView")
            val size = Regex("""fontSize = (\d+)\.sp""").find(view, at)?.groupValues?.get(1)
            assertEquals("12", size, "the line at $anchor is $size.sp, wanted 12.sp")
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────────────────────────────

    private fun costLine(cost: GroupMessageCost): String =
        groupMessageCostText(
            cost = cost,
            string = { ChatResourceText.english(it) },
            plural = { id, quantity, args -> ChatResourceText.englishPlural(id, quantity, args) },
            locale = Locale.US,
        )

    /** PeerName.realName(name, address), the Q2-60 seam, by reflection so this file compiles before it exists. */
    private fun realName(
        name: String?,
        address: String,
    ): String? {
        val method =
            PeerName::class.java.methods.firstOrNull { it.name == "realName" && it.parameterCount == 2 }
                ?: throw AssertionError("PeerName.realName(name, address) does not exist")
        return method.invoke(PeerName, name, address) as String?
    }

    private fun syntheticAddress(seed: Char): String =
        "u1" + (0 until 100).joinToString("") { ('a' + (seed - 'a' + it) % 26).toString() }

    /** The Add Member dialog of GroupSettingsView: from its title to the end of its confirm button. */
    private fun addMemberDialog(settings: String): String {
        val start = settings.indexOf("if (showAddMemberDialog)")
        assertTrue(start >= 0, "Add Member dialog not found")
        val end = settings.indexOf("\n    }\n", start)
        return settings.substring(start, if (end > start) end else settings.length)
    }

    /** The text of the function [name] in [source]: from its declaration to the next `fun`. */
    private fun functionBody(
        source: String,
        name: String,
    ): String {
        val start = source.indexOf("fun $name(")
        assertTrue(start >= 0, "fun $name not found")
        val next = Regex("""\n\s*(private |internal |public )?(suspend )?fun """).find(source, start + 1)
        return source.substring(start, next?.range?.first ?: source.length)
    }
}
