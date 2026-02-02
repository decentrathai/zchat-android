package co.electriccoin.zcash.ui.screen.viewingkeyexport

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.compose.shouldSecureScreen
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.component.blurCompat
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.scaffoldPadding

@Composable
fun ViewingKeyExportView(state: ViewingKeyExportState) {
    if (shouldSecureScreen) {
        SecureScreen()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            state.onSnackbarDismiss()
        }
    }

    Scaffold(
        topBar = {
            ZashiSmallTopAppBar(
                title = "Viewing Keys",
                navigationAction = {
                    ZashiTopAppBarBackNavigation(onBack = state.onBack)
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (state.isLoading) {
            CircularScreenProgressIndicator()
        } else {
            ViewingKeyExportContent(
                state = state,
                modifier = Modifier.scaffoldPadding(paddingValues)
            )
        }
    }
}

@Composable
private fun ViewingKeyExportContent(
    state: ViewingKeyExportState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Text(
            text = "Export Viewing Keys",
            style = ZashiTypography.header6,
            fontWeight = FontWeight.SemiBold,
            color = ZashiColors.Text.textPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Viewing keys let you share transaction visibility without giving spending access. Choose which key type to export based on what information you want to share.",
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Security Warning
        SecurityWarningCard()

        Spacer(modifier = Modifier.height(24.dp))

        // FVK Section (default)
        state.fvkState?.let { fvkState ->
            ViewingKeyCard(
                state = fvkState,
                isHighlighted = true
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Advanced Section Toggle
        AdvancedSectionToggle(
            isExpanded = state.showAdvanced,
            onToggle = state.onToggleAdvanced
        )

        // Advanced Keys (IVK/OVK)
        AnimatedVisibility(
            visible = state.showAdvanced,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Advanced: Selective Viewing Keys",
                    style = ZashiTypography.textMd,
                    fontWeight = FontWeight.SemiBold,
                    color = ZashiColors.Text.textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "These keys provide more granular control over what transaction information you share.",
                    style = ZashiTypography.textXs,
                    color = ZashiColors.Text.textTertiary
                )

                Spacer(modifier = Modifier.height(16.dp))

                state.ivkState?.let { ivkState ->
                    ViewingKeyCard(state = ivkState)
                }

                Spacer(modifier = Modifier.height(16.dp))

                state.ovkState?.let { ovkState ->
                    ViewingKeyCard(state = ovkState)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Educational Footer
        EducationalFooter()

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SecurityWarningCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ZashiColors.Utility.WarningYellow.utilityOrange100
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = ZashiColors.Utility.WarningYellow.utilityOrange700,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Security Notice",
                    style = ZashiTypography.textSm,
                    fontWeight = FontWeight.SemiBold,
                    color = ZashiColors.Utility.WarningYellow.utilityOrange700
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Viewing keys can reveal your transaction history. Only share them with trusted parties who need to verify your transactions.",
                    style = ZashiTypography.textXs,
                    color = ZashiColors.Utility.WarningYellow.utilityOrange700
                )
            }
        }
    }
}

@Composable
private fun ViewingKeyCard(
    state: ViewingKeyState,
    isHighlighted: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = if (isHighlighted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        ZashiColors.Text.textTertiary
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title.getValue(),
                        style = ZashiTypography.textMd,
                        fontWeight = FontWeight.SemiBold,
                        color = ZashiColors.Text.textPrimary
                    )
                    if (isHighlighted) {
                        Text(
                            text = "Recommended",
                            style = ZashiTypography.textXs,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            Text(
                text = state.description.getValue(),
                style = ZashiTypography.textXs,
                color = ZashiColors.Text.textTertiary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Key Display
            KeyDisplay(
                key = state.key,
                isRevealed = state.isRevealed,
                onRevealClick = state.onRevealClick,
                onCopyClick = state.onCopyClick
            )
        }
    }
}

@Composable
private fun KeyDisplay(
    key: String,
    isRevealed: Boolean,
    onRevealClick: () -> Unit,
    onCopyClick: () -> Unit
) {
    val blur by animateDpAsState(if (isRevealed) 0.dp else 14.dp, label = "keyBlur")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = ZashiColors.Inputs.Filled.bg
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .blurCompat(blur, 14.dp),
                    text = if (key.isNotEmpty()) key else "Key not available",
                    style = ZashiTypography.textXs.copy(fontFamily = FontFamily.Monospace),
                    color = ZashiColors.Inputs.Filled.text,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                // Reveal/Hide Button
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRevealClick),
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (isRevealed) "Hide" else "Reveal",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRevealed) "Hide" else "Reveal",
                            style = ZashiTypography.textSm,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Copy Button (only if revealed)
                if (isRevealed && key.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onCopyClick),
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Copy",
                                style = ZashiTypography.textSm,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedSectionToggle(
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Advanced Options",
                style = ZashiTypography.textMd,
                fontWeight = FontWeight.Medium,
                color = ZashiColors.Text.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = ZashiColors.Text.textTertiary
            )
        }
    }
}

@Composable
private fun EducationalFooter() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "About Viewing Keys",
                style = ZashiTypography.textSm,
                fontWeight = FontWeight.SemiBold,
                color = ZashiColors.Text.textPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Viewing keys are a powerful feature of Zcash that allow selective transparency. Unlike private keys, viewing keys cannot be used to spend funds - they only provide read access to transaction history.",
                style = ZashiTypography.textXs,
                color = ZashiColors.Text.textTertiary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Common use cases:",
                style = ZashiTypography.textXs,
                fontWeight = FontWeight.Medium,
                color = ZashiColors.Text.textPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            BulletPoint("Tax reporting and compliance")
            BulletPoint("Auditing by accountants")
            BulletPoint("Proving payments to merchants")
            BulletPoint("Portfolio tracking services")
        }
    }
}

@Composable
private fun BulletPoint(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = "•",
            style = ZashiTypography.textXs,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = ZashiTypography.textXs,
            color = ZashiColors.Text.textTertiary
        )
    }
}
