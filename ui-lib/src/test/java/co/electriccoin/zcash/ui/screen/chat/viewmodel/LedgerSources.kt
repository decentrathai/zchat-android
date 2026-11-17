package co.electriccoin.zcash.ui.screen.chat.viewmodel

import java.io.File
import kotlin.test.fail

/** F8: the production sources the ledger's source scans read. */
internal object LedgerSources {
    private fun root(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(start) { it.parentFile }
            .take(8)
            .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
            .firstOrNull { it.resolve("src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt").isFile }
            ?: fail("ui-lib not found from ${start.path}")
    }

    fun main(path: String): String = root().resolve("src/main/java/co/electriccoin/zcash/$path").readText()

    fun res(path: String): String = root().resolve("src/main/res/ui/common/$path").readText()

    val chatViewModel: String by lazy { main("ui/screen/chat/viewmodel/ChatViewModel.kt") }

    /** The code between `// <name>-BEGIN` and `// <name>-END`, with comment lines removed. */
    fun region(name: String): String {
        val source = chatViewModel
        val begin = source.indexOf("// $name-BEGIN")
        val end = source.indexOf("// $name-END")
        if (begin < 0 || end < begin) fail("ChatViewModel.kt has no $name-BEGIN / $name-END region")
        return source
            .substring(begin, end)
            .lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            .joinToString("\n")
    }

    /** The body of the first function named [name] in ChatViewModel.kt (to the next member at the same indent). */
    fun function(name: String): String {
        val source = chatViewModel
        val declaration = Regex("""\n    (private |internal )?(suspend )?fun $name\(""").find(source)
        val start = declaration?.range?.first ?: fail("no fun $name in ChatViewModel.kt")
        val end = source.indexOf("\n    }\n", start)
        return source.substring(start, end)
    }

    fun string(
        folder: String,
        file: String,
        key: String,
    ): String =
        Regex("<string name=\"$key\">(.*?)</string>").find(res("$folder/$file"))?.groupValues?.get(1)
            ?: fail("no `$key` in $folder/$file")
}
