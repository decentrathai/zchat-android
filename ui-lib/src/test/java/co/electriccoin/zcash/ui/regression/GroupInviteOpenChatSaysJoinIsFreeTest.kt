package co.electriccoin.zcash.ui.regression

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ZC-1224: a member removed from a group whose creator has an accepted Open chat with them read "You haven't chatted
 * with this address before" and "Join ... can cost at least 0.00017 ZEC", while Join over the Open chat is free.
 * Wanted: a banner text for that case, group_invite_join_known_free (EN and ES), with no price, no %3$s and no
 * "haven't chatted"; GroupDetailView.kt uses it.
 */
class GroupInviteOpenChatSaysJoinIsFreeTest {
    private fun value(v: String): String? =
        Regex("""<string name="group_invite_join_known_free"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(PolSourceFiles.res("common/$v/group_invite_request_strings.xml").readText())
            ?.groupValues
            ?.get(1)

    @Test
    fun `English text exists and names neither a price nor a stranger`() {
        val en = value("values")
        assertTrue(en != null, "no string group_invite_join_known_free in group_invite_request_strings.xml")
        assertTrue("ZEC" !in en && "%3" !in en && "chatted" !in en, "text: $en")
        assertTrue(value("values-es") != null, "no Spanish group_invite_join_known_free")
    }

    @Test
    fun `the banner uses it`() {
        val src = PolSourceFiles.main("screen/chat/view/GroupDetailView.kt").readText()
        assertTrue("R.string.group_invite_join_known_free" in src, "GroupDetailView does not use group_invite_join_known_free")
    }
}
