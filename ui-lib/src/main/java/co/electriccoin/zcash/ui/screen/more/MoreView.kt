package co.electriccoin.zcash.ui.screen.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ZashiHorizontalDivider
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.component.ZashiVersion
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.component.listitem.ZashiListItem
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.dimensions.ZashiDimensions
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.scaffoldScrollPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.settings.view.ThemeSelectorDialog
import kotlinx.collections.immutable.persistentListOf

@Composable
fun MoreView(state: MoreState) {
    // Show theme selector dialog when requested
    if (state.showThemeDialog) {
        ThemeSelectorDialog(
            currentTheme = state.currentTheme,
            onThemeSelected = state.onThemeSelected,
            onDismiss = state.onThemeDialogDismiss
        )
    }

    // Security info dialog
    if (state.showSecurityDialog) {
        AlertDialog(
            onDismissRequest = state.onSecurityDialogDismiss,
            title = { Text("What ZCHAT Protects", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    SecurityItem("Double encryption", "AES-256-GCM inside Zcash shielded transactions (Halo 2 zk-SNARKs). Attacker must break BOTH independently.")
                    SecurityItem("Forward secrecy", "Symmetric ratchet derives a unique key per message. Compromising one message key does not reveal others within the same session.")
                    SecurityItem("Replay protection", "Counter-based nonces + seen-counter tracking prevent message replay within each app session.")
                    SecurityItem("Zero metadata on-chain", "Zcash shielded transactions hide sender, receiver, and amount. Contact graph is impossible to construct from blockchain observation.")
                    SecurityItem("Safety Number", "Tap the shield icon in any E2E chat to verify your conversation is not intercepted. Compare numbers with your contact.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Known limitations:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFFFB800))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("- Quantum Shield PSK is additional entropy, not a post-quantum KEM\n- No native Tor (IP visible to lightwalletd server)\n- Keys stored in encrypted prefs, not hardware-backed Keystore\n- Multi-device with same seed will desync ratchet counters",
                        fontSize = 12.sp, color = Color(0xFF7A849B), lineHeight = 18.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = state.onSecurityDialogDismiss) {
                    Text("Got it", color = Color(0xFF00E5FF))
                }
            },
            containerColor = Color(0xFF0D1117),
            titleContentColor = Color(0xFFE8EDF5),
            textContentColor = Color(0xFFE8EDF5),
        )
    }

    BlankBgScaffold(
        topBar = {
            SettingsTopAppBar(
                onBack = state.onBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .scaffoldScrollPadding(paddingValues),
        ) {
            state.items.forEachIndexed { index, item ->
                ZashiListItem(
                    state = item,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                if (index != state.items.lastIndex) {
                    ZashiHorizontalDivider(
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(ZashiDimensions.Spacing.spacingXl))
            Spacer(modifier = Modifier.weight(1f))
            ZashiVersion(
                modifier = Modifier.fillMaxWidth(),
                version = state.version,
                onLongClick = state.onVersionLongClick,
                onDoubleClick = state.onVersionDoubleClick
            )
        }
    }
}

@Composable
private fun SecurityItem(title: String, description: String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF00E5FF))
    Text(description, fontSize = 12.sp, color = Color(0xFFE8EDF5), lineHeight = 17.sp)
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun SettingsTopAppBar(onBack: () -> Unit) {
    ZashiSmallTopAppBar(
        title = stringResource(id = R.string.settings_title),
        modifier = Modifier.testTag(MoreTags.SETTINGS_TOP_APP_BAR),
        showTitleLogo = true,
        navigationAction = {
            ZashiTopAppBarBackNavigation(onBack = onBack)
        }
    )
}

@PreviewScreens
@Composable
private fun PreviewMoreView() {
    ZcashTheme {
        MoreView(
            state =
                MoreState(
                    version = stringRes("Version 1.2"),
                    onBack = {},
                    items =
                        persistentListOf(
                            ListItemState(
                                title = stringRes(R.string.settings_address_book),
                                bigIcon = imageRes(R.drawable.ic_settings_address_book),
                                onClick = { },
                            ),
                            ListItemState(
                                title = stringRes(R.string.settings_advanced_settings),
                                bigIcon = imageRes(R.drawable.ic_advanced_settings),
                                onClick = { },
                            ),
                            ListItemState(
                                title = stringRes(R.string.settings_about_us),
                                bigIcon = imageRes(R.drawable.ic_settings_info),
                                onClick = { },
                            ),
                            ListItemState(
                                title = stringRes(R.string.settings_feedback),
                                bigIcon = imageRes(R.drawable.ic_settings_feedback),
                                onClick = { },
                            ),
                        ),
                    onVersionLongClick = {},
                    onVersionDoubleClick = {}
                ),
        )
    }
}

@PreviewScreens
@Composable
private fun IntegrationsDisabledPreview() {
    ZcashTheme {
        MoreView(
            state =
                MoreState(
                    version = stringRes("Version 1.2"),
                    onBack = {},
                    items =
                        persistentListOf(
                            ListItemState(
                                title = stringRes(R.string.settings_address_book),
                                bigIcon = imageRes(R.drawable.ic_settings_address_book),
                                onClick = { },
                            ),
                            ListItemState(
                                title = stringRes(R.string.settings_advanced_settings),
                                bigIcon = imageRes(R.drawable.ic_advanced_settings),
                                onClick = { },
                            ),
                            ListItemState(
                                title = stringRes(R.string.settings_about_us),
                                bigIcon = imageRes(R.drawable.ic_settings_info),
                                onClick = { },
                            ),
                            ListItemState(
                                title = stringRes(R.string.settings_feedback),
                                bigIcon = imageRes(R.drawable.ic_settings_feedback),
                                onClick = { },
                            ),
                        ),
                    onVersionLongClick = {},
                    onVersionDoubleClick = {}
                ),
        )
    }
}
