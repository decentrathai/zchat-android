package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.keystone.RecordingKeystoneProposalRepository
import co.electriccoin.zcash.ui.keystone.RouteRecordingNavigationRouter
import co.electriccoin.zcash.ui.keystone.TwoAccountDataSource
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase

/**
 * K0-2 (fix-all 2.13.0, train T1) — a [PaymentHarness] chat screen whose phone has a Keystone account next to the
 * ZChat account. The real ChatViewModel entry points run through the real CreateChunkedMessageProposalUseCase; the
 * Keystone signing session ([keystone]) and the navigation ([router]) are recorded, and [accounts] can switch the
 * selected account between two taps.
 */
internal class KeystoneChatScreen(
    val prefs: PaymentPrefs,
    val seam: PaymentSeam,
    val accounts: TwoAccountDataSource,
    val keystone: RecordingKeystoneProposalRepository,
    val router: RouteRecordingNavigationRouter,
    val vm: ChatViewModel,
) {
    companion object {
        suspend fun open(peer: String): KeystoneChatScreen {
            val prefs = PaymentPrefs(peer)
            val seam = PaymentSeam(peer)
            val accounts = TwoAccountDataSource.keystoneSelected()
            val keystone = RecordingKeystoneProposalRepository()
            val router = RouteRecordingNavigationRouter()
            val vm = PaymentHarness.paymentViewModel(prefs, seam)
            val useCase =
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = keystone,
                    zashiProposalRepository =
                        ZashiProposalRepositoryImpl(
                            accountDataSource = accounts,
                            proposalDataSource = seam,
                            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                        ).also(SendHarnessScopes.current()::own),
                    accountDataSource = accounts,
                    navigationRouter = router,
                    biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                )
            PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase)
            PaymentHarness.setField(vm, "accountDataSource", accounts)
            return KeystoneChatScreen(prefs, seam, accounts, keystone, router, vm)
        }

        /**
         * ChatSendCopy.KEYSTONE_CHATS_CLOSED, read by name so the tests compile before it exists. A const val on an
         * object is a static field.
         */
        fun closedText(): String = ChatSendCopy::class.java.getField("KEYSTONE_CHATS_CLOSED").get(null) as String
    }
}
