package co.electriccoin.zcash.ui.screen.about.view

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.design.component.ZashiHorizontalDivider
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.component.ZashiVersion
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.component.listitem.ZashiListItem
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.dimensions.ZashiDimensions
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.scaffoldScrollPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.fixture.ConfigInfoFixture
import co.electriccoin.zcash.ui.fixture.VersionInfoFixture
import co.electriccoin.zcash.ui.screen.support.model.ConfigInfo
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER

@Composable
fun About(
    onBack: () -> Unit,
    configInfo: ConfigInfo,
    onPrivacyPolicy: () -> Unit,
    onTermsOfUse: () -> Unit,
    versionInfo: VersionInfo,
) {
    Scaffold(
        topBar = {
            AboutTopAppBar(
                onBack = onBack,
                versionInfo = versionInfo,
                configInfo = configInfo,
            )
        },
    ) { paddingValues ->
        AboutMainContent(
            versionInfo = versionInfo,
            onPrivacyPolicy = onPrivacyPolicy,
            onTermsOfUse = onTermsOfUse,
            modifier =
                Modifier
                    .fillMaxHeight()
                    .verticalScroll(
                        rememberScrollState()
                    ).scaffoldScrollPadding(paddingValues)
        )
    }
}

@Composable
private fun AboutTopAppBar(
    onBack: () -> Unit,
    versionInfo: VersionInfo,
    configInfo: ConfigInfo
) {
    ZashiSmallTopAppBar(
        title = stringResource(id = R.string.about_title),
        navigationAction = {
            ZashiTopAppBarBackNavigation(onBack = onBack)
        },
        regularActions = {
            if (versionInfo.isDebuggable && !versionInfo.isRunningUnderTestService) {
                DebugMenu(versionInfo, configInfo)
            }
        },
    )
}

@Composable
private fun DebugMenu(
    versionInfo: VersionInfo,
    configInfo: ConfigInfo
) {
    Column(
        modifier = Modifier.testTag(AboutTag.DEBUG_MENU_TAG)
    ) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            stringResource(
                                id = R.string.about_debug_menu_app_name,
                                stringResource(id = R.string.app_name)
                            )
                        )
                        Text(stringResource(R.string.about_debug_menu_build, versionInfo.gitSha))
                        Text(configInfo.toSupportString())
                    }
                },
                onClick = {
                    expanded = false
                }
            )
        }
    }
}

@Composable
fun AboutMainContent(
    onPrivacyPolicy: () -> Unit,
    onTermsOfUse: () -> Unit,
    versionInfo: VersionInfo,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            modifier = Modifier.padding(horizontal = ZashiDimensions.Spacing.spacing3xl),
            text = stringResource(id = R.string.about_subtitle),
            color = ZashiColors.Text.textPrimary,
            style = ZashiTypography.header6,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(12.dp))

        Text(
            modifier = Modifier.padding(horizontal = ZashiDimensions.Spacing.spacing3xl),
            text = stringResource(id = R.string.about_description),
            color = ZashiColors.Text.textPrimary,
            style = ZashiTypography.textSm
        )

        Spacer(Modifier.height(24.dp))

        // Privacy Features Section
        Text(
            modifier = Modifier.padding(horizontal = ZashiDimensions.Spacing.spacing3xl),
            text = stringResource(id = R.string.about_privacy_title),
            color = ZashiColors.Text.textPrimary,
            style = ZashiTypography.textMd,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(16.dp))

        PrivacyFeatureItem(
            title = stringResource(R.string.about_privacy_no_servers),
            description = stringResource(R.string.about_privacy_no_servers_desc),
            emoji = "🔒"
        )

        PrivacyFeatureItem(
            title = stringResource(R.string.about_privacy_no_signup),
            description = stringResource(R.string.about_privacy_no_signup_desc),
            emoji = "📱"
        )

        PrivacyFeatureItem(
            title = stringResource(R.string.about_privacy_encryption),
            description = stringResource(R.string.about_privacy_encryption_desc),
            emoji = "🛡️"
        )

        PrivacyFeatureItem(
            title = stringResource(R.string.about_privacy_no_ai),
            description = stringResource(R.string.about_privacy_no_ai_desc),
            emoji = "🚫"
        )

        PrivacyFeatureItem(
            title = stringResource(R.string.about_privacy_open_source),
            description = stringResource(R.string.about_privacy_open_source_desc),
            emoji = "👁️"
        )

        Spacer(Modifier.height(16.dp))

        Text(
            modifier = Modifier
                .padding(horizontal = ZashiDimensions.Spacing.spacing3xl)
                .fillMaxWidth(),
            text = stringResource(id = R.string.about_privacy_footer),
            color = ZashiColors.Text.textTertiary,
            style = ZashiTypography.textXs,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(24.dp))

        ZashiHorizontalDivider()

        // Quantum-Ready Privacy Section
        QuantumReadySection()

        ZashiHorizontalDivider()

        ZashiListItem(
            modifier = Modifier.padding(horizontal = 4.dp),
            state =
                ListItemState(
                    bigIcon = imageRes(R.drawable.ic_settings_info),
                    title = stringRes(R.string.about_button_privacy_policy),
                    onClick = onPrivacyPolicy
                )
        )

        ZashiHorizontalDivider()

        ZashiListItem(
            modifier = Modifier.padding(horizontal = 4.dp),
            state =
                ListItemState(
                    bigIcon = imageRes(R.drawable.ic_terms_of_use),
                    title = stringRes(stringResource(R.string.terms_of_use)),
                    onClick = onTermsOfUse
                )
        )

        Spacer(Modifier.weight(1f))

        ZashiVersion(
            modifier = Modifier.fillMaxWidth(),
            version = stringRes(R.string.settings_version, versionInfo.versionName)
        )
    }
}

@Composable
private fun PrivacyFeatureItem(
    title: String,
    description: String,
    emoji: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZashiDimensions.Spacing.spacing3xl, vertical = 8.dp)
    ) {
        Text(
            text = emoji,
            style = ZashiTypography.textMd
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = ZashiColors.Text.textPrimary,
                style = ZashiTypography.textSm,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                color = ZashiColors.Text.textTertiary,
                style = ZashiTypography.textXs
            )
        }
    }
}

/**
 * Quantum-Ready Privacy section with badge and explanation.
 */
@Composable
private fun QuantumReadySection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZashiDimensions.Spacing.spacing3xl, vertical = 16.dp)
    ) {
        // Header with Q badge
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Quantum badge icon
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .height(32.dp)
                    .width(32.dp)
                    .background(
                        color = Color(0xFF6B21A8),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Q",
                    color = Color.White,
                    style = ZashiTypography.textMd,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = stringResource(R.string.about_quantum_title),
                color = ZashiColors.Text.textPrimary,
                style = ZashiTypography.textMd,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.about_quantum_description),
            color = ZashiColors.Text.textPrimary,
            style = ZashiTypography.textSm
        )

        Spacer(Modifier.height(12.dp))

        // Feature bullets
        QuantumFeatureBullet(stringResource(R.string.about_quantum_feature_1))
        QuantumFeatureBullet(stringResource(R.string.about_quantum_feature_2))
        QuantumFeatureBullet(stringResource(R.string.about_quantum_feature_3))
    }
}

@Composable
private fun QuantumFeatureBullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "•",
            color = Color(0xFF6B21A8),
            style = ZashiTypography.textSm,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = ZashiColors.Text.textTertiary,
            style = ZashiTypography.textXs
        )
    }
}

@PreviewScreens
@Composable
private fun AboutPreview() =
    ZcashTheme {
        About(
            onBack = {},
            configInfo = ConfigInfoFixture.new(),
            onPrivacyPolicy = {},
            versionInfo = VersionInfoFixture.new(),
            onTermsOfUse = {}
        )
    }
