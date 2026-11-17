package co.electriccoin.zcash.ui.common.sendattempt

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * F4 (SPECS §1.2 "The in-flight guard", review point 3): this process's identity and the rows whose send is running in
 * it.
 *
 * A row is entered when its Retry is claimed or its submit marker is written, and left when its send ends (every
 * path, including a cancellation). A row on the set is never judged: its submit may still be running here, whatever
 * the chain shows (RetryNeverPaysTwiceTest: a held submit in a reopened screen, 10,000 blocks later, no id and no
 * candidate). [ID] is written on each attempt, so a later process can tell an attempt of an ended process (K1).
 */
object SendAttemptProcess {
    /** A random id made once per process. */
    val ID: String = UUID.randomUUID().toString()

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun enter(rowId: String) {
        inFlight.add(rowId)
    }

    fun leave(rowId: String) {
        inFlight.remove(rowId)
    }

    fun isInFlight(rowId: String): Boolean = rowId in inFlight

    /** The account hash written on an attempt: SHA-256 of the account's id bytes, 16 hex characters. */
    fun accountHash(accountIdBytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(accountIdBytes)
            .take(ACCOUNT_HASH_BYTES)
            .joinToString("") { "%02x".format(it) }

    private const val ACCOUNT_HASH_BYTES = 8
}
