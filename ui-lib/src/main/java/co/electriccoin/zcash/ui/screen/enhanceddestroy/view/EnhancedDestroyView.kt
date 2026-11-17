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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.getValue
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
            if (state.currentStep != DestroyStep.DESTROYING) {
                ZashiSmallTopAppBar(
                    title = stringResource(R.string.destroy_title),
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
                    DestroyStep.COUNTDOWN -> CountdownStep(state, dangerRed, dangerRedDark)
                    DestroyStep.DESTROYING -> DestroyingStep(dangerRed, state.keepsContacts)
                }
            }
        }
    }
}

@Composable
private fun ConfirmIntentStep(state: EnhancedDestroyState, dangerRed: Color) {
    val keepsContacts = state.keepsContacts
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
                contentDescription = stringResource(R.string.destroy_delete_all_cd),
                tint = dangerRed,
                modifier = Modifier.size(60.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.destroy_heading),
            style = ZashiTypography.header4,
            color = dangerRed,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(if (keepsContacts) R.string.destroy_will_delete_keep_contacts else R.string.destroy_will_delete),
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
                DestructionItem(stringResource(R.string.destroy_item_messages))
                DestructionItem(stringResource(R.string.destroy_item_wallet))
                DestructionItem(stringResource(R.string.destroy_item_history))
                DestructionItem(stringResource(if (keepsContacts) R.string.destroy_kept_contacts else R.string.destroy_item_contacts))
                DestructionItem(stringResource(R.string.destroy_item_ai_files))
                DestructionItem(stringResource(R.string.destroy_item_settings))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // B5: with the default choice, a list kept by an earlier Destroy is erased too.
        if (state.erasesEarlierList) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.destroy_erases_earlier_list),
                style = ZashiTypography.textMd,
                color = ZashiColors.Text.textPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // R2-7 (a): ZCHAT sends nothing to contacts; said on this one screen (DESTROY-ADV-P moved it here).
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(R.string.destroy_no_notice),
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ZC-692-T: what stays is said here, before the first tap: first what is left on this phone, then who
        // can restore, then what no app can erase. Nothing can be said after the wipe (ZC-720).
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(R.string.destroy_not_erased_saved_files),
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textPrimary,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(R.string.destroy_phrase_note),
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = ZashiColors.Surfaces.bgSecondary
                ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.destroy_stays_title),
                    style = ZashiTypography.textMd,
                    color = ZashiColors.Text.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                StaysItem(stringResource(R.string.destroy_stays_blockchain))
                StaysItem(stringResource(R.string.destroy_stays_contacts_phones))
                StaysItem(stringResource(R.string.destroy_stays_servers))
                StaysItem(stringResource(R.string.destroy_stays_services))
                StaysItem(stringResource(R.string.destroy_stays_ai))
                StaysItem(stringResource(R.string.destroy_kept_ai_key_note))
                StaysItem(stringResource(R.string.destroy_stays_backup))
                StaysItem(stringResource(R.string.destroy_stays_outside_app))
                StaysItem(stringResource(R.string.destroy_stays_android))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.destroy_cannot_be_undone),
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textTertiary,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium
        )

        // ZC-886: Destroy does not wait for an exchange (TEX) payment between its two steps.
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.destroy_tex_send_not_finished),
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textTertiary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(24.dp))

        // DESTROY-ADV-P (W-57, B3): the proof on this one screen; the countdown follows it with no step between.
        when {
            state.hasPin -> {
                OutlinedTextField(
                    value = state.pinInput,
                    onValueChange = state.onPinChange,
                    label = { Text(stringResource(R.string.destroy_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = state.pinError != null,
                    modifier = Modifier.fillMaxWidth()
                )
                state.pinError?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = error.getValue(), color = dangerRed, style = ZashiTypography.textSm)
                }
                Spacer(modifier = Modifier.height(12.dp))
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.destroy_verify_pin),
                    onClick = state.onPinSubmit,
                    enabled = state.pinInput.length >= 4
                )
            }
            state.isBiometricAvailable -> {
                state.biometricError?.let { error ->
                    Text(text = error.getValue(), color = dangerRed, style = ZashiTypography.textSm, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(12.dp))
                }
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.destroy_authenticate),
                    onClick = state.onBiometricRequest
                )
            }
            else ->
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.destroy_start_countdown),
                    onClick = state.onStartWithoutProof
                )
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = state.onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.destroy_cancel),
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
            contentDescription = stringResource(R.string.destroy_item_cd),
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

/** One line of the card "What Destroy All cannot erase": plain text, no icon (nothing here is deleted). */
@Composable
private fun StaysItem(text: String) {
    Text(
        modifier = Modifier.padding(top = 8.dp),
        text = text,
        style = ZashiTypography.textSm,
        color = ZashiColors.Text.textSecondary
    )
}

@Composable
private fun CountdownStep(state: EnhancedDestroyState, dangerRed: Color, dangerRedDark: Color) {
    val keepsContacts = state.keepsContacts
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
            text = stringResource(R.string.destroy_destroying_in),
            style = ZashiTypography.header6,
            color = dangerRed,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.destroy_tap_cancel),
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // DESTROY-ADV-P: what happens at the end of the countdown, said under it (moved here from the step that showed it before the countdown).
        Text(
            text =
                stringResource(
                    if (keepsContacts) {
                        R.string.destroy_after_countdown_keep_contacts
                    } else {
                        R.string.destroy_after_countdown
                    }
                ),
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        OutlinedButton(
            onClick = state.onCancelCountdown,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.destroy_cancel_cd),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.destroy_cancel_upper))
        }
    }
}

@Composable
private fun DestroyingStep(
    dangerRed: Color,
    keepsContacts: Boolean
) {
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
            text = stringResource(R.string.destroy_in_progress),
            style = ZashiTypography.header5,
            color = dangerRed,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(if (keepsContacts) R.string.destroy_please_wait_keep_contacts else R.string.destroy_please_wait),
            style = ZashiTypography.textMd,
            color = ZashiColors.Text.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}
