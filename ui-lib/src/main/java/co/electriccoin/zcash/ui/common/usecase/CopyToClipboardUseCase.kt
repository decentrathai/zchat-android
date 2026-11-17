package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.spackle.ClipboardManagerUtil
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getString

class CopyToClipboardUseCase(
    private val context: Context
) {
    operator fun invoke(value: String) {
        ClipboardManagerUtil.copyToClipboard(
            context = context,
            value = value
        )
    }

    /** Copies [value] as the screen shows it, in the phone's language. */
    operator fun invoke(value: StringResource) = invoke(value.getString(context))
}
