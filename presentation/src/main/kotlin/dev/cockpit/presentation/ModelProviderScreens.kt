package dev.cockpit.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cockpit.application.api.ProviderAuthenticationType
import dev.cockpit.application.api.ProviderBatchEntryInput
import dev.cockpit.application.api.ProviderBatchInput
import dev.cockpit.application.api.ProviderCredentialUpdate
import dev.cockpit.application.api.ProviderModelDiscoveryState
import dev.cockpit.application.api.ProviderModelOptionView
import dev.cockpit.application.api.ProviderModelRouteView
import dev.cockpit.application.api.ProviderOperationResult
import dev.cockpit.application.api.ProviderProbeState
import dev.cockpit.application.api.ProviderProfileInput
import dev.cockpit.application.api.ProviderProfileView
import dev.cockpit.application.api.ProviderProtocol
import dev.cockpit.application.api.ProviderSettingsPort
import dev.cockpit.application.api.ProviderSettingsSnapshot
import dev.cockpit.application.api.ProviderVendor
import java.net.URI
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class ModelProviderPreset(
    val vendor: ProviderVendor,
    val glyph: String,
    val title: String,
    val apiPrefix: String,
    val protocol: ProviderProtocol,
    val authenticationType: ProviderAuthenticationType,
    val compatibilityNote: String? = null,
)

internal val ModelProviderPresets = listOf(
    ModelProviderPreset(
        ProviderVendor.DEEPSEEK,
        "DS",
        "DeepSeek",
        "https://api.deepseek.com",
        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        ProviderAuthenticationType.BEARER,
    ),
    ModelProviderPreset(
        ProviderVendor.OPENAI,
        "AI",
        "OpenAI",
        "https://api.openai.com/v1",
        ProviderProtocol.OPENAI_RESPONSES,
        ProviderAuthenticationType.BEARER,
    ),
    ModelProviderPreset(
        ProviderVendor.GEMINI,
        "G",
        "Gemini",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        ProviderAuthenticationType.BEARER,
        "OpenAI compatibility",
    ),
    ModelProviderPreset(
        ProviderVendor.GLM,
        "GLM",
        "GLM",
        "https://open.bigmodel.cn/api/paas/v4",
        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        ProviderAuthenticationType.BEARER,
        "OpenAI compatibility",
    ),
    ModelProviderPreset(
        ProviderVendor.ANTHROPIC,
        "C",
        "Claude",
        "https://api.anthropic.com/v1",
        ProviderProtocol.ANTHROPIC_MESSAGES,
        ProviderAuthenticationType.X_API_KEY,
    ),
    ModelProviderPreset(
        ProviderVendor.CUSTOM,
        "+",
        "Custom",
        "https://",
        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        ProviderAuthenticationType.BEARER,
    ),
)

internal fun accountCountLabel(count: Int): String = when (count) {
    0 -> "No configurations"
    1 -> "1 configuration"
    else -> "$count configurations"
}

private fun ProviderVendor.modelPreset(): ModelProviderPreset =
    ModelProviderPresets.first { it.vendor == this }

private fun ModelProviderPreset.localizedTitle(t: CockpitTranslator): String =
    if (vendor == ProviderVendor.CUSTOM) t.choose("Custom", "自定义") else title

@Composable
internal fun LiveProviderSettings(
    settings: ProviderSettingsPort,
    onBack: () -> Unit,
    onOpenVendor: (ProviderVendor) -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    val snapshot by settings.observeSettings().collectAsState(ProviderSettingsSnapshot())
    val scope = rememberCoroutineScope()
    var chooseDefault by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProviderOperationResult?>(null) }
    val defaultProfile = snapshot.globalDefaultRoute?.let { route ->
        snapshot.profiles.firstOrNull { it.id == route.connectionId }
    }
    val defaultModel = snapshot.globalDefaultRoute?.let { route ->
        defaultProfile?.models?.firstOrNull { it.id == route.modelId }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = translator.choose("Models", "模型"),
            subtitle = translator.choose(
                accountCountLabel(snapshot.profiles.size),
                if (snapshot.profiles.isEmpty()) "未配置" else "${snapshot.profiles.size} 个配置",
            ),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                DefaultModelCard(
                    profile = defaultProfile,
                    model = defaultModel,
                    onClick = { chooseDefault = true },
                )
            }
            result?.let { operation ->
                item {
                    InfoBanner(
                        title = translator.choose(
                            if (operation.success) "Saved" else "Needs attention",
                            if (operation.success) "已保存" else "需要处理",
                        ),
                        body = operation.message,
                        tone = if (operation.success) StatusTone.Positive else StatusTone.Warning,
                    )
                }
            }
            item {
                SectionHeader(title = translator.choose("Providers", "服务商"))
            }
            items(ModelProviderPresets, key = { it.vendor.name }) { preset ->
                val profiles = snapshot.profiles.filter { it.vendor == preset.vendor }
                ProviderGroupCard(
                    preset = preset,
                    configurationCount = profiles.size,
                    enabledCount = profiles.count { it.enabled },
                    containsDefault = profiles.any { it.isGlobalDefault },
                    onClick = { onOpenVendor(preset.vendor) },
                )
            }
        }
    }

    if (chooseDefault) {
        DefaultModelPicker(
            snapshot = snapshot,
            onDismiss = { chooseDefault = false },
            onClear = {
                scope.launch {
                    result = settings.setGlobalDefault(null)
                    chooseDefault = false
                }
            },
            onSelect = { route ->
                scope.launch {
                    result = settings.setGlobalDefault(route)
                    if (result?.success == true) chooseDefault = false
                }
            },
        )
    }
}

@Composable
private fun DefaultModelCard(
    profile: ProviderProfileView?,
    model: ProviderModelOptionView?,
    onClick: () -> Unit,
) {
    val t = LocalCockpitTranslator.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    t.choose("Default model", "默认模型"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (profile == null || model == null) {
                        t.choose("Not set", "未设置")
                    } else {
                        "${profile.vendor.modelPreset().localizedTitle(t)} · ${profile.displayName}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    model?.remoteModelId ?: t.choose("Choose a model", "选择一个模型"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderGroupCard(
    preset: ModelProviderPreset,
    configurationCount: Int,
    enabledCount: Int,
    containsDefault: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalCockpitTranslator.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderMark(preset)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(preset.localizedTitle(t), style = MaterialTheme.typography.titleMedium)
                    if (containsDefault) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill(t.choose("Default", "默认"), tone = StatusTone.Positive)
                    }
                }
                Text(
                    t.choose(
                        if (configurationCount == 0) "Not added" else "$configurationCount configurations · $enabledCount enabled",
                        if (configurationCount == 0) "未添加" else "$configurationCount 个配置 · $enabledCount 个启用",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProviderMark(preset: ModelProviderPreset) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (preset.vendor == ProviderVendor.CUSTOM) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            } else {
                Text(preset.glyph, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DefaultModelPicker(
    snapshot: ProviderSettingsSnapshot,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSelect: (ProviderModelRouteView) -> Unit,
) {
    val t = LocalCockpitTranslator.current
    val options = snapshot.profiles.filter { it.enabled && it.credentialConfigured }.flatMap { profile ->
        profile.models.filter { it.enabled }.map { profile to it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.choose("Default model", "默认模型")) },
        text = {
            if (options.isEmpty()) {
                Text(t.choose("Enable a model in a saved configuration first.", "请先在已保存的配置中启用模型。"))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(options, key = { it.second.id }) { (profile, model) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelect(ProviderModelRouteView(profile.id, model.id))
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = snapshot.globalDefaultRoute?.modelId == model.id,
                                onClick = { onSelect(ProviderModelRouteView(profile.id, model.id)) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${profile.displayName} · ${model.displayName}")
                                Text(
                                    model.remoteModelId,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (snapshot.globalDefaultRoute != null) {
                TextButton(onClick = onClear) { Text(t.choose("Clear", "清除")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t.choose("Cancel", "取消")) } },
    )
}

@Composable
internal fun LiveProviderVendorSettings(
    settings: ProviderSettingsPort,
    vendor: ProviderVendor,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val t = LocalCockpitTranslator.current
    val preset = vendor.modelPreset()
    val snapshot by settings.observeSettings().collectAsState(ProviderSettingsSnapshot())
    val profiles = snapshot.profiles.filter { it.vendor == vendor }
    val scope = rememberCoroutineScope()
    var result by remember(vendor) { mutableStateOf<ProviderOperationResult?>(null) }
    var busyAction by remember(vendor) { mutableStateOf<String?>(null) }
    var headerMenu by remember { mutableStateOf(false) }
    var batchOpen by remember(vendor) { mutableStateOf(false) }
    var testTarget by remember { mutableStateOf<ProviderProfileView?>(null) }
    var deleteTarget by remember { mutableStateOf<ProviderProfileView?>(null) }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = preset.localizedTitle(t),
            subtitle = t.choose(
                "${profiles.size} configurations · ${profiles.count { it.enabled }} enabled",
                "${profiles.size} 个配置 · ${profiles.count { it.enabled }} 个启用",
            ),
            onBack = onBack,
        ) {
            Box {
                HeaderIconButton(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = t.choose("More actions", "更多操作"),
                    onClick = { headerMenu = true },
                )
                DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(t.choose("Batch add API keys", "批量添加 API Key")) },
                        onClick = { headerMenu = false; batchOpen = true },
                    )
                }
            }
            HeaderIconButton(
                icon = Icons.Rounded.Add,
                contentDescription = t.choose("Add configuration", "添加配置"),
                onClick = onAdd,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            result?.let { operation ->
                item {
                    InfoBanner(
                        title = t.choose(
                            if (operation.success) "Done" else "Needs attention",
                            if (operation.success) "已完成" else "需要处理",
                        ),
                        body = operation.message,
                        tone = if (operation.success) StatusTone.Positive else StatusTone.Warning,
                    )
                }
            }
            if (profiles.isEmpty()) {
                item {
                    EmptyContentCard(
                        icon = Icons.Outlined.Lock,
                        title = t.choose("No API configurations", "暂无 API 配置"),
                        actionLabel = t.choose("Add configuration", "添加配置"),
                        onAction = onAdd,
                    )
                }
            } else {
                items(profiles, key = { it.id }) { profile ->
                    ProviderConnectionCard(
                        profile = profile,
                        busy = busyAction?.endsWith(profile.id) == true,
                        onEdit = { onEdit(profile.id) },
                        onTest = { testTarget = profile },
                        onToggleEnabled = {
                            scope.launch {
                                busyAction = "toggle:${profile.id}"
                                try {
                                    result = settings.setProfileEnabled(profile.id, !profile.enabled)
                                } finally {
                                    busyAction = null
                                }
                            }
                        },
                        onDefault = {
                            val modelId = profile.preferredModelId
                            if (modelId == null) {
                                result = ProviderOperationResult(false, t.choose("Choose a preferred model first.", "请先选择首选模型。"))
                            } else {
                                scope.launch {
                                    busyAction = "default:${profile.id}"
                                    try {
                                        result = settings.setGlobalDefault(ProviderModelRouteView(profile.id, modelId))
                                    } finally {
                                        busyAction = null
                                    }
                                }
                            }
                        },
                        onCopy = { onCopy(profile.id) },
                        onDelete = { deleteTarget = profile },
                    )
                }
            }
        }
    }

    if (batchOpen) {
        BatchAddDialog(
            preset = preset,
            busy = busyAction == "batch",
            onDismiss = { if (busyAction == null) batchOpen = false },
            onSave = { batch ->
                scope.launch {
                    busyAction = "batch"
                    try {
                        val saved = settings.saveBatch(batch)
                        result = ProviderOperationResult(
                            saved.savedCount > 0,
                            t.choose(
                                "Saved ${saved.savedCount} of ${saved.items.size} configurations.",
                                "已保存 ${saved.savedCount}/${saved.items.size} 个配置。",
                            ),
                        )
                        if (saved.savedCount == saved.items.size) batchOpen = false
                    } finally {
                        busyAction = null
                    }
                }
            },
        )
    }
    testTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { if (busyAction == null) testTarget = null },
            title = { Text(t.choose("Test conversation", "测试对话")) },
            text = {
                Text(t.choose(
                    "A short request will be sent to ${profile.displayName} and may incur a small API charge.",
                    "将向 ${profile.displayName} 发送一条简短请求，可能产生少量 API 费用。",
                ))
            },
            confirmButton = {
                Button(
                    enabled = busyAction == null,
                    onClick = {
                        scope.launch {
                            busyAction = "test:${profile.id}"
                            try {
                                result = settings.probeProfile(profile.id)
                                testTarget = null
                            } finally {
                                busyAction = null
                            }
                        }
                    },
                ) { Text(t.choose("Run test", "开始测试")) }
            },
            dismissButton = {
                TextButton(onClick = { testTarget = null }, enabled = busyAction == null) {
                    Text(t.choose("Cancel", "取消"))
                }
            },
        )
    }
    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t.choose("Delete configuration?", "删除配置？")) },
            text = {
                Text(t.choose(
                    "The encrypted key will also be deleted. Referenced configurations must be reassigned first.",
                    "加密保存的 Key 也会删除；如有引用，请先更换路由。",
                ))
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        busyAction = "delete:${profile.id}"
                        try {
                            result = settings.deleteProfile(profile.id)
                            if (result?.success == true) deleteTarget = null
                        } finally {
                            busyAction = null
                        }
                    }
                }) { Text(t.choose("Delete", "删除")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(t.choose("Cancel", "取消")) }
            },
        )
    }
}

@Composable
private fun ProviderConnectionCard(
    profile: ProviderProfileView,
    busy: Boolean,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDefault: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = LocalCockpitTranslator.current
    var menuOpen by remember { mutableStateOf(false) }
    val preferred = profile.models.firstOrNull { it.id == profile.preferredModelId }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                        if (profile.isGlobalDefault) {
                            Spacer(Modifier.width(8.dp))
                            StatusPill(t.choose("Default", "默认"), tone = StatusTone.Positive)
                        }
                    }
                    Text(
                        "${endpointHost(profile.baseUrl)} · ${protocolLabel(profile.protocol, t)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = t.choose("Configuration actions", "配置操作"))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(t.choose("Edit", "编辑")) }, onClick = { menuOpen = false; onEdit() })
                            DropdownMenuItem(text = { Text(t.choose("Test conversation", "测试对话")) }, onClick = { menuOpen = false; onTest() })
                            DropdownMenuItem(text = { Text(t.choose(if (profile.enabled) "Disable" else "Enable", if (profile.enabled) "停用" else "启用")) }, onClick = { menuOpen = false; onToggleEnabled() })
                            DropdownMenuItem(text = { Text(t.choose("Set as global default", "设为全局默认")) }, onClick = { menuOpen = false; onDefault() })
                            DropdownMenuItem(text = { Text(t.choose("Copy configuration", "复制配置")) }, onClick = { menuOpen = false; onCopy() })
                            DropdownMenuItem(text = { Text(t.choose("Delete", "删除")) }, onClick = { menuOpen = false; onDelete() })
                        }
                    }
                }
            }
            Text(
                t.choose(
                    "Key: ${profile.credentialHint.ifBlank { if (profile.credentialConfigured) "saved" else "missing" }}",
                    "密钥：${profile.credentialHint.ifBlank { if (profile.credentialConfigured) "已保存" else "缺失" }}",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (profile.credentialConfigured) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Text(
                t.choose(
                    "Preferred model: ${preferred?.remoteModelId ?: "not selected"}",
                    "首选模型：${preferred?.remoteModelId ?: "未选择"}",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = testStateLabel(profile, t),
                    tone = when (profile.probeState) {
                        ProviderProbeState.AVAILABLE -> StatusTone.Positive
                        ProviderProbeState.UNAVAILABLE, ProviderProbeState.INCONCLUSIVE -> StatusTone.Warning
                        ProviderProbeState.NOT_TESTED -> StatusTone.Neutral
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (profile.enabled) t.choose("Enabled", "已启用") else t.choose("Disabled", "已停用"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BatchAddDialog(
    preset: ModelProviderPreset,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderBatchInput) -> Unit,
) {
    val t = LocalCockpitTranslator.current
    var prefix by remember { mutableStateOf(preset.localizedTitle(t)) }
    var raw by remember { mutableStateOf("") }
    val entries = remember(raw, prefix) { parseBatchEntries(raw, prefix) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.choose("Batch add · ${preset.title}", "批量添加 · ${preset.localizedTitle(t)}")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t.choose("Name prefix", "名称前缀")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 220.dp),
                    label = { Text(t.choose("One API key per line", "每行一个 API Key")) },
                    supportingText = { Text(t.choose("Optional: name | API key", "也可填写：名称 | API Key")) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (entries.isNotEmpty()) {
                    Text(t.choose("Preview", "预览"), style = MaterialTheme.typography.labelMedium)
                    entries.take(4).forEach { entry ->
                        Text(
                            "${entry.displayName}  ${maskedHint(entry.apiKey)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (entries.size > 4) Text("+${entries.size - 4}", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        ProviderBatchInput(
                            vendor = preset.vendor,
                            baseUrl = preset.apiPrefix,
                            protocol = preset.protocol,
                            authenticationType = preset.authenticationType,
                            entries = entries,
                        ),
                    )
                },
                enabled = entries.isNotEmpty() && !busy,
            ) { Text(t.choose(if (busy) "Saving…" else "Save selected", if (busy) "保存中…" else "保存所选")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(t.choose("Cancel", "取消")) }
        },
    )
}

@Composable
internal fun LiveProviderConnectionEditor(
    settings: ProviderSettingsPort,
    vendor: ProviderVendor,
    connectionId: String?,
    copyFromId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val t = LocalCockpitTranslator.current
    val preset = vendor.modelPreset()
    val snapshot by settings.observeSettings().collectAsState(ProviderSettingsSnapshot())
    val existing = connectionId?.let { id -> snapshot.profiles.firstOrNull { it.id == id } }
    val copySource = copyFromId?.let { id -> snapshot.profiles.firstOrNull { it.id == id } }
    val source = existing ?: copySource
    val scope = rememberCoroutineScope()
    var initialized by remember(connectionId, copyFromId) { mutableStateOf(false) }
    var nameWasEdited by remember(connectionId, copyFromId) { mutableStateOf(false) }
    var name by remember(connectionId, copyFromId) {
        mutableStateOf("${preset.localizedTitle(t)} ${snapshot.profiles.count { it.vendor == vendor } + 1}")
    }
    var note by remember(connectionId, copyFromId) { mutableStateOf("") }
    var apiPrefix by remember(connectionId, copyFromId) { mutableStateOf(preset.apiPrefix) }
    var protocol by remember(connectionId, copyFromId) { mutableStateOf(preset.protocol) }
    var authenticationType by remember(connectionId, copyFromId) { mutableStateOf(preset.authenticationType) }
    var key by remember(connectionId, copyFromId) { mutableStateOf("") }
    var credentialUpdate by remember(connectionId, copyFromId) {
        mutableStateOf(if (connectionId == null) ProviderCredentialUpdate.REPLACE else ProviderCredentialUpdate.KEEP)
    }
    var tokenLimit by remember(connectionId, copyFromId) { mutableStateOf("4096") }
    var enabled by remember(connectionId, copyFromId) { mutableStateOf(true) }
    var anthropicVersion by remember(connectionId, copyFromId) { mutableStateOf("2023-06-01") }
    var organizationId by remember(connectionId, copyFromId) { mutableStateOf("") }
    var projectId by remember(connectionId, copyFromId) { mutableStateOf("") }
    var workspaceId by remember(connectionId, copyFromId) { mutableStateOf("") }
    var advanced by remember(connectionId, copyFromId) { mutableStateOf(vendor == ProviderVendor.CUSTOM) }
    var busy by remember(connectionId, copyFromId) { mutableStateOf<String?>(null) }
    var result by remember(connectionId, copyFromId) { mutableStateOf<ProviderOperationResult?>(null) }
    var protocolDialog by remember { mutableStateOf(false) }
    var authDialog by remember { mutableStateOf(false) }
    var manualModelDialog by remember { mutableStateOf(false) }
    var testDialog by remember { mutableStateOf(false) }
    var hostConfirmation by remember { mutableStateOf(false) }
    var endpointConversion by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source?.id, connectionId, copyFromId) {
        if (!initialized && source != null) {
            name = if (copyFromId != null) {
                t.choose("${source.displayName} copy", "${source.displayName} 副本")
            } else {
                source.displayName
            }
            note = source.note
            apiPrefix = source.baseUrl
            protocol = source.protocol
            authenticationType = source.authenticationType
            tokenLimit = source.maxOutputTokens.toString()
            enabled = if (copyFromId != null) true else source.enabled
            anthropicVersion = source.anthropicVersion
            organizationId = source.organizationId
            projectId = source.projectId
            workspaceId = source.workspaceId
            credentialUpdate = if (copyFromId != null) ProviderCredentialUpdate.REPLACE else ProviderCredentialUpdate.KEEP
            initialized = true
        }
    }
    LaunchedEffect(snapshot.profiles.size, connectionId, copyFromId) {
        if (connectionId == null && copyFromId == null && !nameWasEdited) {
            name = "${preset.localizedTitle(t)} ${snapshot.profiles.count { it.vendor == vendor } + 1}"
        }
    }

    fun performSave(prefixOverride: String = apiPrefix) {
        val tokens = tokenLimit.toIntOrNull()
        if (name.isBlank() || apiPrefix.isBlank() || tokens == null || tokens !in 1..131_072) {
            result = ProviderOperationResult(false, t.choose("Complete the name, HTTPS API prefix, and token limit.", "请完整填写名称、HTTPS API 前缀和输出上限。"))
            return
        }
        if (connectionId == null && key.isBlank()) {
            result = ProviderOperationResult(false, t.choose("Enter an API key.", "请输入 API Key。"))
            return
        }
        scope.launch {
            busy = "save"
            try {
                val saved = settings.saveProfile(
                    ProviderProfileInput(
                        id = connectionId,
                        displayName = name,
                        vendor = vendor,
                        baseUrl = prefixOverride,
                        protocol = protocol,
                        apiKey = key,
                        credentialUpdate = credentialUpdate,
                        note = note,
                        authenticationType = authenticationType,
                        maxOutputTokens = tokens,
                        enabled = enabled,
                        anthropicVersion = anthropicVersion,
                        organizationId = organizationId,
                        projectId = projectId,
                        workspaceId = workspaceId,
                    ),
                )
                result = saved
                if (saved.success) {
                    key = ""
                    credentialUpdate = ProviderCredentialUpdate.KEEP
                    saved.profileId?.let(onSaved)
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                busy = null
            }
        }
    }

    fun requestSave() {
        val normalized = stripGenerationEndpoint(apiPrefix)
        if (normalized != apiPrefix.trim().trimEnd('/')) {
            endpointConversion = normalized
        } else if (existing != null && endpointOrigin(existing.baseUrl) != endpointOrigin(apiPrefix)) {
            hostConfirmation = true
        } else {
            performSave()
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = t.choose(if (connectionId == null) "Add configuration" else "Edit configuration", if (connectionId == null) "添加配置" else "编辑配置"),
            subtitle = preset.localizedTitle(t),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            result?.let { operation ->
                item {
                    InfoBanner(
                        title = t.choose(if (operation.success) "Done" else "Needs attention", if (operation.success) "已完成" else "需要处理"),
                        body = operation.message,
                        tone = if (operation.success) StatusTone.Positive else StatusTone.Warning,
                    )
                }
            }
            item {
                FormSection(title = t.choose("Basic information", "基本信息")) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; nameWasEdited = true; result = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t.choose("Configuration name", "配置名称")) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t.choose("Note (optional)", "备注（可选）")) },
                        minLines = 2,
                        maxLines = 3,
                    )
                }
            }
            item {
                FormSection(title = t.choose("Authentication", "认证")) {
                    if (existing != null && credentialUpdate == ProviderCredentialUpdate.KEEP) {
                        InfoBanner(
                            title = t.choose("Saved key", "已保存密钥"),
                            body = existing.credentialHint.ifBlank { t.choose("Stored securely on this device", "已安全存储在本机") },
                            tone = StatusTone.Neutral,
                        )
                    }
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            credentialUpdate = ProviderCredentialUpdate.REPLACE
                            result = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t.choose(if (existing == null) "API key" else "New API key", if (existing == null) "API Key" else "新 API Key")) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                    if (existing != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { key = ""; credentialUpdate = ProviderCredentialUpdate.KEEP }) {
                                Text(t.choose("Keep saved key", "保持原密钥"))
                            }
                            TextButton(onClick = { key = ""; credentialUpdate = ProviderCredentialUpdate.DELETE }) {
                                Text(t.choose("Remove key", "删除密钥"), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (credentialUpdate == ProviderCredentialUpdate.DELETE) {
                        Text(
                            t.choose("Saving will remove the key and disable this configuration.", "保存后会删除密钥并停用此配置。"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item {
                FormSection(title = t.choose("Connection", "连接")) {
                    SelectorRow(
                        label = t.choose("Protocol", "接口协议"),
                        value = protocolLabel(protocol, t),
                        onClick = { protocolDialog = true },
                    )
                    OutlinedTextField(
                        value = apiPrefix,
                        onValueChange = { apiPrefix = it; result = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t.choose("API prefix", "API 基础地址")) },
                        supportingText = { Text(finalEndpointPreview(apiPrefix, protocol, t)) },
                        minLines = 1,
                        maxLines = 3,
                    )
                }
            }
            if (existing == null) {
                item {
                    InfoBanner(
                        title = t.choose("Models", "模型"),
                        body = t.choose("Save first, then fetch models or add an exact model ID.", "保存后即可获取模型，或手动添加精确模型 ID。"),
                    )
                }
            } else {
                item {
                    ModelManagementSection(
                        profile = existing,
                        busy = busy,
                        onDiscover = {
                            scope.launch {
                                busy = "discover"
                                try { result = settings.discoverModels(existing.id) } finally { busy = null }
                            }
                        },
                        onManualAdd = { manualModelDialog = true },
                        onToggle = { model ->
                            scope.launch {
                                busy = "model:${model.id}"
                                try { result = settings.setModelEnabled(existing.id, model.id, !model.enabled) } finally { busy = null }
                            }
                        },
                        onPreferred = { model ->
                            scope.launch {
                                busy = "preferred:${model.id}"
                                try { result = settings.setPreferredModel(existing.id, model.id) } finally { busy = null }
                            }
                        },
                    )
                }
                if (snapshot.globalDefaultRoute == null) {
                    existing.preferredModelId?.let { preferredModelId ->
                        item {
                            InfoBanner(
                                title = t.choose("Global default is not set", "尚未设置全局默认模型"),
                                actionLabel = t.choose("Set default", "设为默认"),
                                onAction = {
                                    scope.launch {
                                        result = settings.setGlobalDefault(
                                            ProviderModelRouteView(existing.id, preferredModelId),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { advanced = !advanced }) {
                    Text(t.choose(if (advanced) "Hide advanced settings" else "Advanced settings", if (advanced) "收起高级设置" else "高级设置"))
                }
            }
            if (advanced) {
                item {
                    FormSection(title = t.choose("Advanced", "高级")) {
                        SelectorRow(
                            label = t.choose("Authentication method", "认证方式"),
                            value = authenticationLabel(authenticationType),
                            onClick = { authDialog = true },
                        )
                        if (protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
                            OutlinedTextField(
                                value = anthropicVersion,
                                onValueChange = { anthropicVersion = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Anthropic API version") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = workspaceId,
                                onValueChange = { workspaceId = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(t.choose("Workspace ID (optional)", "工作区 ID（可选）")) },
                                singleLine = true,
                            )
                        } else {
                            OutlinedTextField(
                                value = organizationId,
                                onValueChange = { organizationId = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(t.choose("Organization ID (optional)", "组织 ID（可选）")) },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = projectId,
                                onValueChange = { projectId = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(t.choose("Project ID (optional)", "项目 ID（可选）")) },
                                singleLine = true,
                            )
                        }
                        OutlinedTextField(
                            value = tokenLimit,
                            onValueChange = { tokenLimit = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(t.choose("Maximum output tokens", "最大输出 Token")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t.choose("Enable this configuration", "启用此配置"), modifier = Modifier.weight(1f))
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (existing != null) {
                    OutlinedButton(
                        onClick = { testDialog = true },
                        enabled = existing.preferredModelId != null && busy == null,
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) { Text(t.choose("Test", "测试")) }
                }
                Button(
                    onClick = ::requestSave,
                    enabled = busy == null,
                    modifier = Modifier.weight(1f).height(50.dp),
                ) {
                    if (busy == "save") {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(t.choose("Save", "保存"))
                    }
                }
            }
        }
    }

    if (protocolDialog) {
        ChoiceDialog(
            title = t.choose("Interface protocol", "接口协议"),
            options = ProviderProtocol.entries,
            selected = protocol,
            label = { protocolLabel(it, t) },
            onSelect = {
                protocol = it
                authenticationType = if (it == ProviderProtocol.ANTHROPIC_MESSAGES) ProviderAuthenticationType.X_API_KEY else ProviderAuthenticationType.BEARER
                protocolDialog = false
            },
            onDismiss = { protocolDialog = false },
        )
    }
    if (authDialog) {
        val options = if (protocol == ProviderProtocol.ANTHROPIC_MESSAGES) ProviderAuthenticationType.entries else listOf(ProviderAuthenticationType.BEARER)
        ChoiceDialog(
            title = t.choose("Authentication method", "认证方式"),
            options = options,
            selected = authenticationType,
            label = ::authenticationLabel,
            onSelect = { authenticationType = it; authDialog = false },
            onDismiss = { authDialog = false },
        )
    }
    if (manualModelDialog && existing != null) {
        ManualModelDialog(
            onDismiss = { manualModelDialog = false },
            onAdd = { remoteId, displayName ->
                scope.launch {
                    busy = "manual"
                    try {
                        result = settings.addModel(existing.id, remoteId, displayName)
                        if (result?.success == true) manualModelDialog = false
                    } finally { busy = null }
                }
            },
        )
    }
    if (testDialog && existing != null) {
        AlertDialog(
            onDismissRequest = { if (busy == null) testDialog = false },
            title = { Text(t.choose("Test conversation", "测试对话")) },
            text = { Text(t.choose("A short request may incur a small API charge.", "将发送一条简短请求，可能产生少量 API 费用。")) },
            confirmButton = {
                Button(
                    enabled = busy == null,
                    onClick = {
                        scope.launch {
                            busy = "test"
                            try {
                                result = settings.probeProfile(existing.id)
                                testDialog = false
                            } finally { busy = null }
                        }
                    },
                ) { Text(t.choose("Run test", "开始测试")) }
            },
            dismissButton = { TextButton(onClick = { testDialog = false }) { Text(t.choose("Cancel", "取消")) } },
        )
    }
    if (hostConfirmation) {
        AlertDialog(
            onDismissRequest = { hostConfirmation = false },
            icon = { Icon(Icons.Outlined.Warning, contentDescription = null) },
            title = { Text(t.choose("Send key to a new host?", "向新主机发送 Key？")) },
            text = { Text(t.choose("The saved API key will be sent to ${endpointOrigin(apiPrefix)} on future requests.", "后续请求会把已保存的 API Key 发送到 ${endpointOrigin(apiPrefix)}。")) },
            confirmButton = { Button(onClick = { hostConfirmation = false; performSave() }) { Text(t.choose("Confirm", "确认")) } },
            dismissButton = { TextButton(onClick = { hostConfirmation = false }) { Text(t.choose("Cancel", "取消")) } },
        )
    }
    endpointConversion?.let { normalized ->
        AlertDialog(
            onDismissRequest = { endpointConversion = null },
            title = { Text(t.choose("Use the API prefix?", "转换为 API 前缀？")) },
            text = {
                Text(t.choose(
                    "This is a complete generation endpoint. It will be stored as $normalized so paths are not duplicated.",
                    "当前填写的是完整生成端点。将保存为 $normalized，避免重复拼接路径。",
                ))
            },
            confirmButton = {
                Button(onClick = {
                    endpointConversion = null
                    apiPrefix = normalized
                    if (existing != null && endpointOrigin(existing.baseUrl) != endpointOrigin(normalized)) {
                        hostConfirmation = true
                    } else {
                        performSave(normalized)
                    }
                }) { Text(t.choose("Convert", "转换")) }
            },
            dismissButton = {
                TextButton(onClick = { endpointConversion = null }) { Text(t.choose("Cancel", "取消")) }
            },
        )
    }
}

@Composable
private fun ModelManagementSection(
    profile: ProviderProfileView,
    busy: String?,
    onDiscover: () -> Unit,
    onManualAdd: () -> Unit,
    onToggle: (ProviderModelOptionView) -> Unit,
    onPreferred: (ProviderModelOptionView) -> Unit,
) {
    val t = LocalCockpitTranslator.current
    FormSection(title = t.choose("Models", "模型")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDiscover, enabled = busy == null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t.choose("Fetch", "获取模型"))
            }
            OutlinedButton(onClick = onManualAdd, enabled = busy == null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t.choose("Manual", "手动添加"))
            }
        }
        if (profile.models.isEmpty()) {
            Text(
                t.choose("No models added", "尚未添加模型"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            profile.models.forEachIndexed { index, model ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                            if (model.id == profile.preferredModelId) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            model.remoteModelId,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (model.discoveryState == ProviderModelDiscoveryState.STALE) {
                            Text(t.choose("Not returned in the latest list", "最新列表未返回"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    if (model.enabled && model.id != profile.preferredModelId) {
                        TextButton(onClick = { onPreferred(model) }, enabled = busy == null) {
                            Text(t.choose("Prefer", "设为首选"))
                        }
                    }
                    Switch(checked = model.enabled, onCheckedChange = { onToggle(model) }, enabled = busy == null)
                }
            }
        }
    }
}

@Composable
private fun FormSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SelectorRow(label: String, value: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalCockpitTranslator.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(option) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option) })
                        Spacer(Modifier.width(8.dp))
                        Text(label(option))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t.choose("Cancel", "取消")) } },
    )
}

@Composable
private fun ManualModelDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    val t = LocalCockpitTranslator.current
    var remoteId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.choose("Add model", "添加模型")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = remoteId,
                    onValueChange = { remoteId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t.choose("Exact model ID", "精确模型 ID")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(t.choose("Display name (optional)", "显示名称（可选）")) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(remoteId, displayName) }, enabled = remoteId.isNotBlank()) {
                Text(t.choose("Add", "添加"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t.choose("Cancel", "取消")) } },
    )
}

private fun protocolLabel(protocol: ProviderProtocol, t: CockpitTranslator): String = when (protocol) {
    ProviderProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
    ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> "OpenAI Chat Completions"
    ProviderProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
}

private fun authenticationLabel(type: ProviderAuthenticationType): String = when (type) {
    ProviderAuthenticationType.BEARER -> "Bearer"
    ProviderAuthenticationType.X_API_KEY -> "x-api-key"
}

private fun finalEndpointPreview(prefix: String, protocol: ProviderProtocol, t: CockpitTranslator): String {
    val suffix = when (protocol) {
        ProviderProtocol.OPENAI_RESPONSES -> "responses"
        ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> "chat/completions"
        ProviderProtocol.ANTHROPIC_MESSAGES -> "messages"
    }
    return t.choose("Final endpoint: ${prefix.trimEnd('/')}/$suffix", "最终端点：${prefix.trimEnd('/')}/$suffix")
}

private fun endpointHost(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

private fun endpointOrigin(url: String): String = runCatching {
    val uri = URI(url.trim())
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    "${uri.scheme}://${uri.host}$port"
}.getOrElse { url.trim() }

private fun stripGenerationEndpoint(url: String): String {
    val normalized = url.trim().trimEnd('/')
    val suffix = listOf("/chat/completions", "/responses", "/messages")
        .firstOrNull { normalized.endsWith(it, ignoreCase = true) }
    return if (suffix == null) normalized else normalized.dropLast(suffix.length).trimEnd('/')
}

private fun testStateLabel(profile: ProviderProfileView, t: CockpitTranslator): String = when (profile.probeState) {
    ProviderProbeState.NOT_TESTED -> t.choose("Not tested", "未测试")
    ProviderProbeState.AVAILABLE -> t.choose(
        "Passed · ${formatTime(profile.lastProbedAtEpochMillis)}",
        "通过 · ${formatTime(profile.lastProbedAtEpochMillis)}",
    )
    ProviderProbeState.UNAVAILABLE -> t.choose(
        "Failed · ${formatTime(profile.lastProbedAtEpochMillis)}",
        "失败 · ${formatTime(profile.lastProbedAtEpochMillis)}",
    )
    ProviderProbeState.INCONCLUSIVE -> t.choose("Inconclusive", "结果不确定")
}

private fun formatTime(epochMillis: Long?): String = epochMillis?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
}.orEmpty()

private fun parseBatchEntries(raw: String, prefix: String): List<ProviderBatchEntryInput> =
    raw.lineSequence().map(String::trim).filter(String::isNotEmpty).mapIndexed { index, line ->
        val separator = line.indexOf('|')
        if (separator > 0) {
            ProviderBatchEntryInput(
                displayName = line.substring(0, separator).trim().ifBlank { "$prefix ${index + 1}" },
                apiKey = line.substring(separator + 1).trim(),
            )
        } else {
            ProviderBatchEntryInput("$prefix ${index + 1}", line)
        }
    }.toList()

private fun maskedHint(secret: String): String = when {
    secret.length >= 8 -> "•••• ${secret.takeLast(4)}"
    secret.length >= 4 -> "•••• ${secret.takeLast(2)}"
    else -> "••••"
}
