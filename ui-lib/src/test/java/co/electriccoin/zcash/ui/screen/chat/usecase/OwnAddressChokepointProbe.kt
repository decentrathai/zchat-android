package co.electriccoin.zcash.ui.screen.chat.usecase

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy

/**
 * T3-extra (fix-all 2.13.0, train T3) — builds the chat send chokepoint ([CreateChunkedMessageProposalUseCase]) with the
 * own-address check the fix adds as its sixth constructor parameter, `isOwnAddress: (String) -> Boolean`. Reached by
 * reflection so the RED tests compile before the parameter exists; without it every probe fails with one clear
 * message instead of a compile error.
 */
internal object OwnAddressChokepointProbe {
    /** The chokepoint over [proposals], with [isOwnAddress] as its own-address check. */
    fun useCase(
        accounts: AccountDataSource,
        proposals: ProposalDataSource,
        biometrics: BiometricRepository,
        isOwnAddress: (String) -> Boolean,
        keystone: KeystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
        onRepository: (ZashiProposalRepositoryImpl) -> Unit = {},
    ): CreateChunkedMessageProposalUseCase {
        val constructor =
            CreateChunkedMessageProposalUseCase::class.java.constructors.firstOrNull {
                it.parameterCount == PARAMETERS && it.parameterTypes[PARAMETERS - 1] == Function1::class.java
            } ?: throw AssertionError(
                "the chat send chokepoint (CreateChunkedMessageProposalUseCase) takes no own-address check: " +
                    "no constructor ends in isOwnAddress: (String) -> Boolean, so a send to one of our own addresses " +
                    "is built and paid like any other",
            )
        val repository =
            ZashiProposalRepositoryImpl(
                accountDataSource = accounts,
                proposalDataSource = proposals,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            ).also(onRepository)
        return constructor.newInstance(
            keystone,
            repository,
            accounts,
            Zc101RecordingNavigationRouter(),
            biometrics,
            isOwnAddress,
        ) as CreateChunkedMessageProposalUseCase
    }

    /** ChatSendCopy's constant [name], or an AssertionError naming it when this build has no such text. */
    fun copy(name: String): String =
        runCatching { ChatSendCopy::class.java.getField(name).get(null) as String }.getOrElse {
            throw AssertionError("ChatSendCopy has no $name text (the own-address refusal the user must read)")
        }

    private const val PARAMETERS = 6
}
