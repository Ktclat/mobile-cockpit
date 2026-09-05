package dev.cockpit.presentation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

internal enum class CockpitThemePreference { SYSTEM, LIGHT, DARK }

internal enum class CockpitLanguagePreference { SYSTEM, SIMPLIFIED_CHINESE, ENGLISH }

internal enum class CockpitUiLanguage { ENGLISH, SIMPLIFIED_CHINESE }

internal data class CockpitUiPreferences(
    val theme: CockpitThemePreference = CockpitThemePreference.SYSTEM,
    val language: CockpitLanguagePreference = CockpitLanguagePreference.SYSTEM,
)

internal class CockpitPreferencesState(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences(
        "cockpit_ui_preferences",
        Context.MODE_PRIVATE,
    )

    var value by mutableStateOf(
        CockpitUiPreferences(
            theme = storage.getString("theme", null).toEnumOr(CockpitThemePreference.SYSTEM),
            language = storage.getString("language", null)
                .toEnumOr(CockpitLanguagePreference.SYSTEM),
        ),
    )
        private set

    fun setTheme(theme: CockpitThemePreference) {
        value = value.copy(theme = theme)
        storage.edit().putString("theme", theme.name).apply()
    }

    fun setLanguage(language: CockpitLanguagePreference) {
        value = value.copy(language = language)
        storage.edit().putString("language", language.name).apply()
    }
}

@Composable
internal fun rememberCockpitPreferencesState(): CockpitPreferencesState {
    val context = LocalContext.current
    return androidx.compose.runtime.remember(context) { CockpitPreferencesState(context) }
}

internal class CockpitTranslator(val language: CockpitUiLanguage) {
    val isChinese: Boolean get() = language == CockpitUiLanguage.SIMPLIFIED_CHINESE

    fun choose(english: String, chinese: String): String = if (isChinese) chinese else english

    fun text(english: String): String {
        if (!isChinese) return english
        ChineseTranslations[english]?.let { return it }

        val manualFallbackSuffix = " You can still add a model ID manually."
        if (english.endsWith(manualFallbackSuffix)) {
            return "${text(english.removeSuffix(manualFallbackSuffix))} 你仍可手动添加模型 ID。"
        }
        val manualRequiredSuffix = " Add a model ID manually."
        if (english.endsWith(manualRequiredSuffix)) {
            return "${text(english.removeSuffix(manualRequiredSuffix))} 请手动添加模型 ID。"
        }

        Regex("(\\d+) Agents? available").matchEntire(english)?.let {
            return "可用智能体：${it.groupValues[1]} 个"
        }
        Regex("(\\d+) identit(?:y|ies)").matchEntire(english)?.let {
            return "${it.groupValues[1]} 个智能体"
        }
        Regex("(\\d+) Agents? needs? a model").matchEntire(english)?.let {
            return "${it.groupValues[1]} 个智能体需要配置模型"
        }
        Regex("(\\d+) Agents? (?:is|are) configured").matchEntire(english)?.let {
            return "已配置 ${it.groupValues[1]} 个智能体"
        }
        Regex("(\\d+) active threads?").matchEntire(english)?.let {
            return "${it.groupValues[1]} 个活跃对话"
        }
        Regex("(\\d+) accounts?").matchEntire(english)?.let {
            return "${it.groupValues[1]} 个账号"
        }
        Regex("(\\d+) configurations?").matchEntire(english)?.let {
            return "${it.groupValues[1]} 个配置"
        }
        Regex("(\\d+) accounts? ready to manage").matchEntire(english)?.let {
            return "可管理 ${it.groupValues[1]} 个账号"
        }
        Regex("(\\d+) accounts?\\. Each account keeps its own key and model\\.")
            .matchEntire(english)?.let {
                return "共 ${it.groupValues[1]} 个账号，每个账号独立保存 Key 与模型。"
            }
        Regex("Conversation (.+)").matchEntire(english)?.let {
            return "对话 ${it.groupValues[1]}"
        }
        Regex("Ready with (.+)").matchEntire(english)?.let {
            return "已连接 ${it.groupValues[1]}"
        }
        Regex("Connected to (.+)").matchEntire(english)?.let {
            return "已连接 ${it.groupValues[1]}"
        }
        Regex("Bound to (.+)").matchEntire(english)?.let {
            return "已绑定 ${it.groupValues[1]}"
        }
        Regex("Bound • (.+)").matchEntire(english)?.let {
            return "已绑定 · ${it.groupValues[1]}"
        }
        Regex("(.+) was imported successfully\\.").matchEntire(english)?.let {
            return "${it.groupValues[1]} 导入成功。"
        }
        Regex("Add your first (.+) account").matchEntire(english)?.let {
            return "添加首个 ${it.groupValues[1]} 账号"
        }
        Regex("Add (.+) account").matchEntire(english)?.let {
            return "添加 ${it.groupValues[1]} 账号"
        }
        Regex("No (.+) account has been added\\.").matchEntire(english)?.let {
            return "尚未添加 ${it.groupValues[1]} 账号。"
        }
        Regex("Receiving from (.+)").matchEntire(english)?.let {
            return "正在接收 ${it.groupValues[1]} 的回复"
        }
        Regex("(.+) is bound").matchEntire(english)?.let {
            return "已绑定 ${it.groupValues[1]}"
        }
        Regex("Model (.+) • connection tested").matchEntire(english)?.let {
            return "模型 ${it.groupValues[1]} · 连接已测试"
        }
        Regex("Model (.+) • connection not tested").matchEntire(english)?.let {
            return "模型 ${it.groupValues[1]} · 连接未测试"
        }
        Regex("Message (.+)").matchEntire(english)?.let {
            return "给 ${it.groupValues[1]} 发消息"
        }
        Regex("Open agent (.+)").matchEntire(english)?.let {
            return "打开智能体 ${it.groupValues[1]}"
        }
        Regex("Open (.+) conversations").matchEntire(english)?.let {
            return "打开 ${it.groupValues[1]} 的对话"
        }
        Regex("Configuration saved\\. It remains unverified until you run a conversation test\\. The full (.+) endpoint was converted to its API prefix\\.")
            .matchEntire(english)?.let {
                return "配置已保存，运行对话测试前仍标记为未验证。已将完整端点 ${it.groupValues[1]} 转换为 API 基础地址。"
            }
        Regex("Found (\\d+) model\\(s\\)\\. Choose which ones to enable\\.")
            .matchEntire(english)?.let {
                return "找到 ${it.groupValues[1]} 个模型，请选择需要启用的模型。"
            }
        Regex("Conversation test passed in (\\d+) ms · (.+)").matchEntire(english)?.let {
            return "对话测试通过，用时 ${it.groupValues[1]} 毫秒 · ${it.groupValues[2]}"
        }
        Regex("This configuration is used by (.+)\\. Change those routes before deleting it\\.")
            .matchEntire(english)?.let {
                val uses = it.groupValues[1]
                    .replace("the global default", "全局默认模型")
                    .replace(Regex("(\\d+) Agent\\(s\\)")) { match ->
                        "${match.groupValues[1]} 个智能体"
                    }
                    .replace(Regex("(\\d+) conversation\\(s\\)")) { match ->
                        "${match.groupValues[1]} 个对话"
                    }
                return "此配置正被 $uses 使用，请先更改相关路由再删除。"
            }
        Regex("(\\d+) Agents?").matchEntire(english)?.let {
            return "${it.groupValues[1]} 个智能体"
        }
        return english
    }
}

internal val LocalCockpitTranslator = staticCompositionLocalOf {
    CockpitTranslator(CockpitUiLanguage.ENGLISH)
}

@Composable
internal fun CockpitLocalization(
    preference: CockpitLanguagePreference,
    content: @Composable () -> Unit,
) {
    val systemLanguage = LocalConfiguration.current.locales[0]?.language.orEmpty()
    val language = when (preference) {
        CockpitLanguagePreference.SYSTEM -> if (systemLanguage == "zh") {
            CockpitUiLanguage.SIMPLIFIED_CHINESE
        } else {
            CockpitUiLanguage.ENGLISH
        }
        CockpitLanguagePreference.SIMPLIFIED_CHINESE -> CockpitUiLanguage.SIMPLIFIED_CHINESE
        CockpitLanguagePreference.ENGLISH -> CockpitUiLanguage.ENGLISH
    }
    CompositionLocalProvider(
        LocalCockpitTranslator provides CockpitTranslator(language),
        content = content,
    )
}

private inline fun <reified T : Enum<T>> String?.toEnumOr(fallback: T): T =
    this?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: fallback

private val ChineseTranslations = mapOf(
    "Back" to "返回",
    "Chats" to "对话",
    "Agents" to "智能体",
    "Activity" to "动态",
    "Settings" to "设置",
    "Models" to "模型",
    "Agent" to "智能体",
    "You" to "你",
    "System" to "系统",
    "Open Chats" to "打开对话",
    "Open Agents" to "打开智能体",
    "Open Activity" to "打开动态",
    "Open Settings" to "打开设置",
    "More options" to "更多选项",
    "Navigate up" to "返回上一级",
    "Personal agent runtime" to "个人智能体运行空间",
    "Open an Agent first, or start a fresh conversation immediately." to "进入智能体查看已有对话，或直接开始一个新对话。",
    "Choose how to begin" to "选择开始方式",
    "Tap an Agent to see its conversations, or use New chat to go straight into a fresh thread." to "点击智能体查看会话列表，或点击“新对话”直接进入新的会话。",
    "Chats are stored locally. Connect a model in Settings when you want live replies." to "对话保存在本机；需要实时回复时，可前往设置连接模型。",
    "Create your first Agent" to "创建第一个智能体",
    "Every conversation belongs to an Agent, so start by creating or importing one." to "每个对话都属于一个智能体，请先创建或导入一个。",
    "Create Agent" to "创建智能体",
    "Provider keys are encrypted on this device. Conversations use only explicit Agent bindings." to "Provider Key 已在本机加密；对话只会使用明确绑定到智能体的账号。",
    "Built around continuity" to "围绕连续对话设计",
    "Agent" to "智能体",
    "Conversation" to "对话",
    "Runtime" to "运行时",
    "The identity you return to." to "可反复使用的角色身份。",
    "A durable thread with its own draft." to "拥有独立草稿的持久对话。",
    "Execution appears only when capability is real." to "仅在能力真实可用时提供执行功能。",
    "Start a conversation" to "开始对话",
    "Need another point of view?" to "需要另一种视角？",
    "Each Agent keeps a separate identity and conversation history." to "每个智能体都有独立身份和对话记录。",
    "New Agent" to "新建智能体",
    "New chat" to "新对话",
    "Starting…" to "正在创建…",
    "Stored locally • model not connected" to "已保存在本机 · 尚未连接模型",
    "Identity before model" to "身份优先于模型",
    "Independent voices, context, and conversations in one trusted shell." to "在一个可信空间中管理独立的角色、上下文与对话。",
    "+ Add" to "+ 添加",
    "Import Tavern character card" to "导入酒馆角色卡",
    "Importing…" to "正在导入…",
    "Import failed" to "导入失败",
    "Character imported" to "角色已导入",
    "No Agents yet" to "还没有智能体",
    "Your Agents" to "我的智能体",
    "Create one manually or import a SillyTavern card." to "可手动创建，或导入 SillyTavern 角色卡。",
    "Start with an identity" to "从角色身份开始",
    "An Agent owns its conversations. JSON and PNG Tavern cards are supported." to "智能体拥有自己的对话；支持 JSON 与 PNG 酒馆角色卡。",
    "Conversation-ready • stored locally" to "可开始对话 · 已保存在本机",
    "Operational inbox" to "运行收件箱",
    "See what needs attention now; later, Agent runs, approvals, and failures will share this timeline." to "集中查看需要处理的事项；后续智能体运行、审批与失败记录也会汇总到这里。",
    "Nothing to review" to "暂无待处理事项",
    "Create an Agent first. Activity will surface setup issues without mixing them into your chats." to "先创建一个智能体。动态页会集中显示配置问题，不会干扰对话内容。",
    "All Agents are ready" to "所有智能体均已就绪",
    "There are no configuration issues requiring attention." to "当前没有需要处理的配置问题。",
    "Connect a provider account so these Agents can produce live replies." to "连接模型账号后，这些智能体即可生成实时回复。",
    "Needs attention" to "需要处理",
    "No open setup issues." to "没有待处理的设置问题。",
    "Resolve these before relying on model replies." to "请先解决这些问题，再使用模型回复。",
    "You're caught up" to "已全部处理",
    "New failures, approvals, and background work will appear here." to "新的失败、审批与后台任务会显示在这里。",
    "Model provider not connected" to "尚未连接模型服务",
    "Configure" to "配置",
    "Ready" to "已就绪",
    "Why Activity is separate" to "为什么动态是独立页面",
    "Chats hold conversation content. Activity is the operational record for runs, approvals, errors, and configuration alerts." to "对话页保存交流内容；动态页记录运行、审批、错误和配置提醒。",
    "Unavailable" to "不可用",
    "Provider is not available" to "该模型服务不可用。",
    "Agent is not available" to "该智能体不可用。",
    "Conversation is not available" to "该对话不可用。",
    "Your cockpit is ready" to "Cockpit 已准备就绪",
    "Local conversation continuity is active." to "本地连续对话已启用。",
    "On-device" to "本机运行",
    "No active run" to "暂无运行任务",
    "Cockpit preferences" to "Cockpit 偏好设置",
    "Manage models, privacy, and how this device runs Cockpit." to "管理模型、隐私以及 Cockpit 在本机上的运行方式。",
    "General" to "通用",
    "Language" to "语言",
    "Language and appearance are saved on this device." to "语言与外观选择会保存在本机。",
    "Choose the language used throughout Cockpit" to "选择 Cockpit 全局使用的语言",
    "Theme" to "主题",
    "Choose light, dark, or follow the device" to "选择浅色、深色或跟随系统",
    "Follow system" to "跟随系统",
    "Light" to "浅色",
    "Dark" to "深色",
    "Simplified Chinese" to "简体中文",
    "English" to "English",
    "Choose language" to "选择语言",
    "Choose theme" to "选择主题",
    "Cancel" to "取消",
    "Connections" to "连接",
    "Configure once, then assign an account to any Agent." to "完成一次配置，即可将账号分配给任意智能体。",
    "Provider accounts, API keys, models, and Agent assignments" to "管理 Provider 账号、API Key、模型及智能体分配",
    "On this device" to "本机设置",
    "Privacy and credentials" to "隐私与凭据",
    "Privacy & About" to "隐私与关于",
    "API keys are encrypted with Android Keystore. Conversations and Agent profiles remain local to this installation." to "API Key 使用 Android Keystore 加密；对话与智能体资料仅保存在本机。",
    "About Cockpit" to "关于 Cockpit",
    "Local-first Agent conversations with explicit model connections." to "本地优先的智能体对话工具，模型连接始终由你明确控制。",
    "Provider accounts" to "Provider 账号",
    "Choose a provider" to "选择 Provider",
    "Official presets keep setup short while still allowing multiple API accounts." to "官方预设可简化配置，同时仍支持添加多个 API 账号。",
    "No accounts added yet" to "尚未添加账号",
    "Open a provider to add, edit, test, or remove its API accounts." to "打开 Provider，即可添加、编辑、测试或删除 API 账号。",
    "Keys stay on this device" to "Key 仅保存在本机",
    "API keys are encrypted with Android Keystore. Provider cards and Agent bindings contain non-secret metadata only." to "API Key 使用 Android Keystore 加密；Provider 卡片与智能体绑定只保存非敏感信息。",
    "No accounts" to "暂无账号",
    "No configurations" to "未配置",
    "1 configuration" to "1 个配置",
    "DeepSeek account" to "DeepSeek 账号",
    "OpenAI account" to "OpenAI 账号",
    "Gemini account" to "Gemini 账号",
    "GLM account" to "GLM 账号",
    "Claude account" to "Claude 账号",
    "Custom account" to "自定义账号",
    "Custom" to "自定义",
    "Connect one or more DeepSeek API accounts." to "连接一个或多个 DeepSeek API 账号。",
    "Use the official Responses API with streaming." to "使用支持流式输出的官方 Responses API。",
    "Connect Gemini through Google's compatibility endpoint." to "通过 Google 兼容端点连接 Gemini。",
    "Connect one or more Zhipu GLM API accounts." to "连接一个或多个智谱 GLM API 账号。",
    "Connect Claude through Anthropic's official Messages API." to "通过 Anthropic 官方 Messages API 连接 Claude。",
    "Add any HTTPS OpenAI-compatible endpoint." to "添加任意兼容 OpenAI 的 HTTPS 端点。",
    "DeepSeek model ID" to "DeepSeek 模型 ID",
    "OpenAI model ID" to "OpenAI 模型 ID",
    "Gemini model ID" to "Gemini 模型 ID",
    "GLM model ID" to "GLM 模型 ID",
    "Claude model ID" to "Claude 模型 ID",
    "Exact model ID" to "准确的模型 ID",
    "API accounts" to "API 账号",
    "Add account" to "添加账号",
    "Add the endpoint, model ID, and API key." to "填写端点、模型 ID 与 API Key。",
    "The official settings are preset, so you can usually paste an API key and save." to "官方参数已经预设，通常粘贴 API Key 后即可保存。",
    "Edit account" to "编辑账号",
    "The official endpoint and recommended model are already filled in. Usually you only need the API key." to "官方端点和推荐模型已预填，通常只需填写 API Key。",
    "Enter an OpenAI-compatible endpoint, model, and API key." to "请输入兼容 OpenAI 的端点、模型和 API Key。",
    "API key" to "API Key",
    "API key (blank keeps the saved key)" to "API Key（留空则保留原 Key）",
    "Change account name, model, or limits" to "修改账号名称、模型或限制",
    "Hide details" to "收起详细设置",
    "Account name" to "账号名称",
    "Model ID" to "模型 ID",
    "Base URL" to "Base URL",
    "Full HTTPS compatibility endpoint" to "完整的 HTTPS 兼容端点",
    "Official preset" to "官方预设",
    "Responses API" to "Responses API",
    "OpenAI-compatible" to "兼容 OpenAI",
    "Messages API" to "Messages API",
    "Maximum output tokens" to "最大输出 Token 数",
    "Saving…" to "正在保存…",
    "Save account" to "保存账号",
    "Not tested" to "未测试",
    "Available" to "可用",
    "API account encrypted on device" to "API 账号已在本机加密",
    "API key needs to be entered again" to "需要重新输入 API Key",
    "Testing…" to "正在测试…",
    "Test" to "测试",
    "Edit" to "编辑",
    "Delete" to "删除",
    "Confirm delete" to "确认删除",
    "Done" to "完成",
    "Enter the API key for this account." to "请输入该账号的 API Key。",
    "Complete the account name, model ID, HTTPS endpoint, and token limit." to "请完整填写账号名称、模型 ID、HTTPS 端点和 Token 限制。",
    "API account saved. The key stays hidden." to "API 账号已保存，Key 将始终隐藏。",
    "Enter the API key again to repair this profile." to "请重新输入 API Key 以修复该账号。",
    "An API key is required for a new provider." to "新增 Provider 必须填写 API Key。",
    "Use a valid HTTPS endpoint, model, name, and token limit." to "请使用有效的 HTTPS 端点、模型、名称和 Token 限制。",
    "The provider profile could not be saved safely." to "无法安全保存 Provider 账号。",
    "Provider profile not found." to "未找到 Provider 账号。",
    "API account and saved credential deleted." to "API 账号及其凭据已删除。",
    "The provider profile could not be deleted." to "无法删除 Provider 账号。",
    "Provider profile is invalid. Edit and save it again." to "Provider 账号无效，请编辑后重新保存。",
    "Connection available" to "连接可用",
    "Connection available." to "连接可用。",
    "Connection available. Streaming works; tool support is unverified." to "连接可用，流式输出正常；工具调用尚未验证。",
    "Provider bound to Agent." to "Provider 已绑定到智能体。",
    "The Provider could not be bound to this Agent." to "无法将 Provider 绑定到该智能体。",
    "Use with an Agent" to "分配给智能体",
    "Choose one of these accounts, then assign its model to an Agent." to "选择一个账号，再将它的模型分配给智能体。",
    "No Agent to assign" to "没有可分配的智能体",
    "Create an Agent first, then return here to attach this model." to "请先创建智能体，再返回这里绑定模型。",
    "No model assigned" to "尚未分配模型",
    "Assign" to "分配",
    "Change" to "更换",
    "HTTPS required" to "必须使用 HTTPS",
    "Official endpoint preset" to "已预设官方端点",
    "Custom endpoints must use HTTPS. Cleartext network traffic is disabled." to "自定义端点必须使用 HTTPS；应用已禁用明文网络流量。",
    "Cockpit locks this provider to its official HTTPS endpoint. The API key remains encrypted locally." to "Cockpit 会固定使用该 Provider 的官方 HTTPS 端点，API Key 始终在本机加密保存。",
    "New Agent" to "新建智能体",
    "Create an identity" to "创建角色身份",
    "Identity" to "身份",
    "Who are you creating?" to "你想创建谁？",
    "Start with a clear name. Capabilities and providers remain separate." to "先设置一个清晰的名称；能力与 Provider 配置彼此独立。",
    "Agent name" to "智能体名称",
    "For example, Ada" to "例如：小航",
    "This name leads every conversation and can be changed later." to "该名称会显示在每个对话中，之后仍可修改。",
    "Creating…" to "正在创建…",
    "An Agent name is required." to "请输入智能体名称。",
    "The selected character card could not be opened." to "无法打开所选角色卡。",
    "The character card does not contain a valid name." to "角色卡中没有有效名称。",
    "The character card could not be imported." to "无法导入该角色卡。",
    "The character card does not contain a name." to "角色卡中没有名称。",
    "Choose a Tavern character card in PNG or JSON format." to "请选择 PNG 或 JSON 格式的酒馆角色卡。",
    "The PNG character card is damaged." to "PNG 角色卡已损坏。",
    "This PNG does not contain Tavern character metadata." to "该 PNG 不包含酒馆角色卡元数据。",
    "What this creates" to "将创建的内容",
    "One Agent identity with its own conversation collection. No execution permission is granted." to "创建一个拥有独立对话集合的智能体身份，不会授予任何执行权限。",
    "Agent detail" to "智能体详情",
    "New Conversation" to "新建对话",
    "Conversations" to "对话",
    "No active conversations yet." to "还没有活跃对话。",
    "A fresh start" to "新的开始",
    "Configuration" to "配置",
    "Capability facts for this Agent." to "查看该智能体的能力配置。",
    "Connect a model provider" to "连接模型 Provider",
    "Bind a provider profile to receive live model responses." to "绑定 Provider 账号后即可接收模型实时回复。",
    "Archived" to "已归档",
    "Restore a conversation without losing its messages." to "恢复对话时不会丢失消息。",
    "Local conversation Agent" to "本地对话智能体",
    "Active" to "活跃",
    "No provider" to "未配置模型服务",
    "Configured" to "已配置",
    "Not connected" to "未连接",
    "Not enabled" to "未启用",
    "Execution" to "执行能力",
    "Model provider" to "模型服务",
    "Ready to continue" to "可继续对话",
    "Archived conversation" to "已归档对话",
    "Restore" to "恢复",
    "Archive" to "归档",
    "Agent detail" to "智能体详情",
    "No workspace" to "无工作区",
    "Close" to "关闭",
    "Current conversation" to "当前对话",
    "Open conversation" to "打开对话",
    "Open archived conversation" to "打开已归档对话",
    "Conversation timeline" to "对话时间线",
    "The reply is streaming. Stop it at any time; partial text is never saved as a completed message." to "回复正在流式生成，可随时停止；未完成的内容不会保存为完整消息。",
    "Stop" to "停止",
    "Response stopped" to "回复已停止",
    "Provider response failed" to "模型服务响应失败",
    "API configuration changed" to "API 配置已修改",
    "This conversation's bound API configuration has changed." to "此会话绑定的 API 配置已修改",
    "Migrate this conversation to the current configuration" to "迁移此会话到当前配置",
    "API configuration not bound" to "API 配置未绑定",
    "This conversation has existing history and must be migrated explicitly." to "此会话已有历史记录，必须明确迁移后才能使用当前配置。",
    "Retry" to "重试",
    "Model not connected" to "尚未连接模型",
    "Messages are stored locally. Bind a provider for live responses." to "消息已保存在本机；绑定 Provider 后即可获得实时回复。",
    "Start the conversation" to "开始对话",
    "Write a message below. Your draft belongs only to this conversation." to "在下方输入消息；草稿仅属于当前对话。",
    "Thinking…" to "思考中…",
    "Streaming…" to "生成中…",
    "Partial • not saved" to "未完成 · 未保存",
    "Conversation is archived" to "对话已归档",
    "Restore it to write a new message." to "恢复后即可继续发送消息。",
    "Save draft" to "保存草稿",
    "Saving…" to "正在保存…",
    "Send" to "发送",
    "Sending…" to "正在发送…",
    "Draft saved" to "草稿已保存",
    "Draft could not be saved. Text is preserved." to "草稿保存失败，文本仍已保留。",
    "Message could not be sent. Text is preserved." to "消息发送失败，文本仍已保留。",
    "Navigation could not complete." to "无法完成页面跳转。",
    "Draft destination is stale. Send is disabled." to "草稿目标已过期，暂时无法发送。",
    "Composer destination is invalid. Actions are disabled." to "消息目标无效，相关操作已禁用。",
    "Connection not found." to "未找到该连接。",
    "Enter a configuration name." to "请输入配置名称。",
    "This authentication method is not valid for the selected protocol." to "该认证方式不适用于所选协议。",
    "Maximum output tokens must be between 1 and 131072." to "最大输出 Token 数必须介于 1 到 131072 之间。",
    "Enter an API key to replace the saved credential." to "请输入 API Key 以替换已保存的凭据。",
    "An API key is required for a new configuration." to "新配置必须填写 API Key。",
    "Enter the API key again to repair this configuration." to "请重新输入 API Key 以修复此配置。",
    "A new configuration requires an API key." to "新配置必须填写 API Key。",
    "Configuration revision is exhausted." to "该配置已无法继续更新，请新建配置。",
    "Configuration saved. It remains unverified until you run a conversation test." to "配置已保存，运行对话测试前仍标记为未验证。",
    "Use a valid HTTPS API prefix." to "请输入有效的 HTTPS API 基础地址。",
    "Invalid API address" to "API 地址无效。",
    "HTTPS is required" to "必须使用 HTTPS。",
    "A host is required" to "必须填写主机地址。",
    "Credentials, fragments, and query parameters are not allowed in the API address" to "API 地址中不能包含凭据、片段或查询参数。",
    "An API prefix is required" to "必须填写 API 基础地址。",
    "An endpoint suffix is required" to "必须填写端点路径。",
    "The configuration could not be saved safely." to "无法安全保存该配置。",
    "A configuration name is required." to "必须填写配置名称。",
    "An API key is required." to "必须填写 API Key。",
    "This key is duplicated in the current batch." to "当前批次中存在重复的 Key。",
    "Enter the API key again before enabling this configuration." to "启用此配置前，请重新输入 API Key。",
    "The saved API key needs to be repaired before enabling." to "启用前需要修复已保存的 API Key。",
    "Enable at least one model before enabling this configuration." to "请至少启用一个模型后再启用此配置。",
    "Configuration enabled." to "配置已启用。",
    "Configuration disabled." to "配置已停用。",
    "Configuration not found." to "未找到该配置。",
    "Configuration and encrypted credential deleted." to "配置及其加密凭据已删除。",
    "The configuration could not be deleted." to "无法删除该配置。",
    "The configuration is invalid. Edit and save it again." to "该配置无效，请编辑后重新保存。",
    "The provider returned an empty model list. You can add an exact model ID manually." to "服务商返回的模型列表为空，你仍可手动添加精确模型 ID。",
    "You can still add a model ID manually." to "你仍可手动添加模型 ID。",
    "Add a model ID manually." to "请手动添加模型 ID。",
    "Enter an exact model ID on one line." to "请在单行中输入精确模型 ID。",
    "Model added and enabled." to "模型已添加并启用。",
    "Model not found." to "未找到该模型。",
    "This model is in use. Change the affected route before disabling it." to "该模型正在使用中，请先更改相关路由再停用。",
    "Model enabled." to "模型已启用。",
    "Model disabled." to "模型已停用。",
    "Choose an enabled model from this configuration." to "请选择此配置中已启用的模型。",
    "Preferred model updated. The global default was not changed." to "首选模型已更新，全局默认模型未更改。",
    "Global default cleared." to "已清除全局默认模型。",
    "Choose an enabled configuration." to "请选择已启用的配置。",
    "Choose an enabled model from that configuration." to "请选择该配置中已启用的模型。",
    "The selected configuration does not have a usable saved credential." to "所选配置没有可用的已保存凭据。",
    "Global default model updated." to "全局默认模型已更新。",
    "Choose a model before running a conversation test." to "运行对话测试前请选择模型。",
    "The request completed but returned no visible text; the result is inconclusive." to "请求已完成，但没有返回可见文本，暂时无法判断结果。",
    "The conversation test ended before completion." to "对话测试未完成便已结束。",
    "Choose a model for this Agent." to "请为此智能体选择模型。",
    "Choose an enabled model for this Agent." to "请为此智能体选择已启用的模型。",
    "Model route assigned to Agent." to "模型路由已分配给智能体。",
    "The model route could not be assigned to this Agent." to "无法将模型路由分配给此智能体。",
    "The provider rejected this API key." to "服务商拒绝了此 API Key。",
    "The credential lacks permission for this endpoint or model." to "该凭据无权访问此端点或模型。",
    "The provider rate limit was reached." to "已达到服务商的请求频率限制。",
    "The provider reported insufficient quota or balance." to "服务商提示配额或余额不足。",
    "This conversation is larger than the model context limit." to "此对话已超出模型的上下文限制。",
    "The provider request timed out." to "模型服务请求超时。",
    "The provider is temporarily unavailable." to "模型服务暂时不可用。",
    "The endpoint or selected protocol was not found." to "未找到该端点或所选协议。",
    "The exact model ID is unavailable or not permitted." to "该精确模型 ID 不可用或无权访问。",
    "The provider rejected a parameter for this model or protocol." to "服务商拒绝了此模型或协议的某个参数。",
    "The provider rejected this request or model configuration." to "服务商拒绝了此请求或模型配置。",
    "The provider returned an unexpected response." to "模型服务返回了意外响应。",
    "The response was stopped." to "回复已停止。",
    "A secure TLS connection to the provider could not be established." to "无法与模型服务建立安全的 TLS 连接。",
    "The provider could not be reached. Check the network and endpoint." to "无法连接模型服务，请检查网络和端点。",
    "The provider request failed safely." to "模型服务请求失败，未暴露敏感信息。",
    "The provider credential is unavailable or already consumed." to "模型服务凭据不可用或已被使用。",
    "The Anthropic credential is unavailable or already consumed." to "Anthropic 凭据不可用或已被使用。",
    "The saved provider credential is unavailable. Save the API key again." to "已保存的模型服务凭据不可用，请重新保存 API Key。",
    "The bound provider profile is invalid. Edit and save it again." to "已绑定的模型服务配置无效，请编辑后重新保存。",
    "The model list response was not valid JSON." to "模型列表响应不是有效的 JSON。",
    "The model list response was too large." to "模型列表响应过大，已停止处理。",
    "The provider sent an oversized streaming event." to "模型服务返回的流式事件过大，已停止处理。",
    "The provider response exceeded the local safety limit." to "模型回复已超过本机安全上限，已停止接收。",
    "The provider stream ended early." to "模型服务的流式响应提前结束。",
    "The provider sent malformed JSON." to "模型服务返回了格式错误的 JSON。",
    "The provider reported that the response failed." to "模型服务报告本次响应失败。",
    "Anthropic reported that the response failed." to "Anthropic 报告本次响应失败。",
    "The Anthropic stream ended before message_stop." to "Anthropic 流式响应在 message_stop 前结束。",
    "Anthropic sent a malformed streaming event." to "Anthropic 返回了格式错误的流式事件。",
    "The provider completed without a text response." to "模型服务已完成响应，但没有返回文本。",
    "The response arrived, but the conversation changed before it could be saved." to "回复已到达，但对话已发生变化，因此没有保存。",
    "The response was stopped. Partial text was not saved as a message." to "回复已停止，未将部分文本保存为消息。",
    "The response was interrupted. Partial text was not saved as a message." to "回复已中断，未将部分文本保存为消息。",
    "Choose an image smaller than 30 MiB." to "请选择小于 30 MiB 的图片。",
    "Choose a valid image no larger than 4096 × 4096." to "请选择尺寸不超过 4096 × 4096 的有效图片。",
    "The selected image could not be decoded." to "无法解码所选图片。",
    "The avatar directory could not be created." to "无法创建头像目录。",
    "Accepted" to "已接受",
    "Delivered" to "已送达",
    "Failed" to "失败",
)
