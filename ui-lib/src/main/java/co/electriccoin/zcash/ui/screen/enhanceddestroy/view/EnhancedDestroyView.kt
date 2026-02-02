package co.electriccoin.zcash.ui.screen.enhanceddestroy.view

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.screen.enhanceddestroy.DestroyStep
import co.electriccoin.zcash.ui.screen.enhanceddestroy.EnhancedDestroyState

@Composable
fun EnhancedDestroyView(
    state: EnhancedDestroyState,
    modifier: Modifier = Modifier
) {
    val dangerRed = Color(0xFFFF1744)
    val dangerRedDark = Color(0xFFD50000)

    Scaffold(
        topBar = {
            if (state.currentStep != DestroyStep.DESTROYING && state.currentStep != DestroyStep.COMPLETE) {
                ZashiSmallTopAppBar(
                    title = "Emergency Destroy",
                    navigationAction = {
                        ZashiTopAppBarBackNavigation(onBack = state.onBack)
                    }
                )
            }
        }
    ) { paddingValues ->
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
            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith
                        fadeOut(animationSpec = tween(300))
                },
                label = "destroy_step_animation"
            ) { step ->
                when (step) {
                    DestroyStep.CONFIRM_INTENT -> ConfirmIntentStep(state, dangerRed)
                    DestroyStep.ENTER_PIN -> EnterPinStep(state, dangerRed)
                    DestroyStep.BIOMETRIC_VERIFY -> BiometricVerifyStep(state, dangerRed)
                    DestroyStep.GOODBYE_OPTION -> GoodbyeOptionStep(state, dangerRed)
                    DestroyStep.COUNTDOWN -> CountdownStep(state, dangerRed, dangerRedDark)
                    DestroyStep.DESTROYING -> DestroyingStep(dangerRed)
                    DestroyStep.COMPLETE -> CompleteStep(dangerRed)
                }
            }
        }
    }
}

@Composable
private fun ConfirmIntentStep(state: EnhancedDestroyState, dangerRed: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Warning icon
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(dangerRed.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = null,
                tint = dangerRed,
                modifier = Modifier.size(60.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Destroy All Data",
            style = ZashiTypography.header4,
            color = dangerRed,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "This will permanently delete:",
            style = ZashiTypography.textLg,
            color = ZashiColors.Text.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // What will be deleted
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = dangerRed.copy(alpha = 0.1f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                DestructionItem("All chat messages and history")
                DestructionItem("Your wallet and private keys")
                DestructionItem("All contacts and nicknames")
                DestructionItem("All app settings and preferences")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "This action CANNOT be undone.\nMake sure you have your seed phrase backed up!",
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textTertiary,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(24.dp))

        ZashiButton(
            modifier = Modifier.fillMaxWidth(),
            text = "I Understand, Continue",
            onClick = state.onConfirmIntent
        )

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = state.onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Cancel",
                color = ZashiColors.Text.textTertiary
            )
        }
    }
}

@Composable
private fun DestructionItem(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            tint = Color(0xFFFF1744),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textPrimary
        )
    }
}

@Composable
private fun EnterPinStep(state: EnhancedDestroyState, dangerRed: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(dangerRed.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = dangerRed,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Enter Destroy PIN",
            style = ZashiTypography.header5,
            color = ZashiColors.Text.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter your PIN to confirm destruction",
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = ZashiColors.Surfaces.bgSecondary
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = state.pinInput,
                    onValueChange = state.onPinChange,
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = state.pinError != null,
                    modifier = Modifier.fillMaxWidth()
                )

                state.pinError?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = dangerRed,
                        style = ZashiTypography.textSm
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(24.dp))

        ZashiButton(
            modifier = Modifier.fillMaxWidth(),
            text = "Verify PIN",
            onClick = state.onPinSubmit,
            enabled = state.pinInput.length >= 4
        )
    }
}

@Composable
private fun BiometricVerifyStep(state: EnhancedDestroyState, dangerRed: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(dangerRed.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Fingerprint,
                contentDescription = null,
                tint = dangerRed,
                modifier = Modifier.size(60.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Biometric Verification",
            style = ZashiTypography.header5,
            color = ZashiColors.Text.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Use your fingerprint or face to confirm",
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textSecondary,
            textAlign = TextAlign.Center
        )

        state.biometricError?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error,
                color = dangerRed,
                style = ZashiTypography.textSm,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(24.dp))

        ZashiButton(
            modifier = Modifier.fillMaxWidth(),
            text = "Authenticate",
            onClick = state.onBiometricRequest
        )

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = state.onBiometricSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Skip Biometric",
                color = ZashiColors.Text.textTertiary
            )
        }
    }
}

@Composable
private fun GoodbyeOptionStep(state: EnhancedDestroyState, dangerRed: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Send Goodbye Message?",
            style = ZashiTypography.header5,
            color = ZashiColors.Text.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Optionally notify your ${state.contactCount} contact(s) before destroying",
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = ZashiColors.Surfaces.bgSecondary
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = state.sendGoodbyeMessages,
                        onCheckedChange = state.onToggleGoodbye,
                        colors = CheckboxDefaults.colors(
                            checkedColor = dangerRed
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Send goodbye to all contacts",
                        style = ZashiTypography.textMd,
                        color = ZashiColors.Text.textPrimary
                    )
                }

                if (state.sendGoodbyeMessages) {
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = state.goodbyeMessageText,
                        onValueChange = state.onGoodbyeMessageChange,
                        label = { Text("Goodbye message") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Note: Each message costs a small network fee",
                        style = ZashiTypography.textSm,
                        color = ZashiColors.Text.textTertiary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Final warning
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = dangerRed.copy(alpha = 0.1f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = dangerRed,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "After the countdown, all data will be permanently erased",
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textPrimary
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(24.dp))

        ZashiButton(
            modifier = Modifier.fillMaxWidth(),
            text = "Start 5-Second Countdown",
            onClick = state.onStartCountdown
        )

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = state.onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Go Back",
                color = ZashiColors.Text.textTertiary
            )
        }
    }
}

@Composable
private fun CountdownStep(state: EnhancedDestroyState, dangerRed: Color, dangerRedDark: Color) {
    val progress by animateFloatAsState(
        targetValue = state.countdownSeconds / 5f,
        animationSpec = tween(900),
        label = "countdown_progress"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(200.dp)
        ) {
            // Background circle
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.size(200.dp),
                color = dangerRed.copy(alpha = 0.2f),
                strokeWidth = 12.dp
            )

            // Progress circle
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(200.dp),
                color = dangerRed,
                strokeWidth = 12.dp
            )

            // Countdown number
            Text(
                text = "${state.countdownSeconds}",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = dangerRed
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "DESTROYING IN...",
            style = ZashiTypography.header6,
            color = dangerRed,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Tap cancel to abort",
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textSecondary
        )

        Spacer(modifier = Modifier.height(48.dp))

        OutlinedButton(
            onClick = state.onCancelCountdown,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("CANCEL")
        }
    }
}

@Composable
private fun DestroyingStep(dangerRed: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(80.dp),
            color = dangerRed,
            strokeWidth = 8.dp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Destroying All Data...",
            style = ZashiTypography.header5,
            color = dangerRed,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Please wait while your data is securely erased",
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CompleteStep(dangerRed: Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(60.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Data Destroyed",
            style = ZashiTypography.header5,
            color = ZashiColors.Text.textPrimary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "All app data has been permanently erased.\nThe app will now request uninstallation.",
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}
