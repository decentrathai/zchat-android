package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * RetestGap06 — RED regression pin for the reply-ref codec round-trip embedded in
 * [ChatViewModel] (private `tagReply` / `untagReply` / `escapeReplyPreview` /
 * `unescapeReplyPreview` + the `replyRefPrefix = "<U+0001>RPL:"` / `replyRefSentinel = U+0001` /
 * `replyPreviewSep = U+001F` constants — ChatViewModel.kt ~line 2348).
 *
 * The fix carries a short PREVIEW of the quoted message inside the on-wire reply marker
 * (`<U+0001>RPL:<id><U+001F><previewEscaped><U+0001><body>`) so a NOSTR receiver — whose local
 * message ids differ from the sender's — has something to render for the quote. Reverting to the
 * legacy id-only marker (`<U+0001>RPL:<id><U+0001><body>`) makes `untagReply`'s preview element
 * null and the cross-device receiver renders no quote. Assertion (1) is the load-bearing anchor.
 *
 * The codec members are private INSTANCE methods that read only the three private final constant
 * fields and no other VM state, so we exercise them WITHOUT the (16-dependency) ChatViewModel
 * constructor: allocate a bare instance via sun.misc.Unsafe (reflected by name — no compile-time
 * dependency on sun.misc, and the ctor's heavy `init {}` never runs), set the three constants by
 * reflection, then invoke the private methods reflectively. Pure JVM lane (no Context / prefs).
 */
class RetestGap06ReplyRefCodecTest {

    // Bare ChatViewModel: no constructor, no init {} — only the three codec constants are populated.
    private val vm: Any = run {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = theUnsafe.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val instance: Any = allocate.invoke(unsafe, ChatViewModel::class.java)!!
        setField(instance, "replyRefSentinel", '\u0001')
        setField(instance, "replyRefPrefix", "\u0001RPL:")
        setField(instance, "replyPreviewSep", '\u001F')
        instance
    }

    private fun setField(target: Any, name: String, value: Any) {
        ChatViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun tagReply(id: String, body: String, preview: String): String {
        val m =
            ChatViewModel::class.java
                .getDeclaredMethod("tagReply", String::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
        return m.invoke(vm, id, body, preview) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun untagReply(content: String): Triple<String?, String?, String> {
        val m =
            ChatViewModel::class.java
                .getDeclaredMethod("untagReply", String::class.java)
                .apply { isAccessible = true }
        return m.invoke(vm, content) as Triple<String?, String?, String>
    }

    /**
     * (1) PREVIEW SURVIVES — the load-bearing RED anchor. Reverting the fix to an id-only marker
     * (dropping the preview arg / returning a null preview) makes `result.second` null and this
     * fails.
     */
    @Test
    fun `preview survives the reply-ref round trip`() {
        val result = untagReply(tagReply("nostr-abc123", "hi there", "Original quoted text"))

        assertEquals(Triple("nostr-abc123", "Original quoted text", "hi there"), result)
        // ANCHOR: the preview element MUST NOT be null (id-only-marker revert => null).
        assertNotNull("preview must ride the marker so the cross-device receiver can render it", result.second)
        assertEquals("Original quoted text", result.second)
    }

    /**
     * (2) ESCAPING IS LOSSLESS AND BODY-SAFE. The preview embeds a raw sentinel (U+0001), a raw
     * separator (U+001F), backslashes and a newline; escaping must keep the embedded U+0001 from
     * being read as the body terminator, round-trip U+001F/U+0001/backslash, and flatten newline.
     */
    @Test
    fun `preview escaping is lossless and body-safe`() {
        // "back\\slash" = two literal backslashes; "\n" = a real newline before "line".
        val preview = "sep\u001F soh\u0001 back\\\\slash and\nline"

        val (id, out, body) = untagReply(tagReply("id1", "the body", preview))

        assertEquals("id1", id)
        // An embedded U+0001 in the preview must NOT be mistaken for the sentinel terminator.
        assertEquals("the body", body)
        // U+001F / U+0001 / backslash round-trip; newline flattened to a single space.
        assertEquals("sep\u001F soh\u0001 back\\\\slash and line", out)
    }

    /** (3) LEGACY BACK-COMPAT: the old id-only marker still parses, with a null preview. */
    @Test
    fun `legacy id-only marker stays backward compatible`() {
        assertEquals(
            Triple("txid123", null, "plain body"),
            untagReply("\u0001RPL:txid123\u0001plain body"),
        )
    }

    /** (4) NON-MARKER PASSTHROUGH: ordinary text is returned untouched. */
    @Test
    fun `non-marker content passes through unchanged`() {
        assertEquals(
            Triple(null, null, "just text"),
            untagReply("just text"),
        )
    }
}
