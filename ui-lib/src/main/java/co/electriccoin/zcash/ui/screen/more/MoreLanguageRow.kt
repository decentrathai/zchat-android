package co.electriccoin.zcash.ui.screen.more

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.settings.model.AppLanguage

/** ZC-1103: the More row "Language: <current choice>"; [onClick] opens the language picker. */
fun languageRow(
    current: AppLanguage,
    onClick: () -> Unit
): ListItemState =
    ListItemState(
        title = stringRes(R.string.more_row_language, stringRes(current.displayNameRes())),
        bigIcon = imageRes(R.drawable.ic_more_language),
        onClick = onClick
    )
