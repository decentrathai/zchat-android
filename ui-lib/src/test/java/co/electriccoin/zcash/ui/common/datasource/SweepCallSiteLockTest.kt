package co.electriccoin.zcash.ui.common.datasource

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The public Orchard crossing can be reached from exactly one place (sweep spec §3, "Lock"): the SDK call
 * sits in the sweep funnel, only the repository and the sheet's VM ask for a sweep, only they submit one,
 * and a sweep proposal is only ever built by the funnel's validating factory. Scans ui-lib's main sources.
 */
class SweepCallSiteLockTest {
    @Test
    fun the_sdk_crossing_is_called_once_in_the_funnel() {
        assertEquals(mapOf(PROPOSAL_DATA_SOURCE to 1), occurrences("proposeOrchardToIronwoodMigration"))
    }

    @Test
    fun only_the_repository_and_the_sheet_ask_for_a_sweep() {
        assertEquals(
            setOf(PROPOSAL_DATA_SOURCE, REPOSITORY, SHEET_VM),
            occurrences("createOrchardSweepProposal(").keys,
        )
    }

    @Test
    fun only_the_repository_and_the_sheet_submit_a_sweep() {
        assertEquals(setOf(REPOSITORY, SHEET_VM), occurrences("submitOrchardSweep(").keys)
    }

    @Test
    fun only_the_funnel_builds_a_sweep_proposal() {
        assertEquals(setOf(PROPOSAL_DATA_SOURCE), occurrences("OrchardSweepTransactionProposal.fromProposal(").keys)
    }

    /** File name to the number of lines of main Kotlin that contain [needle]. */
    private fun occurrences(needle: String): Map<String, Int> =
        mainJava
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val count = file.readLines().count { it.contains(needle) }
                if (count > 0) file.name to count else null
            }.toMap()

    private companion object {
        const val PROPOSAL_DATA_SOURCE = "ProposalDataSource.kt"
        const val REPOSITORY = "ZashiProposalRepository.kt"
        const val SHEET_VM = "MoveOldBalanceVM.kt"

        val mainJava: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/java") }
                .firstOrNull { it.resolve("co/electriccoin/zcash/ui/common/datasource").isDirectory }
                ?: fail("could not locate ui-lib/src/main/java from ${start.path}")
        }
    }
}
