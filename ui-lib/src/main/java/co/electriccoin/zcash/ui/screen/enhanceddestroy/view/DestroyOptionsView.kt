package co.electriccoin.zcash.ui.screen.enhanceddestroy.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.screen.enhanceddestroy.DestroyChoice
import co.electriccoin.zcash.ui.screen.enhanceddestroy.DestroyOptionsState

/**
 * DESTROY-ADV-C (ZC-722; spec revision 2c, 5.1 and 9.2): the screen "What Destroy All erases": the choice block and
 * Save (with a Destroy PIN), or the line that the choice needs a Destroy PIN and the way to set one up.
 */
@Composable
fun DestroyOptionsView(
    state: DestroyOptionsState,
    onBack: () -> Unit,
    onToggleAdvanced: () -> Unit,
    onChooseEverything: () -> Unit,
    onChooseKeep: () -> Unit,
    onTickContacts: (Boolean) -> Unit,
    onSave: () -> Unit,
    onSetPin: () -> Unit,
    onPinChange: (String) -> Unit,
    onPinConfirm: () -> Unit,
    onPinDismiss: () -> Unit,
) {
    Scaffold(
        topBar = {
            ZashiSmallTopAppBar(
                title = stringResource(R.string.destroy_options_title),
                navigationAction = { ZashiTopAppBarBackNavigation(onBack = onBack) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.hasPin) {
                DestroyChoiceBlock(
                    choice = state.choice,
                    contactsTicked = state.contactsTicked,
                    advancedOpen = state.advancedOpen,
                    onToggleAdvanced = onToggleAdvanced,
                    onChooseEverything = onChooseEverything,
                    onChooseKeep = onChooseKeep,
                    onTickContacts = onTickContacts,
                )
                if (state.existingListShown) BodyText(stringResource(R.string.destroy_options_existing_list))
                if (state.keyFailed) BodyText(stringResource(R.string.destroy_options_key_failed))
                if (state.saveFailed) BodyText(stringResource(R.string.destroy_options_save_failed))
                TextButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.destroy_options_save))
                }
            } else {
                BodyText(stringResource(R.string.destroy_options_default_body))
                BodyText(stringResource(R.string.destroy_options_needs_pin))
                TextButton(onClick = onSetPin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.destroy_options_set_pin))
                }
            }
        }
    }

    if (state.pinDialogShown) {
        AlertDialog(
            onDismissRequest = onPinDismiss,
            title = { Text(stringResource(R.string.destroy_options_auth_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.destroy_enter_pin_body))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.pinInput,
                        onValueChange = onPinChange,
                        label = { Text(stringResource(R.string.destroy_pin_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.pinError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(text = error.getValue(), color = ZashiColors.Text.textError)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onPinConfirm, enabled = state.pinInput.length >= MIN_PIN && !state.pinChecking) {
                    Text(stringResource(R.string.destroy_options_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onPinDismiss) { Text(stringResource(R.string.destroy_cancel)) }
            }
        )
    }
}

/**
 * The choice as ONE block (spec 5.1): the default sentence with its bracket, the Advanced part with the two choices
 * and the one tick, the note, the pointer to Reset ZCHAT and the sentence that nothing is asked at Destroy. It has no
 * Save of its own: this screen adds Save, and the Destroy PIN set-up draws the same block as a step.
 */
@Composable
fun DestroyChoiceBlock(
    choice: DestroyChoice,
    contactsTicked: Boolean,
    advancedOpen: Boolean,
    onToggleAdvanced: () -> Unit,
    onChooseEverything: () -> Unit,
    onChooseKeep: () -> Unit,
    onTickContacts: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BodyText(stringResource(R.string.destroy_options_default_body))
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleAdvanced),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.destroy_options_advanced),
                style = ZashiTypography.textMd,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary
            )
            Icon(imageVector = if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (advancedOpen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = choice == DestroyChoice.EVERYTHING, onClick = onChooseEverything)
                Text(stringResource(R.string.destroy_options_everything), color = ZashiColors.Text.textPrimary)
            }
            SmallText(stringResource(R.string.destroy_options_everything_list))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = choice == DestroyChoice.KEEP_SOME, onClick = onChooseKeep)
                Text(stringResource(R.string.destroy_options_keep), color = ZashiColors.Text.textPrimary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp)) {
                Checkbox(
                    checked = contactsTicked,
                    onCheckedChange = onTickContacts,
                    enabled = choice == DestroyChoice.KEEP_SOME
                )
                Text(stringResource(R.string.destroy_keep_contacts_title), color = ZashiColors.Text.textPrimary)
            }
            Column(modifier = Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallText(stringResource(R.string.destroy_keep_contacts_body))
                SmallText(stringResource(R.string.destroy_keep_contacts_after))
            }
            SmallText(stringResource(R.string.destroy_keep_note))
            SmallText(stringResource(R.string.destroy_keep_messages_hint))
        }
        BodyText(stringResource(R.string.destroy_options_asked_now))
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text = text, style = ZashiTypography.textMd, color = ZashiColors.Text.textPrimary)
}

@Composable
private fun SmallText(text: String) {
    Text(text = text, style = ZashiTypography.textSm, color = ZashiColors.Text.textTertiary)
}

private const val MIN_PIN = 4
