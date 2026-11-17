package co.electriccoin.zcash.ui.screen.changeidentity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.util.getValue

@Composable
fun ChangeIdentityView(state: ChangeIdentityState) {
    // Confirmation Dialog
    if (state.showConfirmationDialog) {
        ConfirmationDialog(
            aiResetNote = state.aiResetNote,
            mode = state.selectedMode,
            onDismiss = state.onConfirmationDialogDismiss,
            onConfirm = state.onConfirmationDialogConfirm
        )
    }

    // Success Dialog
    if (state.showSuccessDialog) {
        SuccessDialog(
            onDismiss = state.onSuccessDialogDismiss
        )
    }

    // Error Dialog
    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = state.onErrorDismiss,
            title = { Text(stringResource(R.string.identity_error_title)) },
            text = { Text(error.getValue()) },
            confirmButton = {
                TextButton(onClick = state.onErrorDismiss) {
                    Text(stringResource(R.string.chat_dialog_ok))
                }
            }
        )
    }

    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(
                title = stringResource(R.string.identity_title),
                showTitleLogo = false,
                navigationAction = {
                    ZashiTopAppBarBackNavigation(onBack = state.onBack)
                }
            )
        }
    ) { paddingValues ->
        if (state.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.identity_processing))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Warning Header
                WarningCard()

                Spacer(modifier = Modifier.height(24.dp))

                // Current Address Display
                CurrentAddressCard(address = state.currentAddress)

                Spacer(modifier = Modifier.height(24.dp))

                // Mode Selection
                Text(
                    text = stringResource(R.string.identity_choose_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                ModeOption(
                    title = stringResource(R.string.identity_diversified_title),
                    description = stringResource(R.string.identity_diversified_desc),
                    isSelected = state.selectedMode == IdentityMode.DIVERSIFIED,
                    recommended = true,
                    onClick = { state.onModeSelected(IdentityMode.DIVERSIFIED) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ModeOption(
                    title = stringResource(R.string.identity_full_reset_title),
                    description = stringResource(R.string.identity_full_reset_desc),
                    isSelected = state.selectedMode == IdentityMode.FULL_RESET,
                    recommended = false,
                    warning = true,
                    onClick = { state.onModeSelected(IdentityMode.FULL_RESET) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // R2-7 (a): contacts are never told the new address (the old notify-everyone option sent
                // nothing and quoted a cost). Say so, for the mode the user picked.
                ContactsNotToldCard(mode = state.selectedMode, contactCount = state.contactCount)

                Spacer(modifier = Modifier.height(32.dp))
                Spacer(modifier = Modifier.weight(1f))

                // Confirm Button
                Button(
                    onClick = state.onConfirmClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.selectedMode == IdentityMode.FULL_RESET)
                            ZashiColors.Utility.ErrorRed.utilityError500 else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (state.selectedMode == IdentityMode.FULL_RESET) {
                            stringResource(R.string.identity_reset_wallet)
                        } else {
                            stringResource(R.string.identity_title)
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun WarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ZashiColors.Utility.WarningYellow.utilityOrange50
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = ZashiColors.Utility.WarningYellow.utilityOrange500,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.identity_change_heading),
                    fontWeight = FontWeight.Bold,
                    color = ZashiColors.Utility.WarningYellow.utilityOrange700
                )
                Text(
                    text = stringResource(R.string.identity_change_body),
                    fontSize = 13.sp,
                    color = ZashiColors.Utility.WarningYellow.utilityOrange700
                )
            }
        }
    }
}

@Composable
private fun CurrentAddressCard(address: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = stringResource(R.string.identity_current_address),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    when {
                        address.isBlank() -> stringResource(R.string.identity_error_loading_address)
                        address.length > 40 -> "${address.take(20)}...${address.takeLast(16)}"
                        else -> address
                    },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    description: String,
    isSelected: Boolean,
    recommended: Boolean,
    warning: Boolean = false,
    onClick: () -> Unit
) {
    val borderColor = when {
        isSelected && warning -> ZashiColors.Utility.ErrorRed.utilityError500
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                borderColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Radio indicator
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(2.dp, borderColor, CircleShape)
                    .background(if (isSelected) borderColor else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (recommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.identity_recommended),
                            fontSize = 10.sp,
                            // White on the saturated green badge bg reads well in every theme. The *50
                            // success shade INVERTS to near-black in plain Dark Zashi (dark-on-green =
                            // poor contrast), so don't use it for on-badge text.
                            color = Color.White,
                            modifier = Modifier
                                .background(
                                    ZashiColors.Utility.SuccessGreen.utilitySuccess500,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (warning) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.identity_caution),
                            fontSize = 10.sp,
                            // White on the saturated red badge bg reads well in every theme (the *50 error
                            // shade inverts to near-black in plain Dark Zashi — poor on-red contrast).
                            color = Color.White,
                            modifier = Modifier
                                .background(
                                    ZashiColors.Utility.ErrorRed.utilityError500,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ContactsNotToldCard(
    mode: IdentityMode,
    contactCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (contactCount > 0) {
                Text(
                    text = pluralStringResource(R.plurals.change_identity_contact_count, contactCount, contactCount),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = stringResource(contactsNotToldText(mode)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun contactsNotToldText(mode: IdentityMode): Int =
    when (mode) {
        IdentityMode.FULL_RESET -> R.string.change_identity_full_reset_contacts
        IdentityMode.DIVERSIFIED -> R.string.change_identity_diversified_contacts
    }

@Composable
private fun ConfirmationDialog(
    aiResetNote: Int?,
    mode: IdentityMode,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val isFullReset = mode == IdentityMode.FULL_RESET

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isFullReset) {
                        ZashiColors.Utility.ErrorRed.utilityError500
                    } else {
                        ZashiColors.Utility.WarningYellow.utilityOrange500
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        if (isFullReset) {
                            stringResource(R.string.identity_confirm_reset_title)
                        } else {
                            stringResource(R.string.identity_confirm_change_title)
                        },
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                if (isFullReset) {
                    Text(
                        text = stringResource(R.string.identity_reset_warning),
                        fontWeight = FontWeight.Bold,
                        color = ZashiColors.Utility.ErrorRed.utilityError500
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.identity_reset_step_seed))
                    Text(stringResource(R.string.identity_reset_step_transfer))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.identity_cannot_undo),
                        fontWeight = FontWeight.Bold,
                        color = ZashiColors.Utility.ErrorRed.utilityError500
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.change_identity_full_reset_contacts))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(aiResetNote ?: R.string.identity_reset_ai_note))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.identity_reset_old_data_note))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.identity_reset_stays_note))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.identity_reset_restart_note))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.delete_wallet_confirmation_payment_note))
                } else {
                    Text(stringResource(R.string.identity_new_messaging))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.change_identity_diversified_contacts))

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.identity_can_switch_back),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isFullReset) {
                        ZashiColors.Utility.ErrorRed.utilityError500
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                Text(
                    if (isFullReset) {
                        stringResource(R.string.identity_reset_wallet)
                    } else {
                        stringResource(R.string.identity_confirm)
                    }
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.identity_cancel))
            }
        }
    )
}

@Composable
private fun SuccessDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = ZashiColors.Utility.SuccessGreen.utilitySuccess500
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.identity_success),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Text(
                // ZC-090-R: only a change of identity ends here; a Full Wallet Reset restarts ZCHAT first.
                text = stringResource(R.string.identity_change_done),
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.chat_dialog_ok))
            }
        }
    )
}
