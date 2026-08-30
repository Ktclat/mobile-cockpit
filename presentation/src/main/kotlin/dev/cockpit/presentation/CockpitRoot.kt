package dev.cockpit.presentation

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable

@Composable
fun CockpitRoot(appName: String) {
    BasicText(text = appName)
}
