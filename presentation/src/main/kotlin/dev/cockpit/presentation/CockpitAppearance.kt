package dev.cockpit.presentation

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal data class CockpitPalette(
    val background: Color,
    val foreground: Color,
    val action: Color,
    val onAction: Color,
    val field: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val muted: Color,
    val border: Color,
    val positive: Color,
    val warning: Color,
    val danger: Color,
)

private val LightCockpitColors = lightColorScheme(
    primary = Color(0xFF076B52),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8F4E8),
    onPrimaryContainer = Color(0xFF073D30),
    secondary = Color(0xFF53655E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE9E3),
    onSecondaryContainer = Color(0xFF23352E),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE6A8),
    onTertiaryContainer = Color(0xFF4B3400),
    background = Color(0xFFF5F8F6),
    onBackground = Color(0xFF18201D),
    surface = Color(0xFFFBFDFC),
    onSurface = Color(0xFF18201D),
    surfaceVariant = Color(0xFFE5ECE8),
    onSurfaceVariant = Color(0xFF53605A),
    surfaceDim = Color(0xFFDCE4E0),
    surfaceBright = Color(0xFFFBFDFC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F9F7),
    surfaceContainer = Color(0xFFEFF5F2),
    surfaceContainerHigh = Color(0xFFE9F0EC),
    surfaceContainerHighest = Color(0xFFE2EAE6),
    outline = Color(0xFF75817B),
    outlineVariant = Color(0xFFD4DDD8),
    error = Color(0xFFB3261E),
)

private val DarkCockpitColors = darkColorScheme(
    primary = Color(0xFF75DDB8),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF07523E),
    onPrimaryContainer = Color(0xFFB7F2D9),
    secondary = Color(0xFFBACBC3),
    onSecondary = Color(0xFF25372F),
    secondaryContainer = Color(0xFF3B4D45),
    onSecondaryContainer = Color(0xFFD6E7DF),
    tertiary = Color(0xFFF7C84B),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF594100),
    onTertiaryContainer = Color(0xFFFFE6A8),
    background = Color(0xFF0C1411),
    onBackground = Color(0xFFE3EAE6),
    surface = Color(0xFF111B17),
    onSurface = Color(0xFFE3EAE6),
    surfaceVariant = Color(0xFF29342F),
    onSurfaceVariant = Color(0xFFBBC7C1),
    surfaceDim = Color(0xFF0C1411),
    surfaceBright = Color(0xFF303A35),
    surfaceContainerLowest = Color(0xFF08100D),
    surfaceContainerLow = Color(0xFF111B17),
    surfaceContainer = Color(0xFF16211C),
    surfaceContainerHigh = Color(0xFF202B26),
    surfaceContainerHighest = Color(0xFF2B3631),
    outline = Color(0xFF85918B),
    outlineVariant = Color(0xFF35423C),
    error = Color(0xFFFFB4AB),
)

private val CockpitTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
)

internal val LocalCockpitPalette = compositionLocalOf {
    CockpitPalette(
        background = LightCockpitColors.background,
        foreground = LightCockpitColors.onBackground,
        action = LightCockpitColors.primary,
        onAction = LightCockpitColors.onPrimary,
        field = LightCockpitColors.surfaceVariant,
        surface = LightCockpitColors.surface,
        surfaceRaised = LightCockpitColors.primaryContainer,
        muted = LightCockpitColors.onSurfaceVariant,
        border = LightCockpitColors.outlineVariant,
        positive = Color(0xFF1D7A55),
        warning = Color(0xFF9A6700),
        danger = LightCockpitColors.error,
    )
}

@Composable
internal fun CockpitAppearance(
    themePreference: CockpitThemePreference,
    content: @Composable () -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    val dark = when (themePreference) {
        CockpitThemePreference.SYSTEM -> isSystemInDarkTheme()
        CockpitThemePreference.LIGHT -> false
        CockpitThemePreference.DARK -> true
    }
    val colorScheme = if (dark) DarkCockpitColors else LightCockpitColors
    val activity = LocalContext.current.findComponentActivity()
    val palette = CockpitPalette(
        background = colorScheme.background,
        foreground = colorScheme.onBackground,
        action = colorScheme.primary,
        onAction = colorScheme.onPrimary,
        field = colorScheme.surfaceVariant,
        surface = colorScheme.surface,
        surfaceRaised = colorScheme.primaryContainer,
        muted = colorScheme.onSurfaceVariant,
        border = colorScheme.outlineVariant,
        positive = if (dark) Color(0xFF75DDB8) else Color(0xFF1D7A55),
        warning = if (dark) Color(0xFFF7C84B) else Color(0xFF8A5D00),
        danger = colorScheme.error,
    )

    SideEffect {
        val style = if (dark) {
            SystemBarStyle.dark(colorScheme.background.toArgb())
        } else {
            SystemBarStyle.light(
                colorScheme.background.toArgb(),
                colorScheme.background.toArgb(),
            )
        }
        activity?.enableEdgeToEdge(
            statusBarStyle = style,
            navigationBarStyle = style,
        )
    }

    MaterialTheme(colorScheme = colorScheme, typography = CockpitTypography) {
        CompositionLocalProvider(LocalCockpitPalette provides palette) {
            Surface(
                modifier = Modifier.semantics {
                    contentDescription = if (dark) {
                        translator.choose("Cockpit dark theme", "Cockpit 深色主题")
                    } else {
                        translator.choose("Cockpit light theme", "Cockpit 浅色主题")
                    }
                },
                color = colorScheme.background,
                content = content,
            )
        }
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

@Composable
internal fun CockpitText(text: String, modifier: Modifier = Modifier, action: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
        color = if (action) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.bodyLarge,
    )
}
