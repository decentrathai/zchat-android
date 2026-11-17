package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RED regression pin for the reply-linkage embedding in ChatViewModel (ChatViewModel.kt ~line 2350,
 * `tagReply`). The quoted-message id (and a short preview) MUST be embedded INSIDE the reply body
 * BEFORE encryption:
 *
 *     tagReply: return "$replyRefPrefix$replyToId$previewSeg$replyRefSentinel$body"
 *
 * so the reply threading rides the E2E / on-chain envelope and a NOSTR receiver (whose local ids
 * differ) can still resolve the quote. `untagReply` is the inverse: it strips the marker back to
 * (quotedId?, quotedPreview?, cleanBody).
 *
 * Both functions are `private` on ChatViewModel and read only the compile-time sentinel fields
 * (`replyRefSentinel` = U+0001, `replyRefPrefix` = "<U+0001>RPL:", `replyPreviewSep` = U+001F).
 * Constructing a real ChatViewModel is heavyweight (its init launches inbound/exchange-rate
 * collectors), and these functions are PURE string transforms, so this test uses a "thin"
 * reflectively-allocated instance: sun.misc.Unsafe.allocateInstance (reached reflectively, no
 * sun.* compile-time reference) skips the constructor, the three sentinel fields are set to their
 * production constants, and the two private methods are invoked via java.lang.reflect.
 *
 * MUTATION that reddens this test: revert `tagReply` to return `body` unchanged (drop the reply-ref
 * embedding). The quoted id then never reaches the wire, `untagReply` sees a plain body and returns
 * quotedId == null, and the load-bearing round-trip assertion goes RED.
 */
class RetestGap04ReplyLinkageRoundTripTest {

    // Production sentinel constants (ChatViewModel.kt ~lines 362-364). tagReply/untagReply are
    // internally consistent w.r.t. these fields, so the round-trip holds for the values we set.
    private val replyRefSentinel = '\u0001'
    private val replyRefPrefix = "\u0001RPL:"
    private val replyPreviewSep = '\u001f'

    private lateinit var vm: Any
    private lateinit var tagReplyM: java.lang.reflect.Method
    private lateinit var untagReplyM: java.lang.reflect.Method

    @Before
    fun setUp() {
        // Allocate a ChatViewModel WITHOUT running its constructor (no init-launched coroutines).
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        vm = allocate.invoke(theUnsafe, ChatViewModel::class.java)!!

        // Populate the three sentinel fields the pure functions read (all else stays default/null).
        setField("replyRefSentinel", replyRefSentinel)
        setField("replyRefPrefix", replyRefPrefix)
        setField("replyPreviewSep", replyPreviewSep)

        tagReplyM =
            ChatViewModel::class.java
                .getDeclaredMethod("tagReply", String::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
        untagReplyM =
            ChatViewModel::class.java
                .getDeclaredMethod("untagReply", String::class.java)
                .apply { isAccessible = true }
    }

    private fun setField(name: String, value: Any) {
        ChatViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun tag(replyToId: String, body: String, preview: String): String =
        tagReplyM.invoke(vm, replyToId, body, preview) as String

    private fun untag(content: String): Triple<*, *, *> =
        untagReplyM.invoke(vm, content) as Triple<*, *, *>

    // ----------------------------------------------------------------- tests

    @Test
    fun `LOAD-BEARING - the quoted id survives the tagReply-untagReply round trip`() {
        // This is the assertion that fails the instant the reply-ref embedding (line 2350) is
        // reverted to `return body`: the id never rides the body, so untagReply yields first == null.
        assertEquals("txabc123", untag(tag("txabc123", "hello", "p")).first)
    }

    @Test
    fun `full round trip preserves id, preview and clean body`() {
        val result = untag(tag("txabc123", "hello world", "quoted preview"))
        assertEquals("quotedId must round-trip", "txabc123", result.first)
        assertEquals("preview must round-trip", "quoted preview", result.second)
        assertEquals("body must have the marker stripped", "hello world", result.third)
    }

    @Test
    fun `the quoted id is actually on the wire - present in the tagged body`() {
        // If tagReply regressed to returning the body unchanged, the id would be absent here.
        assertTrue("quoted id must be embedded in the tagged body", tag("txabc123", "hi", "").contains("txabc123"))
    }

    @Test
    fun `preview with escape-sensitive chars survives escape-unescape`() {
        // Preview containing the separator (U+001F) and a backslash must escape into the marker and
        // unescape back out unchanged (proves the escape/unescape leg, not just the id).
        val tricky = "quote\u001fwith\\stuff"
        val result = untag(tag("nostr-abc", "body", tricky))
        assertEquals("nostr-abc", result.first)
        assertEquals(tricky, result.second)
        assertEquals("body", result.third)
    }

    @Test
    fun `legacy id-only marker untags to id, null preview, clean body`() {
        // Backward-compat: the pre-preview marker <U+0001>RPL:<id><U+0001><body>.
        val legacy = "${replyRefPrefix}txabc123${replyRefSentinel}body"
        val result = untag(legacy)
        assertEquals("txabc123", result.first)
        assertNull("legacy marker has no preview", result.second)
        assertEquals("body", result.third)
    }

    @Test
    fun `an untagged plain body is returned verbatim with no false-positive head consumption`() {
        val result = untag("just text")
        assertNull(result.first)
        assertNull(result.second)
        assertEquals("just text", result.third)
    }
}
