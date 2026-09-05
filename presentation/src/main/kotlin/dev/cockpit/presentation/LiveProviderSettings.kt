package dev.cockpit.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cockpit.application.api.ProviderOperationResult
import dev.cockpit.application.api.ProviderProbeState
import dev.cockpit.application.api.ProviderProfileInput
import dev.cockpit.application.api.ProviderProfileKindInput
import dev.cockpit.application.api.ProviderProfileView
import dev.cockpit.application.api.ProviderSettingsPort
import dev.cockpit.application.api.ProviderSettingsSnapshot
import dev.cockpit.application.api.ProviderVendor
import dev.cockpit.projection.model.HomeProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LiveSettingsHome(
    settings: ProviderSettingsPort,
    preferences: CockpitUiPreferences,
    onLanguageChange: (CockpitLanguagePreference) -> Unit,
    onThemeChange: (CockpitThemePreference) -> Unit,
    onOpenModels: () -> Unit,
    onOpenPrivacyAbout: () -> Unit,
) {
    val snapshot by settings.observeSettings().collectAsState(ProviderSettingsSnapshot())
    val accounts = snapshot.profiles.size
    var dialog by remember { mutableStateOf<SettingsChoice?>(null) }
    val languageLabel = when (preferences.language) {
        CockpitLanguagePreference.SYSTEM -> "Follow system"
        CockpitLanguagePreference.SIMPLIFIED_CHINESE -> "Simplified Chinese"
        CockpitLanguagePreference.ENGLISH -> "English"
    }
    val themeLabel = when (preferences.theme) {
        CockpitThemePreference.SYSTEM -> "Follow system"
        CockpitThemePreference.LIGHT -> "Light"
        CockpitThemePreference.DARK -> "Dark"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(title = "Settings")
        }
        item {
            SettingsGroup {
                SettingsDestinationRow(
                    title = "Language",
                    value = languageLabel,
                    onClick = { dialog = SettingsChoice.LANGUAGE },
                )
                SettingsDivider()
                SettingsDestinationRow(
                    title = "Theme",
                    value = themeLabel,
                    onClick = { dialog = SettingsChoice.THEME },
                )
                SettingsDivider()
                SettingsDestinationRow(
                    title = "Models",
                    value = accountCountLabel(accounts),
                    onClick = onOpenModels,
                )
                SettingsDivider()
                SettingsDestinationRow(
                    title = "Privacy & About",
                    onClick = onOpenPrivacyAbout,
                )
            }
        }
    }

    dialog?.let { choice ->
        when (choice) {
            SettingsChoice.LANGUAGE -> SettingsChoiceDialog(
                title = "Choose language",
                options = listOf(
                    "Follow system" to (preferences.language == CockpitLanguagePreference.SYSTEM),
                    "Simplified Chinese" to
                        (preferences.language == CockpitLanguagePreference.SIMPLIFIED_CHINESE),
                    "English" to (preferences.language == CockpitLanguagePreference.ENGLISH),
                ),
                onSelect = { selected ->
                    onLanguageChange(
                        when (selected) {
                            "Simplified Chinese" -> CockpitLanguagePreference.SIMPLIFIED_CHINESE
                            "English" -> CockpitLanguagePreference.ENGLISH
                            else -> CockpitLanguagePreference.SYSTEM
                        },
                    )
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
            SettingsChoice.THEME -> SettingsChoiceDialog(
                title = "Choose theme",
                options = listOf(
                    "Follow system" to (preferences.theme == CockpitThemePreference.SYSTEM),
                    "Light" to (preferences.theme == CockpitThemePreference.LIGHT),
                    "Dark" to (preferences.theme == CockpitThemePreference.DARK),
                ),
                onSelect = { selected ->
                    onThemeChange(
                        when (selected) {
                            "Light" -> CockpitThemePreference.LIGHT
                            "Dark" -> CockpitThemePreference.DARK
                            else -> CockpitThemePreference.SYSTEM
                        },
                    )
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
    }
}
private enum class SettingsChoice { LANGUAGE, THEME }

@Composable
private fun SettingsChoiceDialog(
    title: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(translator.text(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (label, selected) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(label) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { onSelect(label) })
                        Spacer(Modifier.width(8.dp))
                        Text(translator.text(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(translator.text("Cancel"))
            }
        },
    )
}

@Composable
private fun SettingsGroup(
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDestinationRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .semantics {
                contentDescription = buildString {
                    append(translator.text(title))
                    value?.let { append(", ").append(translator.text(it)) }
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = translator.text(title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        value?.let {
            Text(
                text = translator.text(it),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun LivePrivacyAboutSettings(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = "Privacy & About",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsInformationCard(
                    title = "Privacy and credentials",
                    body = "API keys are encrypted with Android Keystore. Conversations and Agent profiles remain local to this installation.",
                )
            }
            item {
                SettingsInformationCard(
                    title = "About Cockpit",
                    body = "Local-first Agent conversations with explicit model connections.",
                )
            }
        }
    }
}

@Composable
private fun SettingsInformationCard(
    title: String,
    body: String,
) {
    val translator = LocalCockpitTranslator.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(translator.text(title), style = MaterialTheme.typography.titleMedium)
            Text(
                translator.text(body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
