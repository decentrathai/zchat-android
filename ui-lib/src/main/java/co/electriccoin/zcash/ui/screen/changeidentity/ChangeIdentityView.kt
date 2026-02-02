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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.util.getValue

@Composable
fun ChangeIdentityView(state: ChangeIdentityState) {
    // Confirmation Dialog
    if (state.showConfirmationDialog) {
        ConfirmationDialog(
            mode = state.selectedMode,
            notification = state.selectedNotification,
            contactCount = state.contactCount,
            estimatedCost = state.estimatedCost,
            onDismiss = state.onConfirmationDialogDismiss,
            onConfirm = state.onConfirmationDialogConfirm
        )
    }

    // Success Dialog
    if (state.showSuccessDialog) {
        SuccessDialog(
            mode = state.selectedMode,
            onDismiss = state.onSuccessDialogDismiss
        )
    }

    // Error Dialog
    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = state.onErrorDismiss,
            title = { Text("Error") },
            text = { Text(error.getValue()) },
            confirmButton = {
                TextButton(onClick = state.onErrorDismiss) {
                    Text("OK")
                }
            }
        )
    }

    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(
                title = "Change Identity",
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
                    Text("Processing...")
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
                    text = "Choose Regeneration Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                ModeOption(
                    title = "Diversified Address",
                    description = "Generate new address from same seed. Your ZEC balance is preserved. You can switch between identities (masks).",
                    isSelected = state.selectedMode == IdentityMode.DIVERSIFIED,
                    recommended = true,
                    onClick = { state.onModeSelected(IdentityMode.DIVERSIFIED) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ModeOption(
                    title = "Full Wallet Reset",
                    description = "Generate entirely new seed phrase. You must transfer ZEC first! Old identity is permanently deleted.",
                    isSelected = state.selectedMode == IdentityMode.FULL_RESET,
                    recommended = false,
                    warning = true,
                    onClick = { state.onModeSelected(IdentityMode.FULL_RESET) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Notification Selection
                Text(
                    text = "Notify Contacts?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                NotificationOption(
                    title = "Notify All Contacts",
                    description = "Send address change notification to ${state.contactCount} contacts. Cost: ~${state.estimatedCost}",
                    isSelected = state.selectedNotification == NotificationOption.NOTIFY_ALL,
                    recommended = true,
                    onClick = { state.onNotificationSelected(NotificationOption.NOTIFY_ALL) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                NotificationOption(
                    title = "Silent Regeneration",
                    description = "Don't notify anyone. Existing conversations will not continue.",
                    isSelected = state.selectedNotification == NotificationOption.SILENT,
                    recommended = false,
                    onClick = { state.onNotificationSelected(NotificationOption.SILENT) }
                )

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
                            Color(0xFFFF5252) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (state.selectedMode == IdentityMode.FULL_RESET)
                            "Reset Wallet" else "Change Identity",
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
            containerColor = Color(0xFFFFF3E0)
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
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Identity Change",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )
                Text(
                    text = "This will change your ZCHAT address. Contacts will need your new address to message you.",
                    fontSize = 13.sp,
                    color = Color(0xFF666666)
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
                text = "Current Address",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (address.length > 40) "${address.take(20)}...${address.takeLast(16)}" else address,
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
        isSelected && warning -> Color(0xFFFF5252)
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
                        tint = Color.White,
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
                            text = "Recommended",
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier
                                .background(
                                    Color(0xFF4CAF50),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (warning) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Caution",
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier
                                .background(
                                    Color(0xFFFF5252),
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
private fun NotificationOption(
    title: String,
    description: String,
    isSelected: Boolean,
    recommended: Boolean,
    onClick: () -> Unit
) {
    ModeOption(
        title = title,
        description = description,
        isSelected = isSelected,
        recommended = recommended,
        warning = false,
        onClick = onClick
    )
}

@Composable
private fun ConfirmationDialog(
    mode: IdentityMode,
    notification: NotificationOption,
    contactCount: Int,
    estimatedCost: String,
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
                    tint = if (isFullReset) Color(0xFFFF5252) else Color(0xFFFF9800)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isFullReset) "Confirm Wallet Reset" else "Confirm Identity Change",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                if (isFullReset) {
                    Text(
                        text = "This will PERMANENTLY delete your current wallet. Make sure you have:",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5252)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("1. Backed up your seed phrase")
                    Text("2. Transferred all ZEC to another wallet")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "You cannot undo this action!",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5252)
                    )
                } else {
                    Text("You will generate a new messaging identity.")
                    Spacer(modifier = Modifier.height(8.dp))

                    if (notification == NotificationOption.NOTIFY_ALL) {
                        Text("Contacts to notify: $contactCount")
                        Text("Estimated cost: $estimatedCost")
                    } else {
                        Text("Silent regeneration - no contacts will be notified.")
                        Text("Existing conversations will not continue.")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "You can switch back to your old identity later.",
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
                    containerColor = if (isFullReset) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isFullReset) "Reset Wallet" else "Confirm")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SuccessDialog(
    mode: IdentityMode,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Success!",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Text(
                text = if (mode == IdentityMode.FULL_RESET)
                    "Your wallet has been reset. Please set up a new wallet."
                else
                    "Your identity has been changed. Share your new address with contacts.",
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}
