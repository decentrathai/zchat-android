package co.electriccoin.zcash.ui.screen.chat.model

sealed class SendMessageState {
    data object Idle : SendMessageState()
    data object Sending : SendMessageState()
    data object Success : SendMessageState()
    data class Error(val message: String) : SendMessageState()
}
