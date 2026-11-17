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
import co.electriccoin.zcash.ui.screen.chat.view.chatColors
import co.electriccoin.zcash.ui.screen.settings.view.LanguageSelectorDialog
import co.electriccoin.zcash.ui.screen.settings.view.ThemeSelectorDialog
import kotlinx.collections.immutable.persistentListOf

@Composable
fun MoreView(state: MoreState) {
    val cc = chatColors()

    // Show theme selector dialog when requested
    if (state.showThemeDialog) {
        ThemeSelectorDialog(
            currentTheme = state.currentTheme,
            onThemeSelected = state.onThemeSelected,
            onDismiss = state.onThemeDialogDismiss
        )
    }

    if (state.showLanguageDialog) {
        LanguageSelectorDialog(
            current = state.currentLanguage,
            onLanguageSelected = state.onLanguageSelected,
            onDismiss = state.onLanguageDialogDismiss
        )
    }

    // Security info dialog
    if (state.showSecurityDialog) {
        AlertDialog(
            onDismissRequest = state.onSecurityDialogDismiss,
            // Round 8 D9 (DECISIONS_NIGHT §4): the dialog reads its resources, so a Spanish phone reads the limits in
            // Spanish. The English values are the pinned PrivacyCopy constants (SecurityDialogResourcesTest).
            title = { Text(stringResource(R.string.security_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                // Scrolls: eleven rows and the limitations are taller than a phone screen (more so in Spanish), and the
                // limitations come last.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    co.electriccoin.zcash.ui.screen.chat.view.SECURITY_DIALOG_ROWS.forEach { (title, description) ->
                        SecurityItem(stringResource(title), stringResource(description))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.security_dialog_limitations_heading),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = cc.warning,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.security_dialog_limitations),
                        fontSize = 14.sp,
                        color = cc.textPrimary,
                        lineHeight = 18.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = state.onSecurityDialogDismiss) {
                    Text(stringResource(R.string.security_dialog_ok), color = cc.primary)
                }
            },
            containerColor = cc.background,
            titleContentColor = cc.textPrimary,
            textContentColor = cc.textPrimary,
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
            // Bottom breathing room so the version/"ZCHAT" footer isn't clipped against the edge.
            Spacer(modifier = Modifier.height(ZashiDimensions.Spacing.spacing3xl))
        }
    }
}

@Composable
private fun SecurityItem(title: String, description: String) {
    val cc = chatColors()
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cc.primary)
    Text(description, fontSize = 12.sp, color = cc.textPrimary, lineHeight = 17.sp)
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
