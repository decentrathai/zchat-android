package co.electriccoin.zcash.ui.screen.insufficientfunds

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsufficientFundsScreen(args: InsufficientFundsArgs) {
    val vm = koinViewModel<InsufficientFundsVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    InsufficientFundsView(state)
}

@Serializable
data class InsufficientFundsArgs(
    val context: InsufficientFundsContext = InsufficientFundsContext.PAYMENT
)

@Serializable
enum class InsufficientFundsContext {
    PAYMENT,
    MESSAGE,
    SWAP
}
