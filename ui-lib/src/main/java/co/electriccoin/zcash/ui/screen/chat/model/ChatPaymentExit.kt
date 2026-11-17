package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.chat.ChatDetail
import java.util.concurrent.atomic.AtomicReference

/**
 * ZC-1273: where the sending screen goes after a payment. A payment sent from a chat returns to that chat, once; any other
 * send goes to the root as it always did. Navigator falls back to the root when the chat has left the stack.
 */
class ChatPaymentExit {
    private val markedPeer = AtomicReference<String?>(null)

    val isMarked: Boolean get() = markedPeer.get() != null

    fun mark(peerAddress: String) = markedPeer.set(peerAddress)

    fun clear() = markedPeer.set(null)

    /** Consumes the mark, so a later send from the wallet goes to the root. */
    fun command(): NavigationCommand =
        if (markedPeer.getAndSet(null) != null) NavigationCommand.BackTo(ChatDetail::class) else NavigationCommand.BackToRoot

    companion object {
        /** The one mark of the app: ChatViewModel writes it, and Koin hands it to the exits of the sending screen. */
        val shared = ChatPaymentExit()
    }
}

/** Carries out a [ChatPaymentExit.command]. */
fun NavigationRouter.follow(command: NavigationCommand) {
    if (command is NavigationCommand.BackTo) backTo(command.route) else backToRoot()
}
