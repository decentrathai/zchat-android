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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.screen.enhanceddestroy.view.DestroyChoiceBlock
import co.electriccoin.zcash.ui.screen.onboarding.DestroyPinSetupUiState

/**
 * DESTROY-ADV-P (ZC-722; owner W-52; spec revision 2c, 5.3, 5.4 and 9.6): the Destroy PIN set-up in three steps on one
 * route. Step 1 says what Destroy All does (and holds the place for the AI credit line, empty by default); step 2 is
 * the choice block of "What Destroy All erases"; step 3 is the PIN. Every text is a resource. It never leads into a
 * Destroy.
 */
@Composable
fun DestroyPinSetupView(
    state: DestroyPinSetupUiState,
    onSetUp: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onToggleAdvanced: () -> Unit,
    onChooseEverything: () -> Unit,
    onChooseKeep: () -> Unit,
    onTickContacts: (Boolean) -> Unit,
    onPinChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    keepsContacts: Boolean = false,
    aiCreditLine: @Composable () -> Unit = {}
) {
    if (shouldSecureScreen) {
        SecureScreen()
    }

    Scaffold { paddingValues ->
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to ZashiColors.Surfaces.bgSecondary,
                            0.5f to ZashiColors.Surfaces.bgTertiary,
                            1f to ZashiColors.Surfaces.bgPrimary,
                        )
                    ).padding(paddingValues)
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state.step) {
                    1 -> SetupStepOne(state, keepsContacts, onSetUp, onSkip, aiCreditLine)
                    2 ->
                        SetupStepTwo(
                            state = state,
                            onContinue = onContinue,
                            onCancel = onCancel,
                            onToggleAdvanced = onToggleAdvanced,
                            onChooseEverything = onChooseEverything,
                            onChooseKeep = onChooseKeep,
                            onTickContacts = onTickContacts,
                        )
                    else -> SetupStepThree(state, onPinChange, onConfirmChange, onSave, onCancel)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/** Step 1: what Destroy All does, the place for the AI credit line, and the buttons. */
@Composable
private fun SetupStepOne(
    state: DestroyPinSetupUiState,
    keepsContacts: Boolean,
    onSetUp: () -> Unit,
    onSkip: () -> Unit,
    aiCreditLine: @Composable () -> Unit,
) {
    val danger = ZashiColors.Utility.ErrorRed.utilityError600
    Spacer(modifier = Modifier.height(32.dp))
    Box(
        modifier =
            Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(danger.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.DeleteForever,
            contentDescription = null,
            tint = danger,
            modifier = Modifier.size(48.dp)
        )
    }
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.destroy_pin_setup_title),
        style = ZashiTypography.header4,
        color = ZashiColors.Text.textPrimary,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.destroy_pin_setup_intro),
        style = ZashiTypography.textMd,
        color = ZashiColors.Text.textSecondary,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(24.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ZashiColors.Surfaces.bgSecondary),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            FeatureItem(
                icon = Icons.Default.Shield,
                title = stringResource(R.string.destroy_pin_setup_pin_title),
                description = stringResource(R.string.destroy_pin_setup_pin_body)
            )
            Spacer(modifier = Modifier.height(12.dp))
            FeatureItem(
                icon = Icons.Default.DeleteForever,
                title = stringResource(R.string.destroy_pin_setup_what_title),
                description =
                    stringResource(
                        if (keepsContacts) {
                            R.string.destroy_pin_setup_what_body_keep_contacts
                        } else {
                            R.string.destroy_pin_setup_what_body
                        }
                    )
            )
            Spacer(modifier = Modifier.height(12.dp))
            FeatureItem(
                icon = Icons.Default.Warning,
                title = stringResource(R.string.destroy_pin_setup_undo_title),
                description = stringResource(R.string.destroy_pin_setup_undo_body)
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    // ZC-692-T: what Destroy All leaves on this phone, said where the user sets it up.
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.destroy_not_erased_saved_files),
        style = ZashiTypography.textSm,
        color = ZashiColors.Text.textSecondary
    )
    // ZC-886: Destroy does not wait for an exchange (TEX) payment between its two steps.
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.destroy_tex_send_not_finished),
        style = ZashiTypography.textSm,
        color = ZashiColors.Text.textSecondary
    )
    // Spec 5.4: the place for the AI credit line (batch AI-KEY-T); empty by default, nothing drawn.
    aiCreditLine()
    // Spec 5.4, state 5: one fixed conditional sentence; no file is checked.
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.destroy_kept_ai_key_note),
        style = ZashiTypography.textSm,
        color = ZashiColors.Text.textSecondary
    )
    Spacer(modifier = Modifier.height(32.dp))
    ZashiButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(if (state.hasPin) R.string.destroy_pin_setup_change else R.string.destroy_pin_setup_start),
        onClick = onSetUp
    )
    Spacer(modifier = Modifier.height(16.dp))
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.destroy_pin_setup_skip), color = ZashiColors.Text.textTertiary)
    }
    if (!state.hasPin) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.destroy_pin_setup_optional),
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textQuaternary,
            textAlign = TextAlign.Center
        )
    }
}

/** Step 2: what Destroy All erases: the choice block of the options screen, then Continue. */
@Composable
private fun SetupStepTwo(
    state: DestroyPinSetupUiState,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onToggleAdvanced: () -> Unit,
    onChooseEverything: () -> Unit,
    onChooseKeep: () -> Unit,
    onTickContacts: (Boolean) -> Unit,
) {
    Text(
        text = stringResource(R.string.destroy_options_title),
        style = ZashiTypography.header5,
        color = ZashiColors.Text.textPrimary
    )
    Spacer(modifier = Modifier.height(16.dp))
    DestroyChoiceBlock(
        choice = state.choice,
        contactsTicked = state.contactsTicked,
        advancedOpen = state.advancedOpen,
        onToggleAdvanced = onToggleAdvanced,
        onChooseEverything = onChooseEverything,
        onChooseKeep = onChooseKeep,
        onTickContacts = onTickContacts,
    )
    Spacer(modifier = Modifier.height(24.dp))
    ZashiButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.destroy_pin_setup_continue),
        onClick = onContinue
    )
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.destroy_cancel), color = ZashiColors.Text.textTertiary)
    }
}

/** Step 3: the PIN, twice, masked; Save as soon as both fields hold something. */
@Composable
private fun SetupStepThree(
    state: DestroyPinSetupUiState,
    onPinChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ZashiColors.Surfaces.bgSecondary),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.chat_destroy_set_pin_title),
                style = ZashiTypography.textLg,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.chat_destroy_set_pin_body),
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = state.pin,
                onValueChange = onPinChange,
                label = { Text(stringResource(R.string.chat_destroy_pin_new_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = state.confirm,
                onValueChange = onConfirmChange,
                label = { Text(stringResource(R.string.chat_destroy_pin_confirm_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            state.message?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = stringResource(message), color = ZashiColors.Text.textError, style = ZashiTypography.textSm)
            }
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    ZashiButton(
        modifier = Modifier.fillMaxWidth(),
        text = stringResource(R.string.destroy_options_save),
        onClick = onSave,
        isLoading = state.saving,
        enabled = state.saveEnabled
    )
    Spacer(modifier = Modifier.height(12.dp))
    TextButton(onClick = onCancel) {
        Text(text = stringResource(R.string.destroy_cancel), color = ZashiColors.Text.textTertiary)
    }
}

@Composable
private fun FeatureItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.Top) {
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
