package co.electriccoin.zcash.ui.regression

import java.io.File
import kotlin.test.fail

/** Finds the ui-lib module from the test's working directory, so source and resource pins read the real files. */
internal object PolSourceFiles {
    val uiLib: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val current = dir ?: return@repeat
            listOf(current, current.resolve("ui-lib")).forEach { module ->
                if (module.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory) return@lazy module
            }
            dir = current.parentFile
        }
        fail("could not locate the ui-lib module")
    }

    fun main(path: String): File =
        uiLib.resolve("src/main/java/co/electriccoin/zcash/ui/$path").also {
            if (!it.isFile) fail("expected ${it.path}")
        }

    fun res(path: String): File =
        uiLib.resolve("src/main/res/ui/$path").also {
            if (!it.isFile) fail("expected ${it.path}")
        }
}
