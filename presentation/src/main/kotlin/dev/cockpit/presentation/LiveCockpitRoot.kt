package dev.cockpit.presentation

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.cockpit.application.api.AgentApplicationPort
import dev.cockpit.application.api.AgentConversationQueryPort
import dev.cockpit.application.api.AgentProfileInput
import dev.cockpit.application.api.ConversationApplicationPort
import dev.cockpit.application.api.ProviderSettingsPort
import dev.cockpit.application.api.ProviderVendor
import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.projection.model.HomeProjection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CockpitRoot(
    appName: String,
    agentApplicationPortHandle: Any,
    conversationApplicationPortHandle: Any,
    agentConversationQueryPortHandle: Any,
    providerSettingsPortHandle: Any,
) {
    val agents = requireNotNull(agentApplicationPortHandle as? AgentApplicationPort)
    val conversations = requireNotNull(conversationApplicationPortHandle as? ConversationApplicationPort)
    val queries = requireNotNull(agentConversationQueryPortHandle as? AgentConversationQueryPort)
    val providerSettings = requireNotNull(providerSettingsPortHandle as? ProviderSettingsPort)
    val preferences = rememberCockpitPreferencesState()

    CockpitLocalization(preferences.value.language) {
        CockpitAppearance(preferences.value.theme) {
            val home by queries.home().collectAsState(HomeProjection(emptyList()))
            val navigation = rememberNavController()
            val entry by navigation.currentBackStackEntryAsState()
            val currentRoute = entry?.destination?.route

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (currentRoute in LiveRoute.RootRoutes) {
                        LiveRootNavigation(
                            navigation = navigation,
                            currentRoute = currentRoute,
                        )
                    }
                },
            ) { scaffoldPadding ->
                NavHost(
                    navController = navigation,
                    startDestination = LiveRoute.Chats,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(scaffoldPadding),
                ) {
                composable(LiveRoute.Chats) {
                    LiveChats(
                        home = home,
                        actions = conversations,
                        onCreateAgent = { navigation.navigate(LiveRoute.CreateAgent) },
                        onOpenAgent = {
                            navigation.navigate(LiveRoute.agent(it.value))
                        },
                        onOpenConversation = {
                            navigation.navigate(LiveRoute.conversation(it.value))
                        },
                    )
                }
                composable(LiveRoute.Agents) {
                    LiveAgents(
                        home = home,
                        agents = agents,
                        onCreateAgent = { navigation.navigate(LiveRoute.CreateAgent) },
                        onOpenAgent = {
                            navigation.navigate(LiveRoute.agent(it.value))
                        },
                    )
                }
                composable(LiveRoute.CreateAgent) {
                    CreateAgentScreen(
                        agents = agents,
                        providerSettings = providerSettings,
                        onBack = navigation::navigateUp,
                        onCreated = {
                            navigation.navigate(LiveRoute.agent(it.value)) {
                                popUpTo(LiveRoute.CreateAgent) { inclusive = true }
                            }
                        },
                    )
                }
                composable(LiveRoute.Activity) {
                    LiveActivity(
                        home = home,
                        onCreateAgent = { navigation.navigate(LiveRoute.CreateAgent) },
                        onOpenAgent = { navigation.navigate(LiveRoute.agent(it.value)) },
                        onOpenModels = { navigation.navigate(LiveRoute.Models) },
                    )
                }
                composable(LiveRoute.Settings) {
                    LiveSettingsHome(
                        settings = providerSettings,
                        preferences = preferences.value,
                        onLanguageChange = preferences::setLanguage,
                        onThemeChange = preferences::setTheme,
                        onOpenModels = { navigation.navigate(LiveRoute.Models) },
                        onOpenPrivacyAbout = {
                            navigation.navigate(LiveRoute.PrivacyAbout)
                        },
                    )
                }
                composable(LiveRoute.PrivacyAbout) {
                    LivePrivacyAboutSettings(onBack = navigation::navigateUp)
                }
                composable(LiveRoute.Models) {
                    LiveProviderSettings(
                        settings = providerSettings,
                        onBack = navigation::navigateUp,
                    ) { vendor ->
                        navigation.navigate(LiveRoute.providerVendor(vendor))
                    }
                }
                composable(LiveRoute.ProviderVendorPattern) { route ->
                    route.arguments
                        ?.getString(LiveRoute.ProviderVendorArgument)
                        ?.let { name ->
                            runCatching { ProviderVendor.valueOf(name) }.getOrNull()
                        }
                        ?.let { vendor ->
                            LiveProviderVendorSettings(
                                settings = providerSettings,
                                vendor = vendor,
                                onBack = navigation::navigateUp,
                                onAdd = {
                                    navigation.navigate(LiveRoute.newProviderConnection(vendor))
                                },
                                onEdit = { id ->
                                    navigation.navigate(LiveRoute.providerConnection(vendor, id))
                                },
                                onCopy = { id ->
                                    navigation.navigate(LiveRoute.copyProviderConnection(vendor, id))
                                },
                            )
                        }
                        ?: LiveEmptyState("Provider is not available")
                }
                composable(LiveRoute.ProviderConnectionNewPattern) { route ->
                    route.providerVendor()?.let { vendor ->
                        LiveProviderConnectionEditor(
                            settings = providerSettings,
                            vendor = vendor,
                            connectionId = null,
                            copyFromId = null,
                            onBack = navigation::navigateUp,
                            onSaved = { id ->
                                navigation.navigate(LiveRoute.providerConnection(vendor, id)) {
                                    popUpTo(LiveRoute.ProviderConnectionNewPattern) { inclusive = true }
                                }
                            },
                        )
                    } ?: LiveEmptyState("Provider is not available")
                }
                composable(LiveRoute.ProviderConnectionPattern) { route ->
                    val vendor = route.providerVendor()
                    val connectionId = NavigationRouteToken.decode(
                        route.arguments?.getString(LiveRoute.ProviderConnectionArgument),
                    )
                    if (vendor != null && connectionId != null) {
                        LiveProviderConnectionEditor(
                            settings = providerSettings,
                            vendor = vendor,
                            connectionId = connectionId,
                            copyFromId = null,
                            onBack = navigation::navigateUp,
                            onSaved = {},
                        )
                    } else {
                        LiveEmptyState("Configuration is not available")
                    }
                }
                composable(LiveRoute.ProviderConnectionCopyPattern) { route ->
                    val vendor = route.providerVendor()
                    val sourceId = NavigationRouteToken.decode(
                        route.arguments?.getString(LiveRoute.ProviderConnectionArgument),
                    )
                    if (vendor != null && sourceId != null) {
                        LiveProviderConnectionEditor(
                            settings = providerSettings,
                            vendor = vendor,
                            connectionId = null,
                            copyFromId = sourceId,
                            onBack = navigation::navigateUp,
                            onSaved = { id ->
                                navigation.navigate(LiveRoute.providerConnection(vendor, id)) {
                                    popUpTo(LiveRoute.ProviderConnectionCopyPattern) { inclusive = true }
                                }
                            },
                        )
                    } else {
                        LiveEmptyState("Configuration is not available")
                    }
                }
                composable(LiveRoute.AgentPattern) { route ->
                    NavigationRouteToken
                        .decode(route.arguments?.getString(LiveRoute.AgentIdArgument))
                        ?.let {
                            LiveAgentDetail(
                                agentId = AgentId(it),
                                queries = queries,
                                agents = agents,
                                actions = conversations,
                                navigation = navigation,
                            )
                        }
                        ?: LiveEmptyState("Agent is not available")
                }
                composable(LiveRoute.EditAgentPattern) { route ->
                    NavigationRouteToken
                        .decode(route.arguments?.getString(LiveRoute.AgentIdArgument))
                        ?.let {
                            EditAgentScreen(
                                agentId = AgentId(it),
                                agents = agents,
                                queries = queries,
                                providerSettings = providerSettings,
                                onBack = navigation::navigateUp,
                            )
                        }
                        ?: LiveEmptyState("Agent is not available")
                }
                composable(LiveRoute.ConversationPattern) { route ->
                    NavigationRouteToken
                        .decode(route.arguments?.getString(LiveRoute.ConversationIdArgument))
                        ?.let {
                            LiveConversation(
                                conversationId = ConversationId(it),
                                queries = queries,
                                actions = conversations,
                                navigation = navigation,
                            )
                        }
                        ?: LiveEmptyState("Conversation is not available")
                }
            }
        }
    }
}
}

@Composable
private fun LiveChats(
    home: HomeProjection,
    actions: ConversationApplicationPort,
    onCreateAgent: () -> Unit,
    onOpenAgent: (AgentId) -> Unit,
    onOpenConversation: (ConversationId) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var creatingForAgent by remember { mutableStateOf<AgentId?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(title = "Chats")
        }
        if (home.agents.isEmpty()) {
            item {
                EmptyContentCard(
                    icon = ImageVector.vectorResource(R.drawable.ic_chat_outline),
                    title = "Create your first Agent",
                    actionLabel = "Create Agent",
                    onAction = onCreateAgent,
                )
            }
        } else {
            items(home.agents, key = { it.id.value }) { agent ->
                AgentSummaryCard(
                    name = agent.name,
                    supportingText = agent.providerName?.let { "Ready with $it" }
                        ?: "Stored locally • model not connected",
                    contentDescription = "Open " + agent.name + " conversations",
                    onClick = { onOpenAgent(agent.id) },
                    avatarRef = agent.avatarRef,
                    actionIcon = Icons.Rounded.AddCircle,
                    actionContentDescription = if (creatingForAgent == agent.id) {
                        "Starting…"
                    } else {
                        "New chat"
                    },
                    onAction = {
                        if (creatingForAgent != null) return@AgentSummaryCard
                        scope.launch {
                            creatingForAgent = agent.id
                            try {
                                actions.createConversation(agent.id)?.let(onOpenConversation)
                            } finally {
                                creatingForAgent = null
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LiveAgents(
    home: HomeProjection,
    agents: AgentApplicationPort,
    onCreateAgent: () -> Unit,
    onOpenAgent: (AgentId) -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedDraft by agents.observeCreationDraft().collectAsState(null)
    var importing by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf<AgentProfileInput?>(null) }
    var importPreview by remember { mutableStateOf<PendingCharacterImport?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    fun beginWith(profile: AgentProfileInput) {
        scope.launch {
            agents.discardCreationDraft()
            agents.saveCreationDraft(profile)
            onCreateAgent()
        }
    }

    suspend fun profileWithImportedAvatar(pending: PendingCharacterImport): AgentProfileInput {
        var profile = pending.preview.profile
        pending.preview.avatarPngBytes?.let { image ->
            AgentAvatarAssets.save(context, image).getOrNull()?.let { avatar ->
                profile = profile.copy(avatarRef = avatar)
            }
        }
        return profile
    }

    val characterCardLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || importing) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            importError = null
            try {
                val name = withContext(Dispatchers.IO) {
                    context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }
                val bytes = readContentBytes(context, uri, 30 * 1024 * 1024)
                val preview = TavernCharacterCardImporter.parse(bytes, name).getOrThrow()
                val duplicate = preview.profile.importSource?.payloadDigest?.let {
                    agents.findAgentByImportDigest(it)
                }
                importPreview = PendingCharacterImport(preview, duplicate)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                importError = error.message ?: "The character card could not be imported."
            } finally {
                importing = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(title = "Agents") {
                HeaderIconButton(
                    icon = Icons.Rounded.Add,
                    contentDescription = "Create Agent",
                    onClick = { showActions = true },
                )
            }
        }
        importError?.let { message ->
            item {
                InfoBanner(
                    title = translator.choose("Import failed", "导入失败"),
                    body = message,
                    tone = StatusTone.Warning,
                )
            }
        }
        if (home.agents.isEmpty()) {
            item {
                EmptyContentCard(
                    icon = Icons.Filled.Person,
                    title = "No Agents yet",
                    actionLabel = "Create Agent",
                    onAction = { showActions = true },
                )
            }
        } else {
            items(home.agents, key = { it.id.value }) { agent ->
                AgentSummaryCard(
                    name = agent.name,
                    supportingText = agent.summary.ifBlank {
                        agent.providerName?.let {
                            translator.choose("Connected · $it", "已连接 · $it")
                        }
                            ?: translator.choose("Ready for conversation", "可开始对话")
                    },
                    contentDescription = "Open agent " + agent.name,
                    onClick = { onOpenAgent(agent.id) },
                    avatarRef = agent.avatarRef,
                )
            }
        }
    }

    if (showActions) {
        ModalBottomSheet(onDismissRequest = { showActions = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                Text(
                    translator.choose("Create Agent", "创建 Agent"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                if (savedDraft != null) {
                    CreationEntryRow(
                        translator.choose("Continue draft", "继续草稿"),
                        translator.choose("Resume where you left off", "继续上次未完成的内容"),
                    ) {
                        showActions = false
                        onCreateAgent()
                    }
                }
                CreationEntryRow(
                    translator.choose("Blank Agent", "空白创建"),
                    translator.choose("Start with only the essential fields", "从必要字段开始"),
                ) {
                    showActions = false
                    val blank = AgentProfileInput(identity = "")
                    if (savedDraft == null) beginWith(blank) else replacement = blank
                }
                CreationEntryRow(
                    translator.choose("Use a template", "使用模板"),
                    translator.choose("Choose a focused starting point", "选择一个预设起点"),
                ) {
                    showActions = false
                    showTemplates = true
                }
                CreationEntryRow(
                    translator.choose(if (importing) "Importing…" else "Import character card", if (importing) "正在导入…" else "导入角色卡"),
                    translator.choose("Tavern PNG or JSON · V1, V2, V3", "酒馆 PNG 或 JSON · V1、V2、V3"),
                    enabled = !importing,
                ) {
                    showActions = false
                    characterCardLauncher.launch(
                        arrayOf(
                            "image/png",
                            "application/json",
                            "text/json",
                            "text/plain",
                            "application/octet-stream",
                        ),
                    )
                }
            }
        }
    }

    if (showTemplates) {
        ModalBottomSheet(onDismissRequest = { showTemplates = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                Text(
                    translator.choose("Templates", "模板"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                BuiltInAgentTemplates.forEach { template ->
                    CreationEntryRow(
                        translator.choose(template.titleEn, template.titleZh),
                        translator.choose(template.summaryEn, template.summaryZh),
                    ) {
                        showTemplates = false
                        val seed = template.localizedProfile(translator)
                        if (savedDraft == null) beginWith(seed)
                        else replacement = seed
                    }
                }
            }
        }
    }

    replacement?.let { candidate ->
        AlertDialog(
            onDismissRequest = { replacement = null },
            title = { Text(translator.choose("Replace draft?", "替换当前草稿？")) },
            text = {
                Text(
                    translator.choose(
                        "Your current creation draft will be replaced.",
                        "当前未完成的创建草稿将被替换。",
                    ),
                )
            },
            confirmButton = {
                Button(onClick = {
                    replacement = null
                    beginWith(candidate)
                }) { Text(translator.choose("Replace", "替换")) }
            },
            dismissButton = {
                TextButton(onClick = { replacement = null }) {
                    Text(translator.choose("Cancel", "取消"))
                }
            },
        )
    }

    importPreview?.let { pending ->
        CharacterImportPreviewDialog(
            pending = pending,
            onCancel = { importPreview = null },
            onOpenExisting = pending.duplicateAgentId?.let { existing ->
                {
                    importPreview = null
                    onOpenAgent(existing)
                }
            },
            onReplaceExisting = pending.duplicateAgentId?.let { existing ->
                {
                    scope.launch {
                        val updated = agents.updateAgent(
                            existing,
                            profileWithImportedAvatar(pending),
                        )
                        if (updated) {
                            importPreview = null
                            onOpenAgent(existing)
                        } else {
                            importError = translator.choose(
                                "The existing Agent could not be replaced.",
                                "无法替换已有 Agent。",
                            )
                        }
                    }
                }
            },
            onContinue = {
                scope.launch {
                    val profile = profileWithImportedAvatar(pending)
                    agents.discardCreationDraft()
                    agents.saveCreationDraft(profile)
                    importPreview = null
                    onCreateAgent()
                }
            },
        )
    }
}

@Composable
private fun CreationEntryRow(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun CharacterImportPreviewDialog(
    pending: PendingCharacterImport,
    onCancel: () -> Unit,
    onOpenExisting: (() -> Unit)?,
    onReplaceExisting: (() -> Unit)?,
    onContinue: () -> Unit,
) {
    val translator = LocalCockpitTranslator.current
    val preview = pending.preview
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(translator.choose("Import preview", "导入预览")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(preview.profile.identity, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${preview.container} · ${preview.spec}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    translator.choose(
                        "${preview.alternateGreetingCount} alternate greetings · ${preview.lorebookEntryCount} lorebook entries",
                        "${preview.alternateGreetingCount} 条备选开场白 · ${preview.lorebookEntryCount} 个世界书条目",
                    ),
                )
                if (preview.hasCustomSystemPrompt) {
                    Text(translator.choose("Contains custom prompt instructions", "包含自定义提示词指令"))
                }
                if (preview.preservedFieldCount > 0) {
                    Text(
                        translator.choose(
                            "${preview.preservedFieldCount} unknown extension fields will be preserved",
                            "将保留 ${preview.preservedFieldCount} 个未知扩展字段",
                        ),
                    )
                }
                if (pending.duplicateAgentId != null) {
                    InfoBanner(
                        translator.choose("Already imported", "检测到重复导入"),
                        translator.choose(
                            "Open it, replace it with this card, or import a copy.",
                            "可打开已有 Agent、用此卡替换，或导入副本。",
                        ),
                        tone = StatusTone.Warning,
                        actionLabel = translator.choose("Replace", "替换已有"),
                        onAction = onReplaceExisting,
                    )
                }
                preview.warnings.take(4).forEach { warning ->
                    Text("• $warning", color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        confirmButton = {
            Button(onClick = onContinue) {
                Text(
                    if (pending.duplicateAgentId == null) {
                        translator.choose("Continue editing", "继续编辑")
                    } else {
                        translator.choose("Import a copy", "导入副本")
                    },
                )
            }
        },
        dismissButton = {
            Row {
                onOpenExisting?.let {
                    TextButton(onClick = it) {
                        Text(translator.choose("Open existing", "打开已有"))
                    }
                }
                TextButton(onClick = onCancel) { Text(translator.choose("Cancel", "取消")) }
            }
        },
    )
}

private data class PendingCharacterImport(
    val preview: CharacterCardImportPreview,
    val duplicateAgentId: AgentId?,
)

private fun AgentTemplate.localizedProfile(translator: CockpitTranslator): AgentProfileInput =
    profile.copy(
        summary = translator.choose(profile.summary, summaryZh),
        personality = when (id) {
            "focused-assistant" -> translator.choose(
                profile.personality,
                "冷静、简洁、周到，重视可执行性。",
            )
            "writing-partner" -> translator.choose(
                profile.personality,
                "好奇、建设性，关注语气与结构。",
            )
            "research-analyst" -> translator.choose(
                profile.personality,
                "系统、审慎、精确，并透明说明证据缺口。",
            )
            else -> translator.choose(
                profile.personality,
                "保持角色设定一致，并以角色身份自然回应。",
            )
        },
        systemPrompt = when (id) {
            "writing-partner" -> translator.choose(
                profile.systemPrompt,
                "你是 {{char}}，是 {{user}} 的写作搭档。协助起草和修改，同时保留用户的意图与表达。",
            )
            "research-analyst" -> translator.choose(
                profile.systemPrompt,
                "你是 {{char}}，负责协助 {{user}} 进行研究分析。请明确区分证据、推断与待确认问题。",
            )
            else -> profile.systemPrompt
        },
        firstMessage = if (id == "roleplay-character") {
            translator.choose(
                profile.firstMessage,
                "*{{char}} 抬起头，看见 {{user}} 到来。*\n\n你好，我一直在等你。",
            )
        } else {
            profile.firstMessage
        },
    )

@Composable
private fun LiveActivity(
    home: HomeProjection,
    onCreateAgent: () -> Unit,
    onOpenAgent: (AgentId) -> Unit,
    onOpenModels: () -> Unit,
) {
    val needsProvider = home.agents.filter { it.providerName == null }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(title = "Activity")
        }
        if (home.agents.isEmpty()) {
            item {
                EmptyContentCard(
                    icon = Icons.Filled.Notifications,
                    title = "Nothing to review",
                    actionLabel = "Create Agent",
                    onAction = onCreateAgent,
                )
            }
        } else {
            if (needsProvider.isEmpty()) {
                item {
                    EmptyContentCard(
                        icon = Icons.Rounded.CheckCircle,
                        title = "You're caught up",
                    )
                }
            } else {
                item { SectionHeader(title = "Needs attention") }
                items(needsProvider, key = { "attention:" + it.id.value }) { agent ->
                    AgentSummaryCard(
                        name = agent.name,
                        supportingText = "Model provider not connected",
                        contentDescription = "Open agent " + agent.name,
                        onClick = { onOpenAgent(agent.id) },
                        actionIcon = Icons.Rounded.Build,
                        actionContentDescription = "Configure",
                        onAction = onOpenModels,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LiveEmptyState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyContentCard(
            icon = Icons.Outlined.Warning,
            title = "Unavailable",
            body = message,
        )
    }
}

@Composable
private fun LiveRootNavigation(
    navigation: NavHostController,
    currentRoute: String?,
) {
    val translator = LocalCockpitTranslator.current
    val chatIcon = ImageVector.vectorResource(R.drawable.ic_chat_outline)
    val selectedChatIcon = ImageVector.vectorResource(R.drawable.ic_chat_filled)
    val items = listOf(
        RootNavigationItem(
            LiveRoute.Chats,
            "Chats",
            chatIcon,
            selectedChatIcon,
        ),
        RootNavigationItem(
            LiveRoute.Agents,
            "Agents",
            Icons.Filled.Person,
            Icons.Filled.Person,
        ),
        RootNavigationItem(
            LiveRoute.Activity,
            "Activity",
            Icons.Filled.Notifications,
            Icons.Filled.Notifications,
        ),
        RootNavigationItem(
            LiveRoute.Settings,
            "Settings",
            Icons.Filled.Settings,
            Icons.Filled.Settings,
        ),
    )
    NavigationBar(
        modifier = Modifier.semantics {
            contentDescription = translator.choose("Root navigation", "底部导航")
        },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = {
                    navigation.navigate(item.route) {
                        popUpTo(LiveRoute.Chats) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (currentRoute == item.route) item.selectedIcon else item.icon,
                        contentDescription = null,
                        modifier = Modifier.padding(2.dp),
                    )
                },
                label = { Text(translator.text(item.label)) },
                modifier = Modifier.semantics {
                    contentDescription = translator.text("Open " + item.label)
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

private data class RootNavigationItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

internal object LiveRoute {
    const val Chats = "Chats"
    const val Agents = "Agents"
    const val Activity = "Activity"
    const val Settings = "Settings"
    const val Models = "settings/models"
    const val PrivacyAbout = "settings/privacy-about"
    const val CreateAgent = "create-agent"
    const val AgentIdArgument = "agentId"
    const val ConversationIdArgument = "conversationId"
    const val ProviderVendorArgument = "providerVendor"
    const val ProviderConnectionArgument = "providerConnection"
    const val AgentPattern = "agent/{$AgentIdArgument}"
    const val EditAgentPattern = "edit-agent/{$AgentIdArgument}"
    const val ConversationPattern = "conversation/{$ConversationIdArgument}"
    const val ProviderVendorPattern = "settings/models/provider/{$ProviderVendorArgument}"
    const val ProviderConnectionNewPattern =
        "settings/models/provider/{$ProviderVendorArgument}/connection/new"
    const val ProviderConnectionPattern =
        "settings/models/provider/{$ProviderVendorArgument}/connection/{$ProviderConnectionArgument}"
    const val ProviderConnectionCopyPattern =
        "settings/models/provider/{$ProviderVendorArgument}/connection/copy/{$ProviderConnectionArgument}"
    val RootRoutes = listOf(Chats, Agents, Activity, Settings)

    fun agent(id: String) = "agent/" + NavigationRouteToken.encode(id)

    fun editAgent(id: String) = "edit-agent/" + NavigationRouteToken.encode(id)

    fun conversation(id: String) = "conversation/" + NavigationRouteToken.encode(id)

    fun providerVendor(vendor: ProviderVendor) = "settings/models/provider/" + vendor.name

    fun newProviderConnection(vendor: ProviderVendor) =
        "settings/models/provider/${vendor.name}/connection/new"

    fun providerConnection(vendor: ProviderVendor, id: String) =
        "settings/models/provider/${vendor.name}/connection/${NavigationRouteToken.encode(id)}"

    fun copyProviderConnection(vendor: ProviderVendor, id: String) =
        "settings/models/provider/${vendor.name}/connection/copy/${NavigationRouteToken.encode(id)}"
}

private fun androidx.navigation.NavBackStackEntry.providerVendor(): ProviderVendor? =
    arguments?.getString(LiveRoute.ProviderVendorArgument)?.let { name ->
        runCatching { ProviderVendor.valueOf(name) }.getOrNull()
    }
