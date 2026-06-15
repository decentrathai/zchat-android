@file:Suppress("MagicNumber", "DEPRECATION")

package co.electriccoin.zcash.ui.screen.chat.view.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.design.theme.typography.JetBrainsMonoFontFamily
import co.electriccoin.zcash.ui.screen.chat.view.chatColors

/**
 * NIGHTWIRE Shared Components — Cypherpunk UI Kit
 */

// ─── 1. ZChatTopBar ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZChatTopBar(
    title: String,
    onBackClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val cc = chatColors()
    TopAppBar(
        title = {
            Text(
                text = title,
                fontFamily = RajdhaniFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = cc.primary,
            )
        },
        navigationIcon = {
            if (onBackClick != null) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = cc.textPrimary,
                    )
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = cc.surface,
            titleContentColor = cc.primary,
        ),
    )
}

// ─── 2. ZChatButton ─────────────────────────────────────────────────────────

enum class ZChatButtonStyle { Primary, Outlined, Danger }

@Composable
fun ZChatButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ZChatButtonStyle = ZChatButtonStyle.Primary,
    enabled: Boolean = true,
) {
    val cc = chatColors()
    // Shape constants are theme-invariant (same radius in both Nightwire variants).
    val radius = NightwireColors.RadiusButton
    when (style) {
        ZChatButtonStyle.Primary -> {
            Button(
                onClick = onClick,
                modifier = modifier
                    .shadow(
                        elevation = if (cc.isLight) 2.dp else 8.dp,
                        shape = RoundedCornerShape(radius),
                        ambientColor = cc.accentPrimaryGlow,
                        spotColor = cc.accentPrimaryGlow,
                    ),
                enabled = enabled,
                shape = RoundedCornerShape(radius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = cc.primary,
                    contentColor = cc.textOnAccent,
                    disabledContainerColor = cc.bgHover,
                    disabledContentColor = cc.textTertiary,
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = text,
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }
        ZChatButtonStyle.Outlined -> {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = RoundedCornerShape(radius),
                border = ButtonDefaults.outlinedButtonBorder(enabled),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = cc.primary,
                    disabledContentColor = cc.textTertiary,
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = text,
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }
        ZChatButtonStyle.Danger -> {
            Button(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                shape = RoundedCornerShape(radius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = cc.error,
                    contentColor = cc.textPrimary,
                    disabledContainerColor = cc.bgHover,
                    disabledContentColor = cc.textTertiary,
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = text,
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

// ─── 3. ZChatTextField ──────────────────────────────────────────────────────

@Composable
fun ZChatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val cc = chatColors()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = placeholder,
                color = cc.textTertiary,
                fontSize = 15.sp,
            )
        },
        singleLine = singleLine,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(NightwireColors.RadiusInput),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = cc.bgInput,
            unfocusedContainerColor = cc.bgInput,
            focusedTextColor = cc.textPrimary,
            unfocusedTextColor = cc.textPrimary,
            cursorColor = cc.primary,
            focusedBorderColor = cc.borderActive,
            // On light theme, an unfocused input needs a visible edge (BgInput ≈ BgSurface).
            unfocusedBorderColor = if (cc.isLight) cc.borderDefault else Color.Transparent,
        ),
    )
}

// ─── 4. UnreadBadge ─────────────────────────────────────────────────────────

@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    val cc = chatColors()
    Box(
        modifier = modifier
            .shadow(
                elevation = if (cc.isLight) 2.dp else 6.dp,
                shape = CircleShape,
                ambientColor = cc.accentSecondary,
                spotColor = cc.accentSecondary,
            )
            .background(cc.accentSecondary, CircleShape)
            .size(if (count < 10) 22.dp else 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = cc.textOnAccent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

// ─── 5. AddressText ─────────────────────────────────────────────────────────

@Composable
fun AddressText(
    address: String,
    modifier: Modifier = Modifier,
    maxLength: Int = 16,
    fontSize: Dp = 11.dp,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val truncated = if (address.length > maxLength) {
        val half = (maxLength - 3) / 2
        "${address.take(half)}...${address.takeLast(half)}"
    } else {
        address
    }
    val cc = chatColors()
    Text(
        text = truncated,
        modifier = modifier.clickable {
            clipboardManager.setText(AnnotatedString(address))
            Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
        },
        fontFamily = JetBrainsMonoFontFamily,
        fontSize = fontSize.value.sp,
        color = cc.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ─── 6. SectionHeader ───────────────────────────────────────────────────────

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    val cc = chatColors()
    Text(
        text = title.uppercase(),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        fontFamily = RajdhaniFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = cc.primary,
    )
}

// ─── 7. BottomNavBar ────────────────────────────────────────────────────────

data class BottomNavItem(
    val label: String,
    val icon: @Composable () -> Unit,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun NightwireBottomNav(
    items: List<BottomNavItem>,
    modifier: Modifier = Modifier,
) {
    val cc = chatColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(cc.surface)
            .border(
                width = 1.dp,
                color = cc.borderDefault,
                shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
            )
            .padding(vertical = 8.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Column(
                modifier = Modifier
                    .clickable(onClick = item.onClick)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item.icon()
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.label,
                    // 13sp + Bold + TextSecondary (was 11sp + SemiBold + TextTertiary)
                    // The previous combo was unreadable on dark theme — TextTertiary is ~25% contrast,
                    // borderline against the surface. Bumping size and contrast makes labels legible.
                    fontSize = 13.sp,
                    fontFamily = RajdhaniFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = if (item.selected) cc.primary else cc.textSecondary,
                )
                if (item.selected) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(3.dp)
                            .shadow(
                                elevation = if (cc.isLight) 2.dp else 6.dp,
                                shape = RoundedCornerShape(1.5.dp),
                                ambientColor = cc.accentPrimaryGlow,
                                spotColor = cc.accentPrimaryGlow,
                            )
                            .background(
                                cc.primary,
                                RoundedCornerShape(1.5.dp),
                            )
                    )
                }
            }
        }
    }
}
