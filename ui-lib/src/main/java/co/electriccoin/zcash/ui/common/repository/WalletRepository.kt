package co.electriccoin.zcash.ui.common.repository

import android.app.Application
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FastestServersResult
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.lightwallet.client.LightWalletClient
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.lightwallet.client.model.Response
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSource
import co.electriccoin.zcash.ui.common.model.FastestServersState
import co.electriccoin.zcash.ui.common.model.OnboardingState
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface WalletRepository {
    val secretState: StateFlow<SecretState>

    val fastestEndpoints: StateFlow<FastestServersState>

    val walletRestoringState: StateFlow<WalletRestoringState>

    fun createNewWallet()

    fun createNewWalletForOnboarding()

    fun completeOnboarding()

    fun restoreWallet(
        network: ZcashNetwork,
        seedPhrase: SeedPhrase,
        birthday: BlockHeight
    )

    fun updateWalletEndpoint(endpoint: LightWalletEndpoint)

    fun refreshFastestServers()
}

class WalletRepositoryImpl(
    configurationRepository: ConfigurationRepository,
    private val application: Application,
    private val lightWalletEndpointProvider: LightWalletEndpointProvider,
    private val persistableWalletProvider: PersistableWalletProvider,
    private val synchronizerProvider: SynchronizerProvider,
    private val standardPreferenceProvider: StandardPreferenceProvider,
    private val restoreTimestampDataSource: RestoreTimestampDataSource,
    private val walletRestoringStateProvider: WalletRestoringStateProvider,
    private val walletBackupFlagStorageProvider: WalletBackupFlagStorageProvider,
) : WalletRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Serializes onboarding wallet minting. The #189 "already-exists" guard alone is a TOCTOU: the check
    // and the persist straddle an async gRPC birthday fetch, so two rapid "Start Chatting" taps (welcome →
    // Identity → Android-back → tap again, within one gRPC RTT) could BOTH pass the guard and persist
    // DIFFERENT seeds — the second overwriting the first and orphaning any funds already received. This
    // mutex makes the check-then-persist atomic across concurrent calls.
    private val onboardingMintMutex = Mutex()

    private val refreshFastestServersRequest = MutableSharedFlow<Unit>(replay = 1)

    private val onboardingState =
        flow {
            emitAll(
                StandardPreferenceKeys.ONBOARDING_STATE.observe(standardPreferenceProvider()).map { persistedNumber ->
                    OnboardingState.fromNumber(persistedNumber)
                }
            )
        }

    override val secretState: StateFlow<SecretState> =
        combine(configurationRepository.configurationFlow, onboardingState) { config, onboardingState ->
            if (config == null) {
                SecretState.LOADING
            } else {
                when (onboardingState) {
                    OnboardingState.NEEDS_WARN,
                    OnboardingState.NEEDS_BACKUP,
                    OnboardingState.NONE,
                    OnboardingState.ONBOARDING_IN_PROGRESS -> SecretState.NONE

                    OnboardingState.READY -> SecretState.READY
                }
            }
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = SecretState.LOADING
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    override val fastestEndpoints =
        channelFlow {
            var previousFastestServerState: FastestServersState? = null

            combine(
                refreshFastestServersRequest.onStart { emit(Unit) },
                synchronizerProvider.synchronizer
            ) { _, synchronizer -> synchronizer }
                .withIndex()
                .flatMapLatest { (_, synchronizer) ->
                    synchronizer
                        ?.getFastestServers(lightWalletEndpointProvider.getEndpoints())
                        ?.map {
                            when (it) {
                                FastestServersResult.Measuring ->
                                    previousFastestServerState?.copy(isLoading = true)
                                        ?: FastestServersState(servers = null, isLoading = true)

                                is FastestServersResult.Validating ->
                                    FastestServersState(servers = it.servers, isLoading = true)

                                is FastestServersResult.Done ->
                                    FastestServersState(servers = it.servers, isLoading = false)
                            }
                        } ?: emptyFlow()
                }.onEach {
                    previousFastestServerState = it
                    send(it)
                }.launchIn(this)
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = FastestServersState(servers = emptyList(), isLoading = true)
        )

    override val walletRestoringState: StateFlow<WalletRestoringState> =
        walletRestoringStateProvider
            .observe()
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = WalletRestoringState.NONE
            )

    override fun updateWalletEndpoint(endpoint: LightWalletEndpoint) {
        scope.launch {
            val selectedWallet = persistableWalletProvider.getPersistableWallet() ?: return@launch
            val selectedEndpoint = selectedWallet.endpoint
            if (selectedEndpoint == endpoint) return@launch
            persistWalletInternal(selectedWallet.copy(endpoint = endpoint))
        }
    }

    private suspend fun persistWalletInternal(persistableWallet: PersistableWallet) {
        synchronizerProvider.synchronizer.firstOrNull()?.let { (it as? SdkSynchronizer)?.close() }
        persistableWalletProvider.store(persistableWallet)
    }

    override fun createNewWallet() {
        scope.launch {
            persistOnboardingStateInternal(OnboardingState.READY)
            val zcashNetwork = ZcashNetwork.fromResources(application)
            val endpoint = lightWalletEndpointProvider.getDefaultEndpoint()
            val newWallet =
                PersistableWallet.new(
                    application = application,
                    zcashNetwork = zcashNetwork,
                    endpoint = endpoint,
                    walletInitMode = WalletInitMode.NewWallet,
                )
            // For a brand new wallet, use the current chain height as birthday
            // so it doesn't need to scan historical blocks it can't have transactions in
            val (wallet, usedLatestHeight) = fetchLatestBirthday(endpoint, newWallet)
            // If we got the current chain height, skip the "Setting Up Wallet" state
            // since there are no blocks to scan
            val restoringState =
                if (usedLatestHeight) WalletRestoringState.SYNCING else WalletRestoringState.INITIATING
            walletRestoringStateProvider.store(restoringState)
            persistWalletInternal(wallet)
        }
    }

    override fun createNewWalletForOnboarding() {
        scope.launch {
            // Serialize the whole check-then-persist so two concurrent mints can't both pass the guard
            // (M1 money-safety: a double-mint would overwrite the first seed and orphan its funds).
            onboardingMintMutex.withLock {
                persistOnboardingStateInternal(OnboardingState.ONBOARDING_IN_PROGRESS)
                // #189 (2b): if onboarding was interrupted by process death AFTER the wallet was persisted
                // but BEFORE completeOnboarding(), relaunch drops the user back at the welcome screen, which
                // still offers "Create wallet". Minting a fresh wallet here would OVERWRITE the existing seed
                // and orphan any funds already received. Resume the existing wallet instead of replacing it.
                // Re-checked INSIDE the lock so it also closes the concurrent-tap TOCTOU (not just process
                // death): the first mint to win the lock persists; the second sees the wallet and no-ops.
                if (persistableWalletProvider.getPersistableWallet() != null) return@withLock
                val zcashNetwork = ZcashNetwork.fromResources(application)
                val endpoint = lightWalletEndpointProvider.getDefaultEndpoint()
                val newWallet =
                    PersistableWallet.new(
                        application = application,
                        zcashNetwork = zcashNetwork,
                        endpoint = endpoint,
                        walletInitMode = WalletInitMode.NewWallet,
                    )
                val (wallet, usedLatestHeight) = fetchLatestBirthday(endpoint, newWallet)
                val restoringState =
                    if (usedLatestHeight) WalletRestoringState.SYNCING else WalletRestoringState.INITIATING
                walletRestoringStateProvider.store(restoringState)
                persistWalletInternal(wallet)
            }
        }
    }

    override fun completeOnboarding() {
        scope.launch {
            persistOnboardingStateInternal(OnboardingState.READY)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchLatestBirthday(
        endpoint: LightWalletEndpoint,
        fallbackWallet: PersistableWallet
    ): Pair<PersistableWallet, Boolean> {
        val client = LightWalletClient.new(application, endpoint)
        return try {
            when (val response = client.getLatestBlockHeight()) {
                is Response.Success -> {
                    val latestHeight = BlockHeight.new(response.result.value)
                    Twig.info { "New wallet: using current chain height $latestHeight as birthday" }
                    fallbackWallet.copy(birthday = latestHeight) to true
                }
                else -> {
                    Twig.warn { "New wallet: failed to get latest block height, using bundled checkpoint" }
                    fallbackWallet to false
                }
            }
        } catch (e: Exception) {
            Twig.warn(e) { "New wallet: error querying chain height, using bundled checkpoint" }
            fallbackWallet to false
        } finally {
            client.dispose()
        }
    }

    private suspend fun persistOnboardingStateInternal(onboardingState: OnboardingState) {
        StandardPreferenceKeys.ONBOARDING_STATE.putValue(
            preferenceProvider = standardPreferenceProvider(),
            newValue = onboardingState.toNumber()
        )
    }

    override fun refreshFastestServers() {
        scope.launch {
            if (!fastestEndpoints.first().isLoading) {
                refreshFastestServersRequest.emit(Unit)
            }
        }
    }

    override fun restoreWallet(
        network: ZcashNetwork,
        seedPhrase: SeedPhrase,
        birthday: BlockHeight
    ) {
        scope.launch {
            val restoredWallet =
                PersistableWallet(
                    network = network,
                    birthday = birthday,
                    endpoint = lightWalletEndpointProvider.getDefaultEndpoint(),
                    seedPhrase = seedPhrase,
                    walletInitMode = WalletInitMode.RestoreWallet,
                )
            persistWalletInternal(restoredWallet)
            walletRestoringStateProvider.store(WalletRestoringState.RESTORING)
            walletBackupFlagStorageProvider.store(true)
            restoreTimestampDataSource.getOrCreate()
            persistOnboardingStateInternal(OnboardingState.READY)
        }
    }
}
