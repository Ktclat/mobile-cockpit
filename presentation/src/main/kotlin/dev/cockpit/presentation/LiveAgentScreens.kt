package dev.cockpit.presentation

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import dev.cockpit.application.api.AgentApplicationPort
import dev.cockpit.application.api.AgentConversationQueryPort
import dev.cockpit.application.api.AgentProfileInput
import dev.cockpit.application.api.AgentTestMessage
import dev.cockpit.application.api.AgentTestRole
import dev.cockpit.application.api.ConversationApplicationPort
import dev.cockpit.application.api.ProviderProfileView
import dev.cockpit.application.api.ProviderSettingsPort
import dev.cockpit.application.api.ProviderSettingsSnapshot
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.agent.AgentMode
import dev.cockpit.domain.agent.LorebookEntry
import dev.cockpit.domain.prompt.ConservativeTokenEstimator
import dev.cockpit.projection.model.ArchiveProjectionState
import dev.cockpit.projection.model.BoundProviderProjection
import dev.cockpit.projection.model.ConversationSummaryProjection
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CreateAgentScreen(
    agents: AgentApplicationPort,
    providerSettings: ProviderSettingsPort,
    onBack: () -> Boolean,
    onCreated: (AgentId) -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by providerSettings.observeSettings()
        .collectAsStateCompat(ProviderSettingsSnapshot())
    var profile by remember { mutableStateOf(AgentProfileInput(identity = "")) }
    var initialized by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(0) }
    var saveState by remember { mutableStateOf(DraftSaveState.Idle) }
    var autoSaveEnabled by remember { mutableStateOf(true) }
    var textEditor by remember { mutableStateOf<AgentTextField?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showNameError by remember { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val draft = agents.observeCreationDraft().first()
        if (draft != null) profile = draft.profile
        initialized = true
    }
    LaunchedEffect(profile, initialized, autoSaveEnabled) {
        if (!initialized || !autoSaveEnabled) return@LaunchedEffect
        delay(650)
        saveState = DraftSaveState.Saving
        saveState = if (agents.saveCreationDraft(profile)) {
            DraftSaveState.Saved
        } else {
            DraftSaveState.Failed
        }
    }

    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            avatarError = null
            runCatching {
                val bytes = readContentBytes(context, uri, 10 * 1024 * 1024)
                AgentAvatarAssets.save(context, bytes).getOrThrow()
            }.onSuccess { profile = profile.copy(avatarRef = it) }
                .onFailure { avatarError = it.message }
        }
    }

    if (!initialized) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = translator.choose("New Agent", "新建 Agent"),
            subtitle = when (saveState) {
                DraftSaveState.Saving -> translator.choose("Saving draft…", "正在保存草稿…")
                DraftSaveState.Saved -> translator.choose("Draft saved", "草稿已保存")
                DraftSaveState.Failed -> translator.choose("Draft not saved", "草稿保存失败")
                DraftSaveState.Idle -> null
            },
            onBack = {
                scope.launch {
                    agents.saveCreationDraft(profile)
                    onBack()
                }
            },
        )
        CreationProgress(step)
        when (step) {
            0 -> AgentBasicsStep(
                modifier = Modifier.weight(1f),
                profile = profile,
                providers = providers.profiles,
                nameError = showNameError,
                avatarError = avatarError,
                onProfileChange = {
                    profile = it
                    showNameError = false
                },
                onPickAvatar = { avatarLauncher.launch("image/*") },
            )
            1 -> AgentDefinitionStep(
                modifier = Modifier.weight(1f),
                profile = profile,
                onProfileChange = { profile = it },
                onEditText = { textEditor = it },
            )
            else -> AgentPreviewStep(
                modifier = Modifier.weight(1f),
                profile = profile,
                agents = agents,
            )
        }
        CreationBottomBar(
            step = step,
            creating = creating,
            canContinue = profile.identity.isNotBlank(),
            onBack = { step = (step - 1).coerceAtLeast(0) },
            onNext = {
                if (step == 0 && profile.identity.isBlank()) {
                    showNameError = true
                } else {
                    step = (step + 1).coerceAtMost(2)
                }
            },
            onCreate = {
                if (creating || profile.identity.isBlank()) return@CreationBottomBar
                scope.launch {
                    creating = true
                    autoSaveEnabled = false
                    try {
                        val id = agents.createAgent(profile)
                        if (id != null) {
                            agents.discardCreationDraft()
                            onCreated(id)
                        } else {
                            autoSaveEnabled = true
                            showNameError = true
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        autoSaveEnabled = true
                        saveState = DraftSaveState.Failed
                    } finally {
                        creating = false
                    }
                }
            },
        )
    }

    textEditor?.let { field ->
        FullScreenTextEditor(
            field = field,
            value = field.value(profile),
            onDismiss = { textEditor = null },
            onSave = { value ->
                profile = field.apply(profile, value)
                textEditor = null
            },
        )
    }
}

@Composable
private fun CreationProgress(step: Int) {
    val translator = LocalCockpitTranslator.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { index ->
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp),
                    color = if (index <= step) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(999.dp),
                ) {}
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (step) {
                0 -> translator.choose("Basics", "基本信息")
                1 -> translator.choose("Definition", "角色定义")
                else -> translator.choose("Preview", "预览")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun AgentBasicsStep(
    modifier: Modifier,
    profile: AgentProfileInput,
    providers: List<ProviderProfileView>,
    nameError: Boolean,
    avatarError: String?,
    onProfileChange: (AgentProfileInput) -> Unit,
    onPickAvatar: () -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentAvatar(
                    name = profile.identity,
                    avatarRef = profile.avatarRef,
                    large = true,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    OutlinedButton(onClick = onPickAvatar) {
                        Text(translator.choose("Choose avatar", "选择头像"))
                    }
                    if (profile.avatarRef != null) {
                        TextButton(onClick = { onProfileChange(profile.copy(avatarRef = null)) }) {
                            Text(translator.choose("Remove", "移除"))
                        }
                    }
                }
            }
            avatarError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text(
                translator.choose("Mode", "模式"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = profile.mode == AgentMode.ASSISTANT,
                    onClick = { onProfileChange(profile.copy(mode = AgentMode.ASSISTANT)) },
                    label = { Text(translator.choose("Assistant", "助理")) },
                )
                FilterChip(
                    selected = profile.mode == AgentMode.ROLEPLAY,
                    onClick = { onProfileChange(profile.copy(mode = AgentMode.ROLEPLAY)) },
                    label = { Text(translator.choose("Roleplay", "角色扮演")) },
                )
            }
        }
        item {
            OutlinedTextField(
                value = profile.identity,
                onValueChange = { onProfileChange(profile.copy(identity = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(translator.choose("Name *", "名称 *")) },
                singleLine = true,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(translator.choose("Name is required", "请输入名称")) }
                } else {
                    null
                },
                shape = RoundedCornerShape(16.dp),
            )
        }
        item {
            OutlinedTextField(
                value = profile.nickname,
                onValueChange = { onProfileChange(profile.copy(nickname = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(translator.choose("Nickname", "昵称")) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
        }
        item {
            OutlinedTextField(
                value = profile.summary,
                onValueChange = { onProfileChange(profile.copy(summary = it.take(160))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(translator.choose("Short summary", "简短介绍")) },
                minLines = 2,
                maxLines = 3,
                supportingText = { Text("${profile.summary.length}/160") },
                shape = RoundedCornerShape(16.dp),
            )
        }
        item {
            ProviderSelector(
                providers = providers,
                selectedConnectionId = profile.providerProfileId,
                selectedModelId = profile.providerModelId,
                onSelected = { connectionId, modelId ->
                    onProfileChange(
                        profile.copy(
                            providerProfileId = connectionId,
                            providerModelId = modelId,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun ProviderSelector(
    providers: List<ProviderProfileView>,
    selectedConnectionId: String?,
    selectedModelId: String?,
    onSelected: (String?, String?) -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    var open by remember { mutableStateOf(false) }
    val selectedProvider = providers.firstOrNull { it.id == selectedConnectionId }
    val selectedModel = selectedProvider?.models?.firstOrNull { it.id == selectedModelId }
    val routes = providers.filter { it.enabled && it.credentialConfigured }.flatMap { provider ->
        provider.models.filter { it.enabled }.map { provider to it }
    }
    Column {
        Text(
            translator.choose("Model", "模型"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        selectedProvider?.displayName ?: translator.choose("Follow default", "跟随默认模型"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        selectedModel?.remoteModelId
                            ?: if (selectedProvider != null) {
                                translator.choose("Model route unavailable", "模型路由不可用")
                            } else if (routes.isEmpty()) {
                                translator.choose("No enabled model route", "尚无已启用模型路由")
                            } else {
                                translator.choose("Uses the global default route", "使用全局默认路由")
                            },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(translator.choose("Follow default", "跟随默认模型")) },
                    onClick = {
                        onSelected(null, null)
                        open = false
                    },
                )
                routes.forEach { (provider, model) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(provider.displayName)
                                Text(model.remoteModelId, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            onSelected(provider.id, model.id)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentDefinitionStep(
    modifier: Modifier,
    profile: AgentProfileInput,
    onProfileChange: (AgentProfileInput) -> Unit,
    onEditText: (AgentTextField) -> Unit,
) {
    AgentDefinitionContent(
        modifier = modifier,
        profile = profile,
        onProfileChange = onProfileChange,
        onEditText = onEditText,
    )
}

@Composable
private fun AgentDefinitionContent(
    modifier: Modifier,
    profile: AgentProfileInput,
    onProfileChange: (AgentProfileInput) -> Unit,
    onEditText: (AgentTextField) -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    var advanced by remember { mutableStateOf(false) }
    var loreIndex by remember { mutableStateOf<Int?>(null) }
    var addingLore by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader(translator.choose("Character", "角色")) }
        items(
            listOf(
                AgentTextField.Description,
                AgentTextField.Personality,
                AgentTextField.Scenario,
            ),
        ) { field ->
            TextDefinitionCard(field, field.value(profile)) { onEditText(field) }
        }
        item { SectionHeader(translator.choose("Conversation", "对话")) }
        items(
            listOf(
                AgentTextField.FirstMessage,
                AgentTextField.AlternateGreetings,
                AgentTextField.ExampleDialogue,
            ),
        ) { field ->
            TextDefinitionCard(field, field.value(profile)) { onEditText(field) }
        }
        item {
            SectionHeader(
                title = translator.choose("Lorebook", "世界书"),
                actionLabel = translator.choose("Add", "添加"),
                onAction = { addingLore = true },
            )
        }
        if (profile.lorebookEntries.isEmpty()) {
            item {
                CompactEmptyRow(translator.choose("No lorebook entries", "暂无世界书条目")) {
                    addingLore = true
                }
            }
        } else {
            items(profile.lorebookEntries.indices.toList()) { index ->
                val entry = profile.lorebookEntries[index]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { loreIndex = index },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title.ifBlank { translator.choose("Untitled entry", "未命名条目") },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                translator.choose(
                                    "${entry.keywords.size} keys · ${entry.content.length} chars",
                                    "${entry.keywords.size} 个关键词 · ${entry.content.length} 字符",
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { enabled ->
                                onProfileChange(
                                    profile.copy(
                                        lorebookEntries = profile.lorebookEntries.toMutableList().also {
                                            it[index] = entry.copy(enabled = enabled)
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            TextButton(onClick = { advanced = !advanced }) {
                Text(
                    if (advanced) {
                        translator.choose("Hide advanced fields", "收起高级字段")
                    } else {
                        translator.choose("Advanced fields", "高级字段")
                    },
                )
            }
        }
        if (advanced) {
            items(
                listOf(
                    AgentTextField.SystemPrompt,
                    AgentTextField.PostHistory,
                    AgentTextField.CreatorNotes,
                ),
            ) { field ->
                TextDefinitionCard(field, field.value(profile)) { onEditText(field) }
            }
            item {
                OutlinedTextField(
                    value = profile.tags.joinToString(", "),
                    onValueChange = { value ->
                        onProfileChange(
                            profile.copy(tags = value.split(',').map(String::trim).filter(String::isNotBlank)),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(translator.choose("Tags", "标签")) },
                    shape = RoundedCornerShape(16.dp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = profile.creator,
                        onValueChange = { onProfileChange(profile.copy(creator = it)) },
                        modifier = Modifier.weight(1f),
                        label = { Text(translator.choose("Creator", "创作者")) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                    OutlinedTextField(
                        value = profile.characterVersion,
                        onValueChange = { onProfileChange(profile.copy(characterVersion = it)) },
                        modifier = Modifier.weight(1f),
                        label = { Text(translator.choose("Version", "版本")) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    val editingIndex = loreIndex
    if (addingLore || editingIndex != null) {
        val initial = editingIndex?.let { profile.lorebookEntries[it] }
        LorebookEntryDialog(
            initial = initial,
            onDismiss = {
                addingLore = false
                loreIndex = null
            },
            onSave = { saved ->
                val next = profile.lorebookEntries.toMutableList()
                if (editingIndex == null) next += saved else next[editingIndex] = saved
                onProfileChange(profile.copy(lorebookEntries = next))
                addingLore = false
                loreIndex = null
            },
            onDelete = if (editingIndex == null) null else {
                {
                    onProfileChange(
                        profile.copy(
                            lorebookEntries = profile.lorebookEntries.filterIndexed { index, _ ->
                                index != editingIndex
                            },
                        ),
                    )
                    loreIndex = null
                }
            },
        )
    }
}

@Composable
private fun TextDefinitionCard(
    field: AgentTextField,
    value: String,
    onClick: () -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(field.label(translator), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (value.isBlank()) {
                        translator.choose("Not set", "未填写")
                    } else {
                        value.replace('\n', ' ').take(90)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                translator.choose("${value.length} chars", "${value.length} 字符"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun CompactEmptyRow(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun FullScreenTextEditor(
    field: AgentTextField,
    value: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    var draft by remember(field, value) { mutableStateOf(value) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                DetailHeader(
                    title = field.label(translator),
                    subtitle = translator.choose(
                        "${draft.length} chars · about ${ConservativeTokenEstimator.estimate(draft)} tokens",
                        "${draft.length} 字符 · 约 ${ConservativeTokenEstimator.estimate(draft)} tokens",
                    ),
                    onBack = onDismiss,
                ) {
                    TextButton(onClick = { onSave(draft) }) {
                        Text(translator.choose("Done", "完成"))
                    }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    buildList {
                        add("{{char}}")
                        add("{{user}}")
                        if (field == AgentTextField.SystemPrompt || field == AgentTextField.PostHistory) {
                            add("{{original}}")
                        }
                    }.forEach { macro ->
                        TextButton(onClick = { draft += macro }) { Text(macro) }
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LorebookEntryDialog(
    initial: LorebookEntry?,
    onDismiss: () -> Unit,
    onSave: (LorebookEntry) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val translator = LocalCockpitTranslator.current
    var entry by remember(initial) {
        mutableStateOf(initial ?: LorebookEntry(id = UUID.randomUUID().toString()))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(translator.choose("Lorebook entry", "世界书条目")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = entry.title,
                    onValueChange = { entry = entry.copy(title = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(translator.choose("Title", "标题")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = entry.keywords.joinToString(", "),
                    onValueChange = { value ->
                        entry = entry.copy(
                            keywords = value.split(',').map(String::trim).filter(String::isNotBlank),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(translator.choose("Keywords", "关键词")) },
                )
                OutlinedTextField(
                    value = entry.content,
                    onValueChange = { entry = entry.copy(content = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(translator.choose("Content", "内容")) },
                    minLines = 4,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = entry.constant,
                        onCheckedChange = { entry = entry.copy(constant = it) },
                    )
                    Text(translator.choose("Always active", "始终启用"))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(entry) },
                enabled = entry.content.isNotBlank() && (entry.constant || entry.keywords.isNotEmpty()),
            ) { Text(translator.choose("Save", "保存")) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            translator.choose("Delete", "删除"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(translator.choose("Cancel", "取消")) }
            }
        },
    )
}

@Composable
private fun AgentPreviewStep(
    modifier: Modifier,
    profile: AgentProfileInput,
    agents: AgentApplicationPort,
) {
    val translator = LocalCockpitTranslator.current
    var messages by remember { mutableStateOf<List<AgentTestMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (profile.firstMessage.isNotBlank()) {
            messages = listOf(
                AgentTestMessage(
                    AgentTestRole.AGENT,
                    renderPreviewMacros(profile.firstMessage, profile),
                ),
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { previewJob?.cancel() }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(
                    Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentAvatar(profile.identity, large = true, avatarRef = profile.avatarRef)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            profile.identity,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            profile.summary.ifBlank {
                                if (profile.mode == AgentMode.ASSISTANT) {
                                    translator.choose("Assistant", "助理")
                                } else {
                                    translator.choose("Roleplay", "角色扮演")
                                }
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    if (profile.providerProfileId == null) {
                        translator.choose("Default model", "默认模型")
                    } else {
                        translator.choose("Selected model", "已选模型")
                    },
                    tone = StatusTone.Positive,
                )
                StatusPill(
                    translator.choose(
                        "~${profile.estimatedDefinitionTokens()} tokens",
                        "约 ${profile.estimatedDefinitionTokens()} tokens",
                    ),
                )
            }
        }
        profile.importSource?.let { source ->
            item {
                InfoBanner(
                    title = translator.choose("Import ready", "导入内容已就绪"),
                    body = translator.choose(
                        "${source.detectedSpec} · ${source.preservedFieldCount} extension fields preserved",
                        "${source.detectedSpec} · 保留 ${source.preservedFieldCount} 个扩展字段",
                    ),
                    tone = if (source.warnings.isEmpty()) StatusTone.Positive else StatusTone.Warning,
                )
            }
        }
        item { SectionHeader(translator.choose("Preview chat", "预览试聊")) }
        if (messages.isEmpty()) {
            item {
                Text(
                    translator.choose("Send a message to test this Agent.", "发送一条消息来试用这个 Agent。"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(messages) { message ->
            PreviewMessageBubble(message, profile.identity)
        }
        error?.let { message ->
            item { InfoBanner(translator.choose("Preview unavailable", "暂时无法试聊"), message, tone = StatusTone.Warning) }
        }
        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(translator.choose("Message", "输入消息")) },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (running) {
                    OutlinedButton(onClick = {
                        scope.launch { agents.cancelAgentTest() }
                        previewJob?.cancel()
                        running = false
                    }) {
                        Text(translator.choose("Stop", "停止"))
                    }
                } else {
                    Button(
                        onClick = {
                            val text = input.trim()
                            if (text.isEmpty()) return@Button
                            val requestMessages = messages + AgentTestMessage(AgentTestRole.USER, text)
                            messages = requestMessages
                            input = ""
                            error = null
                            running = true
                            previewJob = scope.launch {
                                try {
                                    val result = agents.testAgent(profile, requestMessages)
                                    if (result.response.isNotBlank()) {
                                        messages = messages + AgentTestMessage(
                                            AgentTestRole.AGENT,
                                            result.response,
                                        )
                                    }
                                    if (!result.success) error = result.message
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } finally {
                                    running = false
                                }
                            }
                        },
                        enabled = input.isNotBlank(),
                    ) {
                        Text(translator.choose("Send", "发送"))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PreviewMessageBubble(message: AgentTestMessage, agentName: String) {
    val translator = LocalCockpitTranslator.current
    val isUser = message.role == AgentTestRole.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = if (isUser) {
                RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
            } else {
                RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
            },
            border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (isUser) translator.choose("You", "你") else agentName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CreationBottomBar(
    step: Int,
    creating: Boolean,
    canContinue: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onCreate: () -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (step > 0) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(translator.choose("Back", "上一步"))
                }
            }
            Button(
                onClick = if (step < 2) onNext else onCreate,
                enabled = (step != 0 || canContinue) && !creating,
                modifier = Modifier.weight(1f),
            ) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        if (step < 2) {
                            translator.choose("Continue", "继续")
                        } else {
                            translator.choose("Create Agent", "创建 Agent")
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun EditAgentScreen(
    agentId: AgentId,
    agents: AgentApplicationPort,
    queries: AgentConversationQueryPort,
    providerSettings: ProviderSettingsPort,
    onBack: () -> Boolean,
) {
    val translator = LocalCockpitTranslator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val detail by queries.agent(agentId).collectAsStateCompat(null)
    val providers by providerSettings.observeSettings().collectAsStateCompat(ProviderSettingsSnapshot())
    var profile by remember(agentId) { mutableStateOf<AgentProfileInput?>(null) }
    var textEditor by remember { mutableStateOf<AgentTextField?>(null) }
    var loreIndex by remember { mutableStateOf<Int?>(null) }
    var addingLore by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    LaunchedEffect(detail) {
        if (profile == null) {
            detail?.definition?.let { definition ->
                profile = AgentProfileInput(
                    identity = definition.name,
                    mode = definition.mode,
                    summary = definition.summary,
                    avatarRef = definition.avatarRef,
                    description = definition.description,
                    personality = definition.personality,
                    scenario = definition.scenario,
                    firstMessage = definition.firstMessage,
                    alternateGreetings = definition.alternateGreetings,
                    exampleDialogue = definition.exampleDialogue,
                    systemPrompt = definition.systemPrompt,
                    postHistoryInstructions = definition.postHistoryInstructions,
                    tags = definition.tags,
                    nickname = definition.nickname,
                    creator = definition.creator,
                    characterVersion = definition.characterVersion,
                    creatorNotes = definition.creatorNotes,
                    lorebookEntries = definition.lorebookEntries,
                    lorebookScanDepth = definition.lorebookScanDepth,
                    lorebookTokenBudget = definition.lorebookTokenBudget,
                    providerProfileId = detail?.provider?.takeUnless { it.usesDefault }?.id,
                    providerModelId = detail?.provider?.takeUnless { it.usesDefault }?.modelId,
                    importSource = detail?.importSource,
                )
            }
        }
    }
    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val current = profile ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                AgentAvatarAssets.save(context, readContentBytes(context, uri, 10 * 1024 * 1024)).getOrThrow()
            }.onSuccess { profile = current.copy(avatarRef = it) }
        }
    }
    val current = profile
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = translator.choose("Edit Agent", "编辑 Agent"),
            onBack = { onBack() },
        ) {
            TextButton(
                onClick = {
                    if (saving || current.identity.isBlank()) return@TextButton
                    scope.launch {
                        saving = true
                        saveFailed = !agents.updateAgent(agentId, current)
                        saving = false
                        if (!saveFailed) onBack()
                    }
                },
                enabled = current.identity.isNotBlank() && !saving,
            ) {
                Text(translator.choose(if (saving) "Saving…" else "Save", if (saving) "保存中…" else "保存"))
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (saveFailed) {
                item { InfoBanner(translator.choose("Save failed", "保存失败"), tone = StatusTone.Warning) }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentAvatar(current.identity, large = true, avatarRef = current.avatarRef)
                    Spacer(Modifier.width(14.dp))
                    OutlinedButton(onClick = { avatarLauncher.launch("image/*") }) {
                        Text(translator.choose("Change avatar", "更换头像"))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = current.mode == AgentMode.ASSISTANT,
                        onClick = { profile = current.copy(mode = AgentMode.ASSISTANT) },
                        label = { Text(translator.choose("Assistant", "助理")) },
                    )
                    FilterChip(
                        selected = current.mode == AgentMode.ROLEPLAY,
                        onClick = { profile = current.copy(mode = AgentMode.ROLEPLAY) },
                        label = { Text(translator.choose("Roleplay", "角色扮演")) },
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = current.identity,
                    onValueChange = { profile = current.copy(identity = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(translator.choose("Name *", "名称 *")) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = current.summary,
                    onValueChange = { profile = current.copy(summary = it.take(160)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(translator.choose("Short summary", "简短介绍")) },
                    shape = RoundedCornerShape(16.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = current.nickname,
                    onValueChange = { profile = current.copy(nickname = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(translator.choose("Nickname", "昵称")) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
            }
            item {
                ProviderSelector(
                    providers = providers.profiles,
                    selectedConnectionId = current.providerProfileId,
                    selectedModelId = current.providerModelId,
                ) { connectionId, modelId ->
                    profile = current.copy(
                        providerProfileId = connectionId,
                        providerModelId = modelId,
                    )
                }
            }
            item { SectionHeader(translator.choose("Definition", "定义")) }
            items(AgentTextField.entries) { field ->
                TextDefinitionCard(field, field.value(current)) { textEditor = field }
            }
            item {
                SectionHeader(
                    title = translator.choose("Lorebook", "世界书"),
                    actionLabel = translator.choose("Add", "添加"),
                    onAction = { addingLore = true },
                )
            }
            if (current.lorebookEntries.isEmpty()) {
                item {
                    CompactEmptyRow(translator.choose("No lorebook entries", "暂无世界书条目")) {
                        addingLore = true
                    }
                }
            } else {
                items(current.lorebookEntries.indices.toList()) { index ->
                    val entry = current.lorebookEntries[index]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { loreIndex = index },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.title.ifBlank { translator.choose("Untitled entry", "未命名条目") },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    translator.choose(
                                        "${entry.keywords.size} keys",
                                        "${entry.keywords.size} 个关键词",
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = current.tags.joinToString(", "),
                    onValueChange = { value ->
                        profile = current.copy(
                            tags = value.split(',').map(String::trim).filter(String::isNotBlank),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(translator.choose("Tags", "标签")) },
                    shape = RoundedCornerShape(16.dp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = current.creator,
                        onValueChange = { profile = current.copy(creator = it) },
                        modifier = Modifier.weight(1f),
                        label = { Text(translator.choose("Creator", "创作者")) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = current.characterVersion,
                        onValueChange = { profile = current.copy(characterVersion = it) },
                        modifier = Modifier.weight(1f),
                        label = { Text(translator.choose("Version", "版本")) },
                        singleLine = true,
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
    textEditor?.let { field ->
        FullScreenTextEditor(
            field = field,
            value = field.value(current),
            onDismiss = { textEditor = null },
            onSave = {
                profile = field.apply(current, it)
                textEditor = null
            },
        )
    }
    val editingIndex = loreIndex
    if (addingLore || editingIndex != null) {
        LorebookEntryDialog(
            initial = editingIndex?.let { current.lorebookEntries[it] },
            onDismiss = {
                addingLore = false
                loreIndex = null
            },
            onSave = { saved ->
                val entries = current.lorebookEntries.toMutableList()
                if (editingIndex == null) entries += saved else entries[editingIndex] = saved
                profile = current.copy(lorebookEntries = entries)
                addingLore = false
                loreIndex = null
            },
            onDelete = editingIndex?.let { index ->
                {
                    profile = current.copy(
                        lorebookEntries = current.lorebookEntries.filterIndexed { i, _ -> i != index },
                    )
                    loreIndex = null
                }
            },
        )
    }
}

@Composable
internal fun LiveAgentDetail(
    agentId: AgentId,
    queries: AgentConversationQueryPort,
    agents: AgentApplicationPort,
    actions: ConversationApplicationPort,
    navigation: NavHostController,
) {
    val translator = LocalCockpitTranslator.current
    val context = LocalContext.current
    val detail by queries.agent(agentId).collectAsStateCompat(null)
    val current = detail ?: return LiveEmptyState("Agent is not available")
    val scope = rememberCoroutineScope()
    var creatingConversation by remember(current.id) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<dev.cockpit.application.api.AgentExportDocument?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val document = pendingExport
        if (uri != null && document != null) {
            scope.launch {
                exportMessage = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(document.json.toByteArray(Charsets.UTF_8))
                        } ?: error("Unable to open the selected location.")
                    }
                    document.warning
                }.getOrElse { it.message ?: "Export failed." }
            }
        }
    }
    val active = current.conversations.filter { it.archiveState == ArchiveProjectionState.ACTIVE }
    val archived = current.conversations.filter { it.archiveState == ArchiveProjectionState.ARCHIVED }
    val createConversation = {
        if (!creatingConversation && current.archiveState == ArchiveProjectionState.ACTIVE) {
            scope.launch {
                creatingConversation = true
                try {
                    actions.createConversation(current.id)?.let {
                        navigation.navigate(LiveRoute.conversation(it.value))
                    }
                } finally {
                    creatingConversation = false
                }
            }
        }
        Unit
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(title = current.name, onBack = { navigation.navigateUp() }) {
            HeaderIconButton(
                icon = Icons.Rounded.AddCircle,
                contentDescription = "New Conversation",
                onClick = createConversation,
                enabled = !creatingConversation && current.archiveState == ArchiveProjectionState.ACTIVE,
            )
            Box {
                HeaderIconButton(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = "More options",
                    onClick = { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(translator.choose("Edit", "编辑")) },
                        onClick = {
                            menuOpen = false
                            navigation.navigate(LiveRoute.editAgent(agentId.value))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(translator.choose("Export character card", "导出角色卡")) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                pendingExport = agents.exportAgent(agentId)
                                pendingExport?.let { exportLauncher.launch(it.fileName) }
                            }
                        },
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AgentAvatar(
                        current.name,
                        avatarRef = current.definition?.avatarRef,
                        large = true,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(current.name, style = MaterialTheme.typography.titleLarge)
                        current.definition?.summary?.takeIf(String::isNotBlank)?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            exportMessage?.let { message ->
                item {
                    InfoBanner(
                        translator.choose("Export complete", "导出完成"),
                        message,
                        tone = StatusTone.Positive,
                    )
                }
            }
            item { SectionHeader(translator.choose("Conversations", "对话")) }
            if (active.isEmpty()) {
                item {
                    EmptyContentCard(
                        icon = Icons.Filled.Person,
                        title = translator.choose("No conversations", "暂无对话"),
                        actionLabel = translator.choose("New conversation", "新建对话"),
                        onAction = createConversation,
                    )
                }
            } else {
                items(active, key = { it.id.value }) { conversation ->
                    ConversationSummaryCard(
                        label = conversation.visibleLabel(),
                        archived = false,
                        onOpen = { navigation.navigate(LiveRoute.conversation(conversation.id.value)) },
                    )
                }
            }
            item { SectionHeader(translator.choose("Configuration", "配置")) }
            item {
                AgentConfigurationCard(
                    provider = current.provider,
                    revision = current.revision,
                    mode = current.definition?.mode ?: AgentMode.ASSISTANT,
                    imported = current.importSource != null,
                    onOpenModels = { navigation.navigate(LiveRoute.Models) },
                )
            }
            if (archived.isNotEmpty()) {
                item { SectionHeader(translator.choose("Archived", "已归档")) }
                items(archived, key = { it.id.value }) { conversation ->
                    ConversationSummaryCard(
                        label = conversation.visibleLabel(),
                        archived = true,
                        onOpen = {},
                        onRestore = {
                            scope.launch {
                                if (actions.restoreConversation(conversation.id)) {
                                    navigation.navigate(LiveRoute.conversation(conversation.id.value))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentConfigurationCard(
    provider: BoundProviderProjection?,
    revision: Long,
    mode: AgentMode,
    imported: Boolean,
    onOpenModels: () -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            ConfigurationRow(
                translator.choose("Mode", "模式"),
                if (mode == AgentMode.ASSISTANT) translator.choose("Assistant", "助理")
                else translator.choose("Roleplay", "角色扮演"),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ConfigurationRow(
                translator.choose("Model", "模型"),
                provider?.let {
                    if (it.usesDefault) {
                        translator.choose("Default · ${it.displayName}", "默认 · ${it.displayName}")
                    } else {
                        it.displayName
                    }
                } ?: translator.choose("Not configured", "未配置"),
                onClick = onOpenModels,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ConfigurationRow(
                translator.choose("Revision", "修订版本"),
                revision.toString(),
            )
            if (imported) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ConfigurationRow(translator.choose("Source", "来源"), translator.choose("Character card", "角色卡"))
            }
        }
    }
}

@Composable
private fun ConfigurationRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

internal fun ConversationSummaryProjection.visibleLabel(): String = "Conversation " + id.value.take(8)

private enum class DraftSaveState { Idle, Saving, Saved, Failed }

private enum class AgentTextField {
    Description,
    Personality,
    Scenario,
    FirstMessage,
    AlternateGreetings,
    ExampleDialogue,
    SystemPrompt,
    PostHistory,
    CreatorNotes;

    fun label(translator: CockpitTranslator): String = when (this) {
        Description -> translator.choose("Description", "角色描述")
        Personality -> translator.choose("Personality", "性格与语气")
        Scenario -> translator.choose("Scenario", "场景")
        FirstMessage -> translator.choose("Opening message", "开场白")
        AlternateGreetings -> translator.choose("Alternate greetings", "备选开场白")
        ExampleDialogue -> translator.choose("Dialogue examples", "对话示例")
        SystemPrompt -> translator.choose("System prompt", "系统提示词")
        PostHistory -> translator.choose("Post-history instruction", "历史后指令")
        CreatorNotes -> translator.choose("Creator notes", "创作者备注")
    }

    fun value(profile: AgentProfileInput): String = when (this) {
        Description -> profile.description
        Personality -> profile.personality
        Scenario -> profile.scenario
        FirstMessage -> profile.firstMessage
        AlternateGreetings -> profile.alternateGreetings.joinToString("\n---\n")
        ExampleDialogue -> profile.exampleDialogue
        SystemPrompt -> profile.systemPrompt
        PostHistory -> profile.postHistoryInstructions
        CreatorNotes -> profile.creatorNotes
    }

    fun apply(profile: AgentProfileInput, value: String): AgentProfileInput = when (this) {
        Description -> profile.copy(description = value)
        Personality -> profile.copy(personality = value)
        Scenario -> profile.copy(scenario = value)
        FirstMessage -> profile.copy(firstMessage = value)
        AlternateGreetings -> profile.copy(
            alternateGreetings = value.split(Regex("\\n---\\n")).map(String::trim).filter(String::isNotBlank),
        )
        ExampleDialogue -> profile.copy(exampleDialogue = value)
        SystemPrompt -> profile.copy(systemPrompt = value)
        PostHistory -> profile.copy(postHistoryInstructions = value)
        CreatorNotes -> profile.copy(creatorNotes = value)
    }
}

private fun AgentProfileInput.estimatedDefinitionTokens(): Int {
    val text = listOf(
        description,
        personality,
        scenario,
        firstMessage,
        alternateGreetings.joinToString("\n"),
        exampleDialogue,
        systemPrompt,
        postHistoryInstructions,
        lorebookEntries.joinToString("\n") { it.content },
    ).joinToString("\n")
    return ConservativeTokenEstimator.estimate(text)
}

private fun renderPreviewMacros(text: String, profile: AgentProfileInput): String = text
    .replace(Regex(Regex.escape("{{char}}"), RegexOption.IGNORE_CASE), profile.nickname.ifBlank { profile.identity })
    .replace(Regex(Regex.escape("{{user}}"), RegexOption.IGNORE_CASE), "User")
    .replace(Regex("<BOT>", RegexOption.IGNORE_CASE), profile.nickname.ifBlank { profile.identity })
    .replace(Regex("<USER>", RegexOption.IGNORE_CASE), "User")

internal suspend fun readContentBytes(context: Context, uri: Uri, limit: Int): ByteArray =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "The selected file is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("The selected file could not be opened.")
    }

@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateCompat(initial: T): State<T> =
    collectAsState(initial)
