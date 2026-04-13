package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

/**
 * Thrown when [E2ERatchet.decrypt] receives a (direction, counter) pair that has already
 * been consumed by a prior successful decrypt. Either a replay attack or a buggy caller.
 *
 * Per Stage A' hygiene findings, crypto-critical errors are thrown as explicit exceptions
 * and not silently swallowed into null returns.
 */
class ReplayDetectedException(
    val direction: Byte,
    val counter: Long,
) : Exception("Counter $counter for direction $direction already seen")

/**
 * Thrown when [E2ERatchet.decrypt] receives a counter too far ahead of the current
 * max-seen value for its direction. DoS protection — a malicious peer cannot force
 * arbitrary chain-walk work by claiming a huge counter value.
 */
class CounterOutOfRangeException(
    val direction: Byte,
    val counter: Long,
    val maxAllowed: Long,
) : Exception("Counter $counter for direction $direction exceeds max allowed $maxAllowed (MAX_SKIP window)")
