package co.electriccoin.zcash.ui.screen.enhanceddestroy.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.enhanceddestroy.KeptNameItem
import co.electriccoin.zcash.ui.screen.enhanceddestroy.KeptNamesState

/**
 * DESTROY-ADV-N (ZC-722; spec revision 2c, 5.2 and 9.4): the screen "Names you kept". One paragraph, the names (name
 * and short address) with "Save as contact" and "Remove from the list", and "Erase the whole list" with a
 * confirmation. A list that cannot be read shows its message and only "Erase the whole list".
 */
@Composable
fun KeptNamesView(
    state: KeptNamesState,
    onSaveAsContact: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEraseWholeList: () -> Unit,
    onEraseConfirmed: () -> Unit,
    onEraseDismissed: () -> Unit,
) {
    Scaffold(
        topBar = {
            ZashiSmallTopAppBar(
                title = stringResource(R.string.kept_names_title),
                navigationAction = { ZashiTopAppBarBackNavigation(onBack = state.onBack) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.unreadable) {
                UnreadableList(onEraseWholeList = onEraseWholeList)
            } else {
                KeptList(state, onSaveAsContact, onRemove, onEraseWholeList)
            }
        }
    }

    if (state.eraseConfirmShown) {
        AlertDialog(
            onDismissRequest = onEraseDismissed,
            text = { Text(stringResource(R.string.kept_names_remove_all_confirm)) },
            confirmButton = {
                TextButton(onClick = onEraseConfirmed) { Text(stringResource(R.string.kept_names_remove_all)) }
            },
            dismissButton = {
                TextButton(onClick = onEraseDismissed) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}

/** The list cannot be read: its message, no name, and only "Erase the whole list". */
@Composable
private fun UnreadableList(onEraseWholeList: () -> Unit) {
    Text(
        text = stringResource(R.string.kept_names_unreadable),
        style = ZashiTypography.textMd,
        color = ZashiColors.Text.textPrimary
    )
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onEraseWholeList) { Text(stringResource(R.string.kept_names_remove_all)) }
}

@Composable
private fun KeptList(
    state: KeptNamesState,
    onSaveAsContact: (String) -> Unit,
    onRemove: (String) -> Unit,
    onEraseWholeList: () -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                text = stringResource(R.string.kept_names_intro),
                style = ZashiTypography.textMd,
                color = ZashiColors.Text.textPrimary
            )
        }
        if (state.allSaved) {
            item {
                Text(
                    text = stringResource(R.string.kept_names_all_saved),
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textTertiary
                )
            }
        }
        items(state.entries, key = { it.address }) { entry ->
            KeptEntry(entry, onSaveAsContact, onRemove)
        }
        item {
            HorizontalDivider()
            TextButton(onClick = onEraseWholeList) { Text(stringResource(R.string.kept_names_remove_all)) }
        }
    }
}

@Composable
private fun KeptEntry(
    entry: KeptNameItem,
    onSaveAsContact: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = entry.name, style = ZashiTypography.textMd, color = ZashiColors.Text.textPrimary)
        Text(text = entry.shortAddress.getValue(), style = ZashiTypography.textSm, color = ZashiColors.Text.textTertiary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onSaveAsContact(entry.address) }) { Text(stringResource(R.string.kept_names_save)) }
            TextButton(onClick = { onRemove(entry.address) }) { Text(stringResource(R.string.kept_names_remove)) }
        }
    }
}
