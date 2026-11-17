package co.electriccoin.zcash.ui.screen.reviewtransaction

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import org.koin.androidx.compose.koinViewModel

@Composable
fun AndroidReviewTransaction() {
    val vm = koinViewModel<ReviewTransactionVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // QA2 Q2-16 (ZC-1619): cancelling the system Send Funds prompt says that nothing was sent or paid.
    LaunchedEffect(vm) {
        vm.sendCancelled.collect {
            val text = context.getString(R.string.send_confirmation_auth_cancelled_not_sent)
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
    BackHandler(enabled = state != null) { state?.onBack?.invoke() }
    state?.let { ReviewTransactionView(it) }
}
