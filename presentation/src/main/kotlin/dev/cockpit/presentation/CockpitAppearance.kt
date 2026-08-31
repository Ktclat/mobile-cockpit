package dev.cockpit.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle

internal data class CockpitPalette(
    val background: Color,
    val foreground: Color,
    val action: Color,
    val field: Color,
)

internal val LocalCockpitPalette = compositionLocalOf {
    CockpitPalette(
        background = Color.White,
        foreground = Color(0xFF111418),
        action = Color(0xFF0057A8),
        field = Color(0xFFF1F3F5),
    )
}

@Composable
internal fun CockpitAppearance(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) {
        CockpitPalette(
            background = Color(0xFF101418),
            foreground = Color(0xFFF2F5F7),
            action = Color(0xFF8CC8FF),
            field = Color(0xFF252B31),
        )
    } else {
        LocalCockpitPalette.current
    }
    CompositionLocalProvider(LocalCockpitPalette provides palette) {
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.background)
                .semantics {
                    contentDescription = if (dark) "Cockpit dark theme" else "Cockpit light theme"
                },
        ) {
            content()
        }
    }
}

@Composable
internal fun CockpitText(text: String, modifier: Modifier = Modifier, action: Boolean = false) {
    val palette = LocalCockpitPalette.current
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = if (action) palette.action else palette.foreground),
    )
}
