@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.design.theme.internal

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import co.electriccoin.zcash.ui.design.theme.ExtendedColors

internal object Dark {
    // ZCHAT New Branding - Futuristic Cyan-Green Theme
    val primaryColor = Color(0xFF0D1B2A)  // Deep navy background
    val secondaryColor = Color(0xFFFFFFFF)

    val backgroundColor = primaryColor
    val gridColor = Color(0xFF1B2838)  // Slightly lighter navy

    val textPrimary = secondaryColor
    val textSecondary = primaryColor
    val textDisabled = Color(0xFF4A5568)
    val textFieldFrame = Color(0xFF00D9FF)  // Cyan
    val textFieldWarning = Color(0xFFFF6B6B)
    val textFieldHint = Color(0xFF8892A0)
    val textDescription = Color(0xFFB0BEC5)
    val textDescriptionDark = Color(0xFFFFFFFF)
    val reference = Color(0xFFFFFFFF)

    val welcomeAnimationColor = Color(0xFF0D0B1A)  // Deep purple-black (cyberpunk)
    val complementaryColor = Color(0xFF00E676)  // Green

    val primaryDividerColor = Color(0xFF2D3E50)
    val secondaryDividerColor = Color(0xFF00D9FF)  // Cyan
    val tertiaryDividerColor = Color(0xFF2D3E50)

    val panelBackgroundColor = Color(0xFF152238)  // Dark panel
    val panelBackgroundColorActive = Color(0xFF0D1B2A)

    val layoutStroke = Color(0xFF00D9FF)  // Cyan glow
    val layoutStrokeSecondary = Color(0xFF2D3E50)
    val cameraDisabledBackgroundColor = Color(0xFF0D1B2A)
    val cameraDisabledFrameColor = Color(0xFF2D3E50)

    val primaryButtonColors = DarkPrimaryButtonColors()
    val secondaryButtonColors = DarkSecondaryButtonColors()
    val tertiaryButtonColors = DarkTertiaryButtonColors()

    val circularProgressBarSmall = Color(0xFF00D9FF)  // Cyan
    val circularProgressBarSmallDark = Color(0xFF00E676)  // Green
    val circularProgressBarScreen = Color(0xFF00D9FF)
    val linearProgressBarTrack = Color(0xFF2D3E50)
    val linearProgressBarBackground = complementaryColor

    val overlay = Color(0x44000000)
    val overlayProgressBar = Color(0xFF00D9FF)

    val historyBackgroundColor = Color(0xFF152238)
    val historyRedColor = textFieldWarning
    val historySyncingColor = Color(0xFF0D1B2A)
    val historyMessageBubbleColor = Color(0xFF00838F)  // Teal for sent messages
    val historyMessageBubbleStrokeColor = Color(0xFF00ACC1)  // Lighter teal

    val topAppBarColors = DarkTopAppBarColors()
    val transparentTopAppBarColors = TransparentTopAppBarColors()
}

internal object Light {
    val primaryColor = Color(0xFFFFFFFF)
    val secondaryColor = Color(0xFF000000)

    val backgroundColor = primaryColor
    val gridColor = Color(0xFFFBFBFB)

    val textPrimary = secondaryColor
    val textSecondary = primaryColor
    val textDisabled = Color(0xFFB7B7B7)
    val textFieldFrame = Color(0xFF000000)
    val textFieldWarning = Color(0xFFF40202)
    val textFieldHint = Color(0xFFB7B7B7)
    val textDescription = Color(0xFF777777)
    val textDescriptionDark = Color(0xFF4D4D4D)
    val reference = Color(0xFF000000)

    val welcomeAnimationColor = Color(0xFF2563EB)  // Blue-600
    val complementaryColor = Color(0xFF2563EB)  // Blue-600

    val primaryDividerColor = Color(0xFFDDDDDD)
    val secondaryDividerColor = Color(0xFF000000)
    val tertiaryDividerColor = Color(0xFF000000)

    val panelBackgroundColor = Color(0xFFEBEBEB)
    val panelBackgroundColorActive = Color(0xFFFFFFFF)

    val layoutStroke = Color(0xFF000000)
    val layoutStrokeSecondary = Color(0xFFDDDDDD)
    val cameraDisabledBackgroundColor = Color(0xFF5E5C5C)
    val cameraDisabledFrameColor = Color(0xFFFFFFFF)

    val primaryButtonColors = LightPrimaryButtonColors()
    val secondaryButtonColors = LightSecondaryButtonColors()
    val tertiaryButtonColors = LightTertiaryButtonColors()

    val circularProgressBarSmall = Color(0xFF8B8A8A)
    val circularProgressBarSmallDark = textPrimary
    val circularProgressBarScreen = Color(0xFF000000)
    val linearProgressBarTrack = Color(0xFFDDDDDD)
    val linearProgressBarBackground = complementaryColor

    val overlay = Color(0x22000000)
    val overlayProgressBar = Color(0xFFFFFFFF)

    val historyBackgroundColor = Color(0xFFF6F6F6)
    val historyRedColor = textFieldWarning
    val historySyncingColor = Color(0xFFEBEBEB)
    val historyMessageBubbleColor = Color(0xFF2563EB)  // Blue-600 for sent messages
    val historyMessageBubbleStrokeColor = Color(0xFF1D4ED8)  // Blue-700

    val topAppBarColors = LightTopAppBarColors()
    val transparentTopAppBarColors = TransparentTopAppBarColors()
}

internal val DarkColorPalette =
    darkColorScheme(
        // ZCHAT New Branding - Futuristic Cyan-Green Theme
        primary = Color(0xFF00D9FF),  // Cyan - main accent color
        onPrimary = Color(0xFF0D1B2A),  // Dark navy for text on primary
        primaryContainer = Color(0xFF006064),  // Dark teal
        onPrimaryContainer = Color(0xFF84FFFF),  // Light cyan
        secondary = Color(0xFF00E676),  // Green - secondary accent
        onSecondary = Color(0xFF0D1B2A),
        tertiary = Color(0xFF00BFA5),  // Teal
        onTertiary = Color(0xFF0D1B2A),
        surface = Color(0xFF0D1B2A),  // Deep navy background
        onSurface = Color(0xFFE0E0E0),  // Light gray text
        surfaceVariant = Color(0xFF1B2838),  // Slightly lighter navy
        onSurfaceVariant = Color(0xFFB0BEC5),  // Muted text
        background = Color(0xFF0D1B2A),  // Deep navy
        onBackground = Color(0xFFE0E0E0),
        outline = Color(0xFF2D3E50),  // Border color
        outlineVariant = Color(0xFF00D9FF),  // Cyan borders
    )

internal val LightColorPalette =
    lightColorScheme(
        // Blue-600 primary for ZCHAT branding
        primary = Color(0xFF2563EB),  // Blue-600
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDBEAFE),  // Blue-100
        onPrimaryContainer = Color(0xFF1E40AF),  // Blue-800
        secondary = Light.secondaryColor,
        onSecondary = Light.textSecondary,
        surface = Light.backgroundColor,
        onSurface = Light.textPrimary,
        surfaceVariant = Color(0xFFF1F5F9),  // Slate-100
        onSurfaceVariant = Color(0xFF475569),  // Slate-600
        background = Light.backgroundColor,
        onBackground = Light.textPrimary,
    )

internal val DarkExtendedColorPalette =
    ExtendedColors(
        primaryColor = Dark.primaryColor,
        secondaryColor = Dark.secondaryColor,
        backgroundColor = Dark.backgroundColor,
        gridColor = Dark.gridColor,
        circularProgressBarSmall = Dark.circularProgressBarSmall,
        circularProgressBarSmallDark = Dark.circularProgressBarSmallDark,
        circularProgressBarScreen = Dark.circularProgressBarScreen,
        linearProgressBarTrack = Dark.linearProgressBarTrack,
        linearProgressBarBackground = Dark.linearProgressBarBackground,
        textPrimary = Dark.textPrimary,
        textSecondary = Dark.textSecondary,
        textDisabled = Dark.textDisabled,
        textFieldFrame = Dark.textFieldFrame,
        textFieldWarning = Dark.textFieldWarning,
        textFieldHint = Dark.textFieldHint,
        textDescription = Dark.textDescription,
        textDescriptionDark = Dark.textDescriptionDark,
        layoutStroke = Dark.layoutStroke,
        layoutStrokeSecondary = Dark.layoutStrokeSecondary,
        overlay = Dark.overlay,
        overlayProgressBar = Dark.overlayProgressBar,
        reference = Dark.reference,
        welcomeAnimationColor = Dark.welcomeAnimationColor,
        complementaryColor = Dark.complementaryColor,
        primaryDividerColor = Dark.primaryDividerColor,
        secondaryDividerColor = Dark.secondaryDividerColor,
        tertiaryDividerColor = Dark.tertiaryDividerColor,
        panelBackgroundColor = Dark.panelBackgroundColor,
        panelBackgroundColorActive = Dark.panelBackgroundColorActive,
        cameraDisabledBackgroundColor = Dark.cameraDisabledBackgroundColor,
        cameraDisabledFrameColor = Dark.cameraDisabledFrameColor,
        historyBackgroundColor = Dark.historyBackgroundColor,
        historyRedColor = Dark.historyRedColor,
        historySyncingColor = Dark.historySyncingColor,
        historyMessageBubbleColor = Dark.historyMessageBubbleColor,
        historyMessageBubbleStrokeColor = Dark.historyMessageBubbleStrokeColor,
        topAppBarColors = Dark.topAppBarColors,
        transparentTopAppBarColors = Dark.transparentTopAppBarColors,
        primaryButtonColors = Dark.primaryButtonColors,
        secondaryButtonColors = Dark.secondaryButtonColors,
        tertiaryButtonColors = Dark.tertiaryButtonColors,
    )

internal val LightExtendedColorPalette =
    ExtendedColors(
        primaryColor = Light.primaryColor,
        secondaryColor = Light.secondaryColor,
        backgroundColor = Light.backgroundColor,
        gridColor = Light.gridColor,
        circularProgressBarScreen = Light.circularProgressBarScreen,
        circularProgressBarSmall = Light.circularProgressBarSmall,
        circularProgressBarSmallDark = Light.circularProgressBarSmallDark,
        linearProgressBarTrack = Light.linearProgressBarTrack,
        linearProgressBarBackground = Light.linearProgressBarBackground,
        textPrimary = Light.textPrimary,
        textSecondary = Light.textSecondary,
        textDisabled = Light.textDisabled,
        textFieldFrame = Light.textFieldFrame,
        textFieldWarning = Light.textFieldWarning,
        textFieldHint = Light.textFieldHint,
        textDescription = Light.textDescription,
        textDescriptionDark = Light.textDescriptionDark,
        layoutStroke = Light.layoutStroke,
        layoutStrokeSecondary = Light.layoutStrokeSecondary,
        overlay = Light.overlay,
        overlayProgressBar = Light.overlayProgressBar,
        reference = Light.reference,
        welcomeAnimationColor = Light.welcomeAnimationColor,
        complementaryColor = Light.complementaryColor,
        primaryDividerColor = Light.primaryDividerColor,
        secondaryDividerColor = Light.secondaryDividerColor,
        tertiaryDividerColor = Light.tertiaryDividerColor,
        panelBackgroundColor = Light.panelBackgroundColor,
        panelBackgroundColorActive = Light.panelBackgroundColorActive,
        cameraDisabledBackgroundColor = Light.cameraDisabledBackgroundColor,
        cameraDisabledFrameColor = Light.cameraDisabledFrameColor,
        historyBackgroundColor = Light.historyBackgroundColor,
        historyRedColor = Light.historyRedColor,
        historySyncingColor = Light.historySyncingColor,
        historyMessageBubbleColor = Light.historyMessageBubbleColor,
        historyMessageBubbleStrokeColor = Light.historyMessageBubbleStrokeColor,
        topAppBarColors = Light.topAppBarColors,
        transparentTopAppBarColors = Light.transparentTopAppBarColors,
        primaryButtonColors = Light.primaryButtonColors,
        secondaryButtonColors = Light.secondaryButtonColors,
        tertiaryButtonColors = Light.tertiaryButtonColors,
    )

@Suppress("CompositionLocalAllowlist")
internal val LocalExtendedColors =
    staticCompositionLocalOf {
        ExtendedColors(
            primaryColor = Color.Unspecified,
            secondaryColor = Color.Unspecified,
            backgroundColor = Color.Unspecified,
            gridColor = Color.Unspecified,
            circularProgressBarScreen = Color.Unspecified,
            circularProgressBarSmall = Color.Unspecified,
            circularProgressBarSmallDark = Color.Unspecified,
            linearProgressBarTrack = Color.Unspecified,
            linearProgressBarBackground = Color.Unspecified,
            textPrimary = Color.Unspecified,
            textSecondary = Color.Unspecified,
            textDisabled = Color.Unspecified,
            textFieldHint = Color.Unspecified,
            textFieldWarning = Color.Unspecified,
            textFieldFrame = Color.Unspecified,
            textDescription = Color.Unspecified,
            textDescriptionDark = Color.Unspecified,
            layoutStroke = Color.Unspecified,
            layoutStrokeSecondary = Color.Unspecified,
            overlay = Color.Unspecified,
            overlayProgressBar = Color.Unspecified,
            reference = Color.Unspecified,
            welcomeAnimationColor = Color.Unspecified,
            complementaryColor = Color.Unspecified,
            primaryDividerColor = Color.Unspecified,
            secondaryDividerColor = Color.Unspecified,
            tertiaryDividerColor = Color.Unspecified,
            panelBackgroundColor = Color.Unspecified,
            panelBackgroundColorActive = Color.Unspecified,
            cameraDisabledBackgroundColor = Color.Unspecified,
            cameraDisabledFrameColor = Color.Unspecified,
            historyBackgroundColor = Color.Unspecified,
            historyRedColor = Color.Unspecified,
            historySyncingColor = Color.Unspecified,
            historyMessageBubbleColor = Color.Unspecified,
            historyMessageBubbleStrokeColor = Color.Unspecified,
            topAppBarColors = DefaultTopAppBarColors(),
            transparentTopAppBarColors = DefaultTopAppBarColors(),
            primaryButtonColors = DefaultButtonColors(),
            secondaryButtonColors = DefaultButtonColors(),
            tertiaryButtonColors = DefaultButtonColors(),
        )
    }
