package co.electriccoin.zcash.ui.screen.onboarding.view

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.dimensions.ZashiDimensions
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography

/**
 * Destroy PIN Setup screen shown during onboarding.
 * Explains the emergency data wipe feature and allows users to set up a PIN.
 */
@Composable
fun DestroyPinSetupView(
    onSetupPin: (String) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pinInput by remember { mutableStateOf("") }
    var pinConfirmInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var showPinFields by remember { mutableStateOf(false) }

    Scaffold { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to ZashiColors.Surfaces.bgSecondary,
                        0.5f to ZashiColors.Surfaces.bgTertiary,
                        1f to ZashiColors.Surfaces.bgPrimary,
                    )
                )
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // Shield icon with warning
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF1744).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = Color(0xFFFF1744),
                        modifier = Modifier.size(48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Title
                Text(
                    text = "Emergency Data Wipe",
                    style = ZashiTypography.header4,
                    color = ZashiColors.Text.textPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Description
                Text(
                    text = "ZCHAT includes an emergency feature that allows you to instantly wipe all app data if needed.",
                    style = ZashiTypography.textMd,
                    color = ZashiColors.Text.textSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Feature explanation card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = ZashiColors.Surfaces.bgSecondary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        FeatureItem(
                            icon = Icons.Default.Shield,
                            title = "PIN Protection",
                            description = "Set a PIN to protect against accidental data wipe"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FeatureItem(
                            icon = Icons.Default.DeleteForever,
                            title = "Instant Wipe",
                            description = "All messages, contacts, and wallet data will be permanently deleted"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FeatureItem(
                            icon = Icons.Default.Warning,
                            title = "Unrecoverable",
                            description = "Make sure you have your backup ready before using this feature"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                if (!showPinFields) {
                    // Initial state - show setup button
                    ZashiButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = "Set Up PIN Now",
                        onClick = { showPinFields = true }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Skip for Now",
                            color = ZashiColors.Text.textTertiary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "You can set up a PIN later in Settings",
                        style = ZashiTypography.textSm,
                        color = ZashiColors.Text.textQuaternary,
                        textAlign = TextAlign.Center
                    )
                } else {
                    // PIN entry fields
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = ZashiColors.Surfaces.bgSecondary
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Create your Destroy PIN",
                                style = ZashiTypography.textLg,
                                fontWeight = FontWeight.SemiBold,
                                color = ZashiColors.Text.textPrimary
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Enter 4-8 digits. You'll need this PIN to use the emergency wipe feature.",
                                style = ZashiTypography.textSm,
                                color = ZashiColors.Text.textSecondary
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = pinInput,
                                onValueChange = {
                                    if (it.length <= 8 && it.all { c -> c.isDigit() }) {
                                        pinInput = it
                                        pinError = null
                                    }
                                },
                                label = { Text("Enter PIN") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = pinConfirmInput,
                                onValueChange = {
                                    if (it.length <= 8 && it.all { c -> c.isDigit() }) {
                                        pinConfirmInput = it
                                        pinError = null
                                    }
                                },
                                label = { Text("Confirm PIN") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier.fillMaxWidth()
                            )

                            pinError?.let { error ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = error,
                                    color = Color(0xFFFF1744),
                                    style = ZashiTypography.textSm
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    ZashiButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = "Save PIN & Continue",
                        onClick = {
                            when {
                                pinInput.length < 4 -> {
                                    pinError = "PIN must be at least 4 digits"
                                }
                                pinInput != pinConfirmInput -> {
                                    pinError = "PINs do not match"
                                }
                                else -> {
                                    onSetupPin(pinInput)
                                }
                            }
                        },
                        enabled = pinInput.length >= 4 && pinConfirmInput.isNotEmpty()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = {
                            showPinFields = false
                            pinInput = ""
                            pinConfirmInput = ""
                            pinError = null
                        }
                    ) {
                        Text(
                            text = "Cancel",
                            color = ZashiColors.Text.textTertiary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun FeatureItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ZashiColors.Text.textTertiary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = ZashiTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary
            )
            Text(
                text = description,
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textSecondary
            )
        }
    }
}
