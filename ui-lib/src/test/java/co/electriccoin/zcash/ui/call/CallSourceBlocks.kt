package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import java.lang.reflect.InvocationTargetException
import kotlin.test.fail

/**
 * QA2 U2-calls (W-137): helpers for the call tests that read Kotlin sources and reach seams by name, so the tests
 * compile before the seams exist and fail with a message naming what is missing.
 */
internal object CallSourceBlocks {
    const val OVERLAY = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/CallOverlay.kt"
    const val NOTIFICATIONS = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/CallNotificationController.kt"
    const val SERVICE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/service/SyncForegroundService.kt"
    const val CHAT_MESSAGE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ChatMessage.kt"

    fun source(path: String): String = ChatResourceText.source(path)

    /**
     * The text of function [name] in [src], from its `fun` line to the next declaration at the same [indent]
     * ("" for a top-level function, four spaces for a class member).
     */
    fun block(
        src: String,
        name: String,
        indent: String = "",
    ): String {
        val start =
            Regex("""(?m)^$indent(?:private |internal )?fun $name\(""").find(src)?.range?.first
                ?: fail("no function $name in the source")
        val end =
            Regex("""(?m)^$indent(?:@Composable|@Suppress|private |internal |fun |companion object|/\*\*|\})""")
                .findAll(src, start + 1)
                .map { it.range.first }
                .firstOrNull { it > start }
                ?: src.length
        return src.substring(start, end)
    }

    /** The brace-matched body of the first declaration in [src] that starts with [header] (e.g. "fun onCreate("). */
    fun body(
        src: String,
        header: String,
    ): String {
        val at = src.indexOf(header).takeIf { it >= 0 } ?: fail("no $header in the source")
        val open = src.indexOf('{', at)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        fail("unbalanced $header")
    }

    /** The Kotlin `object` [className] (or a class with static methods), and its method [method] with [params] parameters. */
    fun objectCall(
        className: String,
        method: String,
        vararg args: Any?,
    ): Any? {
        val cls =
            runCatching { Class.forName(className) }.getOrElse {
                fail("$className does not exist")
            }
        val m =
            cls.methods.firstOrNull { it.name == method && it.parameterCount == args.size }
                ?: fail("$className has no $method with ${args.size} parameters")
        val receiver = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull()
        return try {
            m.invoke(receiver, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
