package co.electriccoin.zcash.ui.debug

import co.electriccoin.zcash.ui.common.util.FaultSeam
import java.io.File

/**
 * QA test-infrastructure (DEBUG BUILDS ONLY — `debug` source set). The armed fault seams as a small file, so a seam
 * armed before a restart is armed again at the next start (the Keystore seam must hold from the very first store
 * open of a launch). One `NAME=value` line per armed seam, in `<filesDir>/qa/fault_seams.txt`.
 */
internal object FaultSeamsConfig {
    const val DIR = "qa"
    const val FILE = "fault_seams.txt"

    fun file(filesDir: File): File = File(File(filesDir, DIR), FILE)

    /** Parses the file's lines; unknown names and bad values are skipped, so an old file never stops a start. */
    fun parse(lines: List<String>): Map<FaultSeam, Long> =
        lines
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                val name = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=', missingDelimiterValue = "1").trim().toLongOrNull()
                val seam = FaultSeam.entries.firstOrNull { it.name == name }
                if (seam == null || value == null) null else seam to value
            }.toMap()

    fun render(armed: Map<FaultSeam, Long>): String =
        armed.entries
            .sortedBy { it.key.ordinal }
            .joinToString(separator = "") { "${it.key.name}=${it.value}\n" }

    fun read(filesDir: File): Map<FaultSeam, Long> {
        val file = file(filesDir)
        return if (file.isFile) parse(file.readLines()) else emptyMap()
    }

    /** Writes [armed], or deletes the file when nothing is armed. */
    fun write(
        filesDir: File,
        armed: Map<FaultSeam, Long>,
    ) {
        val file = file(filesDir)
        if (armed.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(render(armed))
    }

    /**
     * A one-shot input file in `<filesDir>/qa/`, named by the tester. Only a bare file name is accepted (no path
     * separator, no "..") so the receiver can't be pointed at any other file of the app.
     */
    fun inputFile(
        filesDir: File,
        name: String?,
    ): File? {
        if (name.isNullOrBlank() || !SAFE_NAME.matches(name)) return null
        return File(File(filesDir, DIR), name)
    }

    private val SAFE_NAME = Regex("""[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}""")
}
