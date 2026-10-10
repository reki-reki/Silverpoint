package app.silverpoint.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Silverpoint's colours: One UI's dark look (true black, dark grey cards, light text) with a
 * darkroom-safelight orange as the accent. Camera apps stay dark whatever the phone's theme is,
 * so this does not follow the system light/dark setting.
 */
object Sl {
    val Black = Color(0xFF000000)
    /** Cards and sheets (One UI's grouped lists). */
    val Card = Color(0xFF17171A)
    /** Chips and pressed rows on a card. */
    val CardHigh = Color(0xFF242428)
    val Divider = Color(0xFF2C2C31)
    val Text = Color(0xFFF2F2F5)
    val TextDim = Color(0xFFA3A3AB)
    val TextFaint = Color(0xFF6F6F77)
    /** The accent: the warm orange of a darkroom safelight lamp. */
    val Accent = Color(0xFFFF7A45)
    /** Accent-tinted background for selected rows. */
    val AccentDeep = Color(0xFF34190F)
    /** Text on an accent fill. */
    val OnAccent = Color(0xFF1C0B04)
    /** Translucent black behind controls drawn over the viewfinder. */
    val Scrim = Color(0x99000000)
    val ScrimStrong = Color(0xD90C0C0E)
    val Danger = Color(0xFFFF5D55)
    val Ok = Color(0xFF63D98A)
}

private val scheme = darkColorScheme(
    primary = Sl.Accent,
    onPrimary = Sl.OnAccent,
    primaryContainer = Sl.AccentDeep,
    onPrimaryContainer = Sl.Accent,
    secondary = Sl.TextDim,
    onSecondary = Sl.Black,
    secondaryContainer = Sl.CardHigh,
    onSecondaryContainer = Sl.Text,
    background = Sl.Black,
    onBackground = Sl.Text,
    surface = Sl.Black,
    onSurface = Sl.Text,
    surfaceVariant = Sl.CardHigh,
    onSurfaceVariant = Sl.TextDim,
    surfaceContainerLowest = Sl.Black,
    surfaceContainerLow = Sl.Card,
    surfaceContainer = Sl.Card,
    surfaceContainerHigh = Sl.CardHigh,
    surfaceContainerHighest = Sl.CardHigh,
    outline = Sl.Divider,
    outlineVariant = Sl.Divider,
    error = Sl.Danger,
)

/** One UI type: the phone's own font (SamsungOne on Samsung phones), large light-weight titles. */
private val type = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 42.sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    )
}

/** Small caps-style labels used over the viewfinder. */
val LabelCaps = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)

@Composable
fun SilverpointTheme(haptics: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type) {
        // Text takes its colour from here unless a Surface says otherwise, so it is never black on black.
        CompositionLocalProvider(LocalContentColor provides Sl.Text, LocalHapticsEnabled provides haptics, content = content)
    }
}
