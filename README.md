# Mobile Cockpit

Mobile Cockpit 是一个 Android 原生个人智能体客户端。应用采用本地优先、用户自带 API Key（BYOK）的方式，支持创建智能体、导入 Tavern 角色卡、管理模型连接，并在会话中固定使用明确的连接与模型。

## 当前能力

- 对话、智能体、动态与设置四个一级入口
- 智能体创建、编辑、草稿恢复和 Tavern 角色卡导入/导出
- DeepSeek、OpenAI、Gemini、GLM、Claude 与自定义模型服务商
- 同一服务商配置多个独立 API 账号，以及批量添加账号
- OpenAI Responses、OpenAI Chat Completions 与 Anthropic Messages 协议
- 远程模型发现、手动添加模型、启用状态、首选模型和全局默认模型
- 全局、智能体与会话三级精确模型路由；已开始的会话不会随机切换账号
- 中文与英文界面、浅色/深色/跟随系统主题

## 安全约束

- API Key 由 Android Keystore 保护并仅保存在设备本地
- 数据库和界面只保存或展示脱敏后的密钥提示
- 应用禁止明文 HTTP，Provider 地址必须使用 HTTPS
- Provider 密钥和本地数据库均排除在 Android 备份与设备迁移之外
- 仓库不包含 API Key、签名文件或其他本地凭据

## 技术栈

- Kotlin、Jetpack Compose、Material 3
- Room 3 与 Bundled SQLite
- Kotlin Coroutines、Kotlin Serialization
- OkHttp 5
- Gradle Kotlin DSL、JDK 17

项目采用模块化单体与 Ports/Adapters 边界。核心模块不依赖 Android，平台实现、Provider 适配、持久化、安全能力和 UI 分别位于独立 Gradle 模块中。架构决策记录见 [`docs/adr`](docs/adr)。

## 本地构建

准备 JDK 17，以及包含项目所需平台的 Android SDK，然后执行：

```shell
./gradlew :app:assembleDebug
```

Windows 可使用：

```powershell
.\gradlew.bat :app:assembleDebug
```

调试 APK 生成于 `app/build/outputs/apk/debug/app-debug.apk`。`local.properties`、构建目录、APK、本地截图与凭据文件均已通过 `.gitignore` 排除。

## 仓库说明

当前仓库未声明开源许可证。除非仓库所有者另行授权，不应视为允许复制、分发或再许可。
