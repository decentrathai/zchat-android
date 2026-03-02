@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.design.theme.typography

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font as GoogleFontVariant
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.R

// Bundled Nightwire fonts (offline-first, no Google Fonts dependency)
val RajdhaniFontFamily =
    FontFamily(
        Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
        Font(R.font.rajdhani_bold, FontWeight.Bold)
    )

val JetBrainsMonoFontFamily =
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal)
    )

@Immutable
object ZashiTypographyInternal {
    val header1: TextStyle =
        TextStyle(
            fontSize = 56.sp,
            lineHeight = 68.sp,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.Bold,
        )
    val header2: TextStyle =
        TextStyle(
            fontSize = 48.sp,
            lineHeight = 60.sp,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.Bold,
        )
    val header3: TextStyle =
        TextStyle(
            fontSize = 40.sp,
            lineHeight = 52.sp,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.SemiBold,
        )
    val header4: TextStyle =
        TextStyle(
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.SemiBold,
        )
    val header5: TextStyle =
        TextStyle(
            fontSize = 28.sp,
            lineHeight = 40.sp,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.SemiBold,
        )
    val header6: TextStyle =
        TextStyle(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.SemiBold,
        )
    val text3xl: TextStyle =
        TextStyle(
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.Bold,
        )
    val text2xl: TextStyle =
        TextStyle(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontFamily = RajdhaniFontFamily,
            fontWeight = FontWeight.Bold,
        )
    val textXl: TextStyle =
        TextStyle(
            fontSize = 20.sp,
            lineHeight = 30.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textLg: TextStyle =
        TextStyle(
            fontSize = 17.sp,
            lineHeight = 26.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textMd: TextStyle =
        TextStyle(
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textSm: TextStyle =
        TextStyle(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textXs: TextStyle =
        TextStyle(
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textXxs: TextStyle =
        TextStyle(
            fontSize = 10.sp,
            lineHeight = 18.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
}

private val provider =
    GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.com_google_android_gms_fonts_certs
    )

private val InterFont = GoogleFont(name = "Inter", bestEffort = true)

private val InterFontFamily =
    FontFamily(
        // W400
        GoogleFontVariant(googleFont = InterFont, fontProvider = provider, weight = FontWeight.Normal),
        // W500
        GoogleFontVariant(googleFont = InterFont, fontProvider = provider, weight = FontWeight.Medium),
        // W600
        GoogleFontVariant(googleFont = InterFont, fontProvider = provider, weight = FontWeight.SemiBold),
        // W700
        GoogleFontVariant(googleFont = InterFont, fontProvider = provider, weight = FontWeight.Bold)
    )

// OrbitronFontFamily kept as alias for backward compatibility — maps to Rajdhani
val OrbitronFontFamily = RajdhaniFontFamily
