package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1071: the outgoing rows whose send a ChatViewModel of this process is still making, by pending-row id, with the
 * view model that makes it.
 *
 * Every screen has its own ChatViewModel over the one store. A row is registered where its send starts: [waiting] when
 * its row is written to wait in its screen's queue (or behind its quote), [running] when a send runs for it. It is
 * released where that run ends, unless it waits in its screen's queue again, and every row still waiting is released
 * when its screen is cleared ([releaseWaiting]: that queue never runs). A run that goes on after its screen was cleared
 * (the process-wide send scope) releases its row when it ends. The pending-row loader never marks a live row as not
 * sent, and Retry never takes a live row over, so a second screen neither says "ZChat was closed" about a message that
 * is going out nor builds and pays its own transaction for it. Memory only: at process start nothing is live, and a row
 * stored in flight then is what a closed app left (the loader's FAILED + Retry).
 *
 * Every release is told to the screens that listen ([listen]), so a screen that loaded the row while it was live maps
 * it again from the store (review finding 1): its stored failure, or "ZChat was closed" with Retry. ZC-1114: a send that
 * finished under a new id (a relay row stored again as `nmsg-…`) is released with that id, so a listening screen shows the
 * row under it instead of keeping the old id in flight.
 */
object LiveSenders {
    private class Sender(
        val owner: Any,
        val running: Boolean,
    )

    private val senders = ConcurrentHashMap<String, Sender>()

    private val listeners = ConcurrentHashMap<Any, (rowId: String, sender: Any, replacedBy: String?) -> Unit>()

    /**
     * [owner] is told, on the releasing thread, of every row no screen of this process sends any more, with the sender
     * that let it go and the id the sender stored the row under instead, if it did (ZC-1114).
     */
    fun listen(
        owner: Any,
        onReleased: (rowId: String, sender: Any, replacedBy: String?) -> Unit,
    ) {
        listeners[owner] = onReleased
    }

    /** [owner]'s screen was cleared: it is told nothing more. */
    fun stopListening(owner: Any) {
        listeners.remove(owner)
    }

    private fun tellReleased(
        rowId: String,
        sender: Any,
        replacedBy: String? = null,
    ) {
        // A listener only updates what its screen shows; its failure never reaches the run that let the row go.
        listeners.values.forEach { runCatching { it(rowId, sender, replacedBy) } }
    }

    /** [owner] has [rowId] waiting in its queue (or behind its quote); a later owner of the row takes it over. */
    fun waiting(
        rowId: String,
        owner: Any,
    ) {
        senders[rowId] = Sender(owner, running = false)
    }

    /** A send of [owner] runs for [rowId] (Retry reuses the id, so a later owner takes the row over). */
    fun running(
        rowId: String,
        owner: Any,
    ) {
        senders[rowId] = Sender(owner, running = true)
    }

    /**
     * [owner]'s send of [rowId] ended; a registration of another owner is left as it is. [replacedBy]: the id the sender
     * stored the row under instead of [rowId] (ZC-1114), told to the listeners.
     */
    fun release(
        rowId: String,
        owner: Any,
        replacedBy: String? = null,
    ) {
        var gone = false
        senders.computeIfPresent(rowId) { _, sender ->
            if (sender.owner === owner) {
                gone = true
                null
            } else {
                sender
            }
        }
        if (gone) tellReleased(rowId, owner, replacedBy)
    }

    /** [owner]'s screen was cleared: the rows waiting in its queue are no longer sent by anyone. */
    fun releaseWaiting(owner: Any) {
        senders.forEach { (rowId, sender) ->
            if (sender.owner === owner && !sender.running && senders.remove(rowId, sender)) tellReleased(rowId, owner)
        }
    }

    fun isLive(rowId: String): Boolean = senders.containsKey(rowId)

    /**
     * ZC-1990 (money-path rule 2): the unsent rows the user deleted (hid) on any screen of this process, so a send another
     * screen runs or queues for one of them sees the delete without a store read. Memory only, as the senders.
     */
    private val hiddenRows: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The user deleted (hid) [rowIds] on a screen of this process. */
    fun hidden(rowIds: Collection<String>) {
        hiddenRows.addAll(rowIds)
    }

    /** The user brought [rowId] back. */
    fun unhidden(rowId: String) {
        hiddenRows.remove(rowId)
    }

    fun isHidden(rowId: String): Boolean = rowId in hiddenRows

    @VisibleForTesting
    fun resetForTest() {
        senders.clear()
        listeners.clear()
        hiddenRows.clear()
    }
}
