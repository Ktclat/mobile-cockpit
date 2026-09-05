# Mobile-Native Personal Agent Runtime — v0.1 Architecture Baseline

- Version: 0.1
- Status: Architecture Baseline Approved
- Date: 2026-08-29
- Initial product wedge: Mobile AI SSH Agent
- Primary platform: Android
- Document role: v0.1 产品、领域语义、安全边界和 Runtime 行为的 authoritative source of truth
- Freeze status: Frozen；任何 Baseline-level 变更必须通过 `Architecture Baseline Change Request`

本文件合并并取代此前分别讨论的：

- Mobile AI SSH Agent — MVP v0.1 Product & Runtime Specification
- Agent / Conversation / Persona Information Architecture v0.1

本文件不是 System Architecture、Conversation & Execution UX Specification 或 Implementation Plan。后续文档必须引用本 Approved Baseline，不得重新定义这里已经锁定的产品语义、安全边界、Runtime 行为和领域关系。

除非下游设计发现真正的 Baseline-level contradiction，否则不得修改本文件已经批准的语义。需要改变时必须单独提出 `Architecture Baseline Change Request`，说明冲突、影响、不变量变化和迁移后果；不得在 System Architecture、UX Specification 或 Implementation Plan 中静默改变。

---

## 1. Executive Summary

### 1.1 对外产品定位

首发产品是：

> 手机上的 AI SSH Agent。用户描述服务器问题，Agent 在手机上自主诊断；涉及修改时展示精确操作并请求审批；执行后继续验证，最后给出可审计的结果。

它解决的不是“在手机上打开另一个聊天窗口”，而是：

> 描述目标 → Agent 收集证据 → 提出操作 → 用户审批 → Agent 执行 → Agent 验证。

目标服务器只需要能够被手机通过 SSH 访问，不需要部署额外 Agent daemon，也不需要一台常开的个人电脑。

### 1.2 内部产品定位

内部长期定位是：

> Mobile-Native Personal Agent Runtime

同一套 Runtime 未来可以承载：

- Server Agent
- Personal Assistant
- Coding Agent
- Character Agent
- 其他 Skill-based Agent

产品策略是“窄入口、宽底座”：

- v0.1 对外聚焦 AI SSH。
- 底层以 Agent-first Domain 建模。
- Tavern、Character Card、长期 Memory 等不进入 SSH MVP。
- 当前设计必须允许未来增加这些能力，而不提前实现空壳子系统。

### 1.3 核心产品原则

- Agent-first，不是 Chat-first，也不是 Task-manager-first。
- 手机负责 Agent 状态、审批、SSH 执行、审计和恢复。
- 模型可以来自云端或用户自定义 Provider。
- 模型负责提出计划和操作，不拥有最终权限。
- 所有实际 SSH 操作在手机上可见。
- 修改服务器前必须经过确定性的 Permission Engine。
- 只有独立 Verification 通过后，修复型 Run 才能宣告完成。

---

## 2. Target User, Problem, Value and Success

### 2.1 目标用户

首批用户：

- 独立开发者
- 自托管服务用户
- Homelab 玩家
- 小型团队中偶尔需要处理服务器问题的人
- 具备基础 SSH 概念，但不希望在手机上手敲复杂命令的人

v0.1 暂不面向需要多人审批、RBAC、合规审计和大规模编排的企业 SRE 团队。

### 2.2 用户问题

用户离开电脑后遇到服务器问题，目前通常只能：

- 在手机终端中手工排查。
- 把日志复制给 AI，再把命令复制回终端。
- 远程连接一台常开的电脑。
- 在服务器部署一个新的常驻 Agent。

这些方案要么交互成本高，要么增加基础设施和安全负担。

### 2.3 核心价值

- 无需电脑常开。
- 无需在目标服务器安装运行时。
- AI 不只回答问题，而是完成具有证据链的任务。
- 所有命令、输出、审批、状态和验证均在手机可见。
- 模型不能绕过 Permission Engine。
- App 进入后台或进程被终止后，任务可以安全恢复。

### 2.4 杀手级场景

用户说：

> 我的生产服务器 Nginx 返回 502，帮我找出原因并修好。

Agent 自动检查连接、服务状态、配置、日志、监听端口和上游服务；定位问题后展示修复动作；用户批准；Agent 执行并通过配置检查、服务状态和 HTTP 请求验证结果。

### 2.5 MVP 成功标准

发布门槛：

- 未经授权的服务器修改数量必须为 0。
- 修改失败、断线或进程被终止后，不会盲目重复执行。
- 所有实际操作均有结构化记录。
- 不能只依据模型陈述或命令退出码宣称修复成功。
- 用户从配置到完成首个任务不需要电脑或外部终端。

建议早期验证指标：

- 受控测试环境 Golden Path 成功率达到 90%。
- 10 名目标用户中至少 7 名能独立完成一次真实服务器任务。
- 首次成功任务的中位时间不超过 10 分钟。
- 安全测试中未经审批的修改操作数量为 0。

---

## 3. v0.1 Assumptions and Boundaries

### 3.1 平台与运行位置

- Android-first。
- Agent Core、Permission、Task/Run 状态、SSH 客户端和审计数据位于手机。
- 模型推理可以是远端 API，也可以是用户配置的兼容端点。
- 目标服务器不安装本产品的 daemon。
- 手机必须能通过公网、局域网或用户自己的 VPN 访问 SSH。

### 3.2 执行假设

- MVP 同一时间只允许一个活跃 Run。
- 可以保存任意数量的 Agent、Conversation、Task 和历史 Run。
- MVP 主要面向具有非交互式 shell 的 Linux/Unix SSH 目标。
- 真实执行要求模型支持可靠的结构化 Tool Call。
- 不从普通 Markdown 代码块猜测并执行命令。
- 需要 sudo 时优先依赖服务器预配置的非交互式 sudo。
- 复杂交互式 TTY、MFA、Jump Host 和端口转发不属于 MVP。

### 3.3 文档范围

本 Baseline 定义：

- 产品定位和 MVP 范围
- 领域实体和不变量
- Agent Runtime 的逻辑职责
- Task/Run 状态与恢复语义
- Skill、SSH、Permission、Verification 语义
- Persistence 的领域事实
- Agent-first 移动端信息架构

本 Baseline 不定义：

- 具体代码模块和依赖注入方式
- 线程、协程、队列和调度器的实现
- 数据库、SSH 或 UI 库选型
- Conversation 页面的最终交互与视觉细节
- 实现步骤和工程任务拆分

---

## 4. Architecture Invariants

以下关系是正式不变量：

~~~text
Agent != Conversation
Conversation != Task
Message != Task
Task != Run
Run != Message
Persona != Permission
Persona != Skill
Workspace != Credential
Conversation Summary != Domain Fact
Model Output != Permission Decision
Message != ModelInvocation
~~~

附加不变量：

1. Agent 是一级实体，可以拥有多个 Conversation。
2. Persona 只能影响身份、表达和行为倾向，不能授予 Capability。
3. Skill 决定 Agent 可以提出什么能力，Permission 决定本次是否允许执行。
4. Workspace 表示外部工作环境，不保存 Credential。
5. Run 是具有状态、副作用、审批、取消、恢复和验证语义的执行实体。
6. Conversation Timeline 是 Message 和 Task Projection 的组合，不是 Everything-as-Message。
7. Run 内的 Plan、ToolCall、stdout、stderr、Permission 和 Verification 不产生普通聊天 Message。
8. 只有需要真实语言交互时才产生 RUN_QUESTION 或 RUN_RESULT Message。
9. Run Snapshot 创建后不可变。
10. Conversation Summary 是可重新生成的有损缓存，不能代替 Message、Task、Run、Permission 或 Verification 的结构化事实。
11. 模型可以建议风险级别，但不能授予权限或降低 Runtime Policy。
12. Runtime Safety Policy 的优先级高于 Persona、Conversation、Memory 和服务器输出。
13. v0.1 只有 Runtime 能证明属于 Safe Read Profile 的 SSH 操作才允许自动 ALLOW；无法证明安全的操作不得进入 ALLOW。
14. Execution Risk 与 Data Sensitivity 是两个独立维度；允许执行不代表允许把结果发送给模型 Provider。
15. Credential exclusion 不足以保护数据；任意服务器输出均是不可信 Observation，并且可能包含秘密。
16. 模型 Tool Proposal 必须先被规范化为一个不可变 Execution Envelope；风险分析、审批和最终执行引用同一 Envelope 和 Digest。
17. 被分析、被批准和被执行的动作，在同一个 canonical Execution Envelope 下必须保持字节及语义一致。
18. Run Snapshot 创建后的用户回答、Permission Decision、Credential recovery 和预算扩展均作为 append-only runtime input 保存，不得修改 Snapshot。
19. 在 source Run 及其 reconciliation flow 中，未解决的 UNKNOWN_OUTCOME 阻止任何后续 mutating ToolCall；跨 Run 阻断范围按第 24 条执行。
20. Verification 的高可信 PASS 必须来自可审计证据和明确 Evaluator；模型自然语言声明本身不能产生高可信 PASS。
21. 已向用户展示并用于批准修改的关键 Success Criteria 或 Verification Plan，不得在执行后被 Agent 静默削弱。
22. 已批准 SSH Envelope 中冻结的 SSH target 必须与实际连接目标完全一致；SSHHost 可变记录不得在审批后重定向执行。
23. Run Snapshot 冻结历史配置，但不能阻止当前 Runtime Mandatory Safety Floor 对旧 Run 应用更严格限制；当前规则只能收紧，不能静默放宽 Snapshot 中的安全约束。
24. 未解决的外部副作用不会因 Cancel Run、Run 终态或 Retry 而消失；在建立远端真实状态前，它会阻断后续 Run 中可能冲突的修改。

### 4.1 v0.1 SSH 执行不变量

v0.1 中：

> 所有 SSH Skill Invocation 必须发生在 Run 中，并且 Run 必须属于 Task。普通 Message 永远不能直接调用 SSH。

这包括只读 SSH 操作，例如 df、service status 和读取普通日志，因为它们仍然需要：

- Workspace binding
- 状态管理
- 审计
- Cancellation
- Failure handling
- Runtime budget
- Tool records

这不是对未来所有 Skill 的永久建模承诺。

更普遍的长期原则是：

> Any privileged or externally-effectful capability invocation must execute through the Runtime execution boundary and may never bypass Permission or Audit infrastructure.

当第二种真实 Skill 类型出现时，再评估轻量查询是否需要独立执行实体。v0.1 不创建 InlineToolExecution 等未来实体。

### 4.2 v0.1 Workspace 稳定性

v0.1 中：

> 一个执行型 Conversation 使用一个稳定的 Workspace context。切换 Workspace 默认创建同一 Agent 下的新 Conversation。

真正的安全事实位于：

~~~text
Conversation.workspaceId
    = 默认和上下文环境

Task.workspaceId
    = 本次目标实际作用的 Workspace

Run.workspaceSnapshot
    = 本次执行最终冻结的安全事实
~~~

数据库和长期 Domain 不建立“Conversation 永远只能涉及一个 Workspace”的不可演进约束。v0.1 只在产品行为上限制已有执行型 Conversation 原地切换环境。

---

## 5. Domain Model

~~~mermaid
erDiagram
    AGENT ||--|| PERSONA : owns
    AGENT ||--o{ CONVERSATION : owns
    AGENT }o--o{ SKILL : enables
    PERMISSION_POLICY ||--o{ AGENT : governs
    PROVIDER_PROFILE ||--o{ AGENT : serves
    WORKSPACE ||--o{ AGENT : default_for

    CONVERSATION ||--o{ MESSAGE : owns
    CONVERSATION ||--o{ TASK : owns
    WORKSPACE ||--o{ CONVERSATION : contextualizes
    CONVERSATION ||--o| CONVERSATION_SUMMARY : summarizes

    TASK ||--o{ RUN : attempts
    WORKSPACE ||--o{ TASK : targets
    RUN ||--|| RUN_SNAPSHOT : freezes
    RUN ||--o{ PLAN_VERSION : owns
    RUN ||--o{ MODEL_INVOCATION : uses
    RUN ||--o{ TOOL_CALL : owns
    RUN ||--o{ PERMISSION_REQUEST : owns
    RUN ||--o{ VERIFICATION_RECORD : owns
    RUN ||--o| FINAL_REPORT : produces
~~~

### 5.1 领域语义

| 实体 | 核心语义 |
|---|---|
| Agent | 谁在与用户交互，以及配置了哪些能力 |
| Persona | Agent 如何表现、表达和塑造身份 |
| Conversation | 用户与 Agent 的一段持续上下文 |
| Message | 产品层双方实际表达的内容 |
| Task | Conversation 中需要完成的具体目标 |
| Run | Task 的一次实际执行尝试 |
| Skill | Agent 可以做什么 |
| Permission Policy | Agent 本次被允许做什么 |
| Workspace | Agent 正在处理的外部环境 |
| Memory | 哪些信息可以跨模型调用保留 |
| ModelInvocation | Runtime 对 Provider 的一次模型调用 |

### 5.2 Cardinality 与 Ownership

| 关系 | 基数 | 所有权 |
|---|---:|---|
| Agent → Persona | 1 : 1 | Persona 由 Agent 拥有 |
| Agent → Conversation | 1 : N | Conversation 由 Agent 拥有 |
| Agent → Skill | N : M | Agent 拥有 SkillBinding，不拥有 Skill 定义 |
| Agent → PermissionPolicy | N : 1 | 引用 |
| Agent → ProviderProfile | N : 1 | 引用 |
| Agent → Default Workspace | N : 0..1 | 引用 |
| Conversation → Message | 1 : N | Message 由 Conversation 拥有 |
| Conversation → Task | 1 : N | Task 由 Conversation 拥有 |
| Conversation → Workspace | N : 0..1 | 上下文引用 |
| Conversation → Summary | 1 : 0..1 | Summary 由 Conversation 拥有 |
| Task → Workspace | N : 0..1 | 目标引用 |
| Task → Run | 1 : N | Run 由 Task 拥有 |
| Run → Snapshot | 1 : 1 | Snapshot 由 Run 拥有 |
| Run → Plan/Tool/Permission/Verification | 1 : N | 全部由 Run 拥有 |

Archive 不触发级联删除。只有用户明确执行永久 Erase 时，才删除 owned children。Workspace、Provider、Host 和 Credential 是独立资源，不随 Agent 或 Conversation 删除。

---

## 6. Agent

### 6.1 正式实体名称

领域模型只使用 Agent，不再同时存在 AgentProfile。

Agent 表示持续存在的数字工作主体：

~~~text
Agent
= Persona
+ Model Selection
+ Skill Bindings
+ Permission Policy
+ Memory Policy
+ Optional Default Workspace
~~~

Agent 不保存 Conversation Message、Run 状态或 Credential。

### 6.2 MVP 字段

- id
- personaId
- providerProfileId
- modelId
- skillBindings
- permissionPolicyId
- memoryPolicy
- defaultWorkspaceId，可空
- revision
- lifecycleStatus
- createdAt
- updatedAt
- archivedAt，可空
- createdFromTemplateId，可空，仅作来源元数据

### 6.3 生命周期

~~~text
DRAFT → ACTIVE → ARCHIVED
          ↑          │
          └──────────┘ restore
~~~

- DRAFT：创建流程尚未完成。
- ACTIVE：可以创建 Conversation 和 Task。
- ARCHIVED：不显示在主要列表，不允许创建新 Conversation 或 Run。

存在活跃 Run 时不能直接归档 Agent，用户必须先返回、暂停或取消该 Run。

### 6.4 Revision

以下变更增加 Agent revision：

- Persona 修改
- 模型修改
- SkillBinding 修改
- Permission Policy 修改
- Memory Policy 修改
- Default Workspace 修改

已有 Run 不受影响。普通聊天回复记录使用的 Agent revision；真实执行冻结完整 Run Snapshot。

---

## 7. Persona

Persona 是 Agent 拥有的、独立且可版本化的身份与表达实体。

### 7.1 MVP 字段

- id
- displayName
- avatarRef，可空
- description，可空
- systemPrompt，可空
- greeting，可空
- schemaVersion
- extensions
- revision
- createdAt
- updatedAt

### 7.2 规则

- 一个 Agent 任意时刻拥有一个生效 Persona。
- Persona 默认不跨 Agent 共享可变实例。
- Duplicate Agent 时复制 Persona。
- Persona Library 未来采用复制或导入，不默认共享可变 Persona。
- extensions 是有大小限制、命名空间化的纯数据。
- 未被 Runtime 明确识别的 extension 不传给模型，也不参与任何安全决策。

### 7.3 Capability 隔离

Persona 永远不能：

- 添加 Skill
- 修改 Permission Policy
- 绑定 Workspace
- 访问 Credential
- 修改 Runtime Safety Policy
- 因文本声明而获得系统权限

安全优先级：

~~~text
Runtime Safety Policy
    >
Effective Permission Policy
    >
Skill Capability Boundary
    >
Persona
    >
Conversation / Memory / Imported Content / Tool Output
~~~

---

## 8. Conversation

Conversation 是用户与一个 Agent 的持续上下文。

### 8.1 MVP 字段

- id
- agentId
- title
- workspaceId，可空
- lifecycleStatus
- lastActivityAt
- createdAt
- archivedAt，可空

### 8.2 规则

- Conversation 必须属于一个 Agent。
- 可以有零到多个 Message。
- 可以有零到多个 Task。
- 可以绑定零或一个默认 Workspace context。
- Conversation 没有 COMPLETED 状态。
- Conversation 可以长期继续、归档和恢复。

### 8.3 创建流程

入口：

- Agent Detail → New Conversation
- Home → New Conversation → 选择 Agent
- 创建 Agent 完成后 → Start Conversation
- Agent Detail → Continue；若没有最近 Conversation，则创建

流程：

1. 读取当前 Agent 配置。
2. 使用用户显式 Workspace；否则使用 Agent Default Workspace；否则为 null。
3. 创建 ACTIVE Conversation。
4. Persona greeting 非空时追加 PERSONA_GREETING Message。
5. 用户发送第一条有效消息。
6. 生成简短标题，用户可以重命名。

没有 Message、Task 或 Greeting 的空 Conversation 可以直接删除。

### 8.4 Workspace context

- Agent Default Workspace 仅在创建 Conversation 时作为默认值。
- Agent 后续修改默认 Workspace，不影响已有 Conversation。
- v0.1 中 Conversation 产生首个 Task 后，UI 不允许直接切换 Workspace。
- 用户选择另一个 Workspace 时，默认创建同一 Agent 下的新 Conversation。
- 长期模型仍允许未来支持多环境 Workflow，因为 Task 和 Run 分别保存自己的 Workspace 事实。

---

## 9. Message and ModelInvocation

### 9.1 Message

Message 属于产品 Conversation Domain。

MVP 字段：

- id
- conversationId
- role：USER 或 ASSISTANT
- source
- content
- status
- ordinal
- agentRevision，可空
- relatedTaskId，可空
- relatedRunId，可空
- createdAt
- completedAt，可空

source：

- USER_INPUT
- NORMAL_RESPONSE
- PERSONA_GREETING
- RUN_QUESTION
- RUN_RESULT

助手流式 Message 状态：

~~~text
STREAMING → COMPLETED
          → FAILED
          → CANCELLED
~~~

提交后的 Message 不原地覆盖。

### 9.2 ModelInvocation

ModelInvocation 属于 Agent Runtime / Provider Domain。

它表示一次模型请求、流式响应和结果，不是产品聊天消息。

二者不是一一对应：

- 一条 Assistant Message 可能经历多次重试。
- 一个 Run 可以拥有多次 ModelInvocation，但只产生一条 RUN_RESULT Message。
- Tool Proposal 可以不产生 Message。
- Provider stream chunk 不是 Message。

正式 Persistence 不使用含义模糊的 Turn 取代 Message 或 ModelInvocation。

---

## 10. Task

Task 是 Conversation 中需要 Runtime 真正完成的目标。

### 10.1 MVP 字段

- id
- conversationId
- originMessageId
- title
- goal
- successCriteria
- workspaceId，可空
- status
- createdAt
- resolvedAt，可空
- closedAt，可空
- closeReason，可空

### 10.2 状态

- OPEN：仍可创建或继续 Run。
- RESOLVED：至少一个 Run 已通过必要 Verification。
- CLOSED：用户放弃、能力不支持或决定不再重试。

运行中、等待审批、暂停等状态来自最新 Run，不重复存储在 Task status。

### 10.3 普通 Chat 何时升级为 Task

| 用户意图 | v0.1 行为 |
|---|---|
| 解释 Nginx 是什么 | 普通 Message |
| 讨论通常如何排查 502 | 普通 Message |
| 检查 production 为什么 502 | 创建 Task |
| 登录服务器查看磁盘 | 创建 Task |
| 修复并验证 | 创建 Task |
| 询问“如果修你会怎么做” | 普通 Message，不连接服务器 |
| 目标不明确 | Agent 先提问 |

升级不会改变原始 Message，而是创建引用 originMessageId 的 Task。

v0.1 中只要需要调用 SSH Skill，就必须创建 Task 和 Run。该规则只对当前 SSH Runtime 做出承诺，不预判所有未来轻量 Skill 的产品语义。

### 10.4 创建 Run 的前置条件

Runtime 先确定：

- 所需 Skill
- Workspace
- Agent 当前能力
- Permission Policy
- 模型是否支持结构化工具调用

如果 Workspace 缺失，Task 可以保持 OPEN 并要求用户选择 Workspace；关键绑定完成后再创建不可变 Run Snapshot。

---

## 11. Conversation Timeline

Conversation Timeline 是展示投影：

~~~text
Conversation Timeline
= Message
+ Task Projection
~~~

推荐使用轻量 ConversationTimelineEntry 保证排序：

- conversationId
- ordinal
- itemType
- itemId
- createdAt

v0.1 itemType：

- MESSAGE
- TASK

TimelineEntry 不保存 Message 或 Run 的业务状态；领域事实仍来自 Message、Task、Run 和 Run Event Log。

### 11.1 Task Card

Task Card 在 Timeline 中保持稳定位置：

~~~text
User Message

Task Card
├── Goal
├── Current Run State
├── Plan
├── Current Step
├── Latest Observation
├── Tool Call
├── Permission
└── Verification

Assistant RUN_RESULT Message
~~~

同一个 Task 多次尝试时，Task Card 默认展示最新 Run，并允许展开历史 Run。

### 11.2 哪些内容不是普通 Message

- Plan Update
- Tool Call
- stdout / stderr
- Permission Request / Decision
- Verification
- Provider stream
- Agent 内部运行状态

只有真正需要用户语言交互时才创建：

- RUN_QUESTION
- RUN_RESULT

Permission Decision 始终是结构化安全实体，不伪装成聊天文本。

---

## 12. Workspace

Workspace 是独立聚合根，表示 Agent 当前处理的外部工作环境。

### 12.1 MVP 字段

- id
- name
- primaryHostId
- environment：development、staging 或 production
- defaultWorkingDirectory，可空
- contextDescription，可空
- tags
- permissionPolicyOverrideId，可空
- defaultVerificationHints，可空
- revision
- lifecycleStatus
- createdAt
- updatedAt
- archivedAt，可空

Workspace 不保存密码、私钥或 API Key，只保存 Credential 和 Host 的引用。

### 12.2 三层语义

Conversation.workspaceId：

- 默认和上下文执行环境。
- 可以为空。
- v0.1 中产生首个 Task 后保持稳定。

Task.workspaceId：

- 本次目标实际作用的 Workspace。
- 从 Conversation 复制，但作为 Task 自己的明确引用保存。
- 长期允许未来 Workflow 选择不同 Workspace。

RunSnapshot.workspace：

- 最终执行的不可变安全事实。
- 包含 Workspace revision、Host、用户、环境类别、目录、上下文和风险标签。

### 12.3 归档

Workspace 被归档后：

- 历史 Run 仍可通过 Snapshot 查看。
- 不允许用于新 Conversation 或新 Run。
- 用户需要重新启用 Workspace 才能继续创建新的执行。

---

## 13. Run and Run Snapshot

Run 是 Task 的一次真实执行尝试。

### 13.1 Run Snapshot

创建 Run 时冻结：

Agent：

- Agent ID 和 revision
- Persona ID、revision 和运行字段
- Agent 显示身份

Model：

- Provider Profile ID
- Base URL 指纹
- Model ID
- Tool capability
- Context configuration
- Adapter version

Skill：

- 启用 Skill
- Skill version
- Capability
- Binding configuration

Historical Policy Snapshot：

- Agent Policy version
- Workspace override
- Run 创建时的 Runtime Mandatory Safety Policy version
- Run 创建时解析出的安全约束和 Effective Policy
- policy provenance，区分用户可配置策略与 Runtime 强制规则

Workspace：

- Workspace ID 和 revision
- Host Reference
- 环境类别
- 用户和默认目录
- contextDescription
- Host Key fingerprint
- 风险标签

Context：

- Task goal
- successCriteria
- Conversation Summary version
- sourceThroughOrdinal
- 必要 Message reference

Runtime：

- Runtime version
- policy schema version
- historical Runtime Mandatory Safety Policy version
- snapshot creation time

Snapshot 只保存 Credential Reference，不保存秘密。每次实际连接记录使用的 Credential ID 和 rotation version。

### 13.2 修改后的配置

- Agent、Persona、Skill、Policy 或 Workspace 的后续修改不改变已有 Snapshot。
- 暂停 Run 恢复时继续使用原 Snapshot 中的历史配置，但每次执行仍必须应用当前 Runtime Mandatory Safety Floor。
- 凭证失效后可以在用户处理后使用同一逻辑 Credential 的新 rotation version；秘密本身不进入 Snapshot。

Snapshot 的 immutable 语义冻结历史事实和用户可配置策略，不冻结已知安全漏洞，也不赋予旧 Runtime 规则永久执行权。Runtime 升级后的强制限制不会回写旧 Snapshot，而是在执行时与历史约束共同求值。

### 13.3 Post-snapshot Runtime Inputs

Run Snapshot 只描述 Run 创建时的初始、不可变事实。Run 创建后仍然可以接收：

- RUN_QUESTION 的用户回答
- Permission Decision
- 用户对 SENSITIVE Observation egress 的决定
- Credential recovery 结果和 rotation version
- Budget extension
- Resume、Pause 或 Cancel Run 请求
- 其他明确的 runtime interaction

这些输入必须是有顺序、append-only 的事实或事件，不能回写或覆盖 Run Snapshot。它们可以参与后续 Working Context，也可以改变 Run 当前状态，但不能改变“Run 最初在什么配置和目标下创建”这一事实。

正式恢复语义：

~~~text
Initial immutable Run Snapshot
+
ordered append-only runtime inputs
+
ordered runtime events
=
recoverable current Run context
~~~

具体采用通用 Event Log 还是专门 RunInput persistence，由后续 System Architecture 决定。Baseline 只要求输入可审计、可排序、可恢复，并且不能通过所谓“更新 Snapshot”掩盖历史变化。

如果用户需要实质改变 Workspace、Skill、Agent 安全配置或 Task 目标，使原 Snapshot 不再适用，应创建新的 Run，必要时创建新的 Task，而不是修改旧 Snapshot。

恢复出历史和当前 Run context 后，Runtime 在允许执行前还必须叠加当前安全事实：

~~~text
recoverable current Run context
+ Current Runtime Mandatory Safety Floor evaluation
+ cross-run unresolved side-effect obligation projection
= recoverable execution-safe current context
~~~

后两项以当前规则和跨 Run 历史 events 重新计算；计算结果追加为新 runtime facts，不成为 Snapshot 的可变字段。

### 13.4 Historical Policy and Current Runtime Safety Floor

Run 恢复和每次执行时的有效安全语义为：

~~~text
Frozen historical Run constraints
+
Current Runtime Mandatory Safety Floor
=
most restrictive effective execution-time safety
~~~

两类策略必须明确分开：

- Historical Policy Snapshot：Run 创建时的 Agent policy、Workspace override、Runtime safety version 和解析结果，用于还原当时配置与审计历史。
- Current Runtime Mandatory Safety Floor：当前安装 Runtime 不可由 Agent、Persona、用户普通审批或旧 Snapshot 绕过的最低安全要求。

Current Runtime Mandatory Safety Floor 至少覆盖：

- Runtime hard DENY rules
- Safe Read Profile 及其 validators
- Execution Envelope schema、canonicalization 和 target validators
- critical-operation rules
- Observation / Egress Guard hard blocks
- 本 Baseline 的 mandatory safety invariants

合并规则是确定性的“只能收紧”：

- Permission 决策按 `DENY > ASK > ALLOW` 取更严格结果。
- Safe Read 自动执行资格必须同时满足历史冻结约束和当前 Safe Read Profile；任一侧不能证明安全时不得 ALLOW。
- 当前 validator 新增限制、hard DENY 或 Egress hard block 必须立即适用于旧 Run。
- 当前 Runtime 不能因为规则更新为更宽松而削弱 Snapshot 冻结的 ASK、DENY、路径限制、权限限制或数据限制。
- 历史约束无法被当前 Runtime 可靠解释或复现时 fail closed，进入 ASK、DENY、WAITING_USER 或 PAUSED，不得猜测为 ALLOW。

示例：

| Frozen constraint | Current floor | Effective result |
|---|---|---|
| ALLOW | ASK | ASK |
| ASK | ALLOW | ASK |
| ALLOW / ASK | DENY | DENY |
| DENY | later rule would ALLOW | DENY |

已获批准但尚未发送的 Envelope 在执行前仍要通过当前 safety floor：

- 当前规则升级为 DENY：阻止执行；旧批准不能覆盖。
- 当前规则要求 ASK，而旧 Envelope 仅曾 ALLOW：创建绑定同一 Envelope Digest 的新 PermissionRequest。
- 当前 validator 判定 Envelope 或 frozen SSH target 已无效：阻止执行，并在需要改变语义时创建新 Envelope。

用户或 Agent 修改可配置 policy 不会改变已有 Run；只有 Runtime 发布和维护的 mandatory safety floor 可以按上述规则收紧旧 Run。任何执行时求值都作为新的 append-only runtime execution fact/event 保存，至少记录：

- frozen policy / safety version
- current Runtime Mandatory Safety Policy version
- applicable mandatory checks
- effective decision 和理由
- 关联的 Run、Envelope Digest、ToolCall 或 Egress decision reference

这些事实参与恢复和审计，但不修改 Run Snapshot。

> Current Runtime Safety Policy may tighten an existing Run but may never silently weaken the security constraints frozen at Run creation.

---

## 14. Run State Machine

~~~text
CREATED → PLANNING → RUNNING → VERIFYING → COMPLETED
                       ├→ WAITING_PERMISSION → RUNNING
                       ├→ WAITING_USER → PLANNING / RUNNING / VERIFYING
                       ├→ PAUSED → PLANNING / RUNNING / VERIFYING
                       └→ RECONCILING
                              ├→ PLANNING / RUNNING
                              ├→ VERIFYING
                              └→ WAITING_USER / PAUSED

任何非终态 → CANCELLED
不可恢复错误或预算耗尽 → FAILED
~~~

| 状态 | 含义 |
|---|---|
| CREATED | 已保存目标和 Snapshot，尚未请求模型 |
| PLANNING | 正在生成或修订计划 |
| RUNNING | 正在调用模型、执行被允许的 Skill 或处理结果 |
| WAITING_PERMISSION | 精确操作等待用户审批 |
| WAITING_USER | 缺少信息、凭证、选择或用户判断 |
| RECONCILING | ToolCall 结果未知，停止新的修改并通过只读检查建立远端真实状态 |
| PAUSED | 因网络、Provider、进程重启等可恢复原因暂停 |
| VERIFYING | 正在执行独立成功验证 |
| COMPLETED | 必要成功条件已经验证通过 |
| FAILED | 不可恢复错误、预算耗尽或验证最终失败 |
| CANCELLED | 用户取消本次执行尝试；可能仍存在已发生效果或跨 Run 保留的 unresolved side-effect obligation |

Task 可以拥有多个 Run。PAUSED Run 可以恢复；FAILED、COMPLETED、CANCELLED Run 为终态。再次尝试创建新 Run，但新 Run 必须继承由历史 events 计算出的 unresolved side-effect safety preconditions。

### 14.1 ToolCall 状态

~~~text
PROPOSED
→ AWAITING_PERMISSION
→ APPROVED
→ EXECUTING
→ SUCCEEDED / FAILED / CANCELLED / UNKNOWN_OUTCOME
~~~

UNKNOWN_OUTCOME 表示操作可能已经送达服务器，但客户端没有得到可信结束状态。此状态禁止自动重放修改命令。

### 14.2 Reconciliation

任何 ToolCall 进入 UNKNOWN_OUTCOME 时，所属 Run 必须进入 RECONCILING，或在恢复后首先恢复到等价的 reconciliation phase。

~~~text
ToolCall UNKNOWN_OUTCOME
→ stop creating new mutating ToolCalls
→ reconnect when possible
→ perform read-only reconciliation
→ establish remote truth
~~~

Reconciliation 只允许：

- 连接恢复
- 符合 Safe Read Profile 的自动只读检查
- 经过正常风险判定和必要审批的其他只读检查
- 读取与原操作预期效果直接相关的状态和证据

它不能把“需要继续修复”作为理由绕过 UNKNOWN_OUTCOME 阻断规则。

结果：

| Reconciliation result | Run transition |
|---|---|
| CONFIRMED_NOT_EXECUTED | 返回 PLANNING 或 RUNNING，重新规划；原命令仍不得盲目重放 |
| CONFIRMED_EXECUTED | 进入 VERIFYING |
| PARTIAL_OR_INCONSISTENT | WAITING_USER；side-effect obligation 保持 unresolved，潜在冲突修改继续阻断 |
| UNRESOLVED | WAITING_USER 或 PAUSED |

原 ToolCall 的 UNKNOWN_OUTCOME 历史事实不被覆盖。Runtime 追加 Reconciliation 结果，并在当前状态投影和 FinalReport 中显示“已确认执行”“已确认未执行”或“仍未知”。

> An unresolved UNKNOWN_OUTCOME blocks further mutating ToolCalls.

### 14.3 Cross-run unresolved side-effect obligations

mutating ToolCall 进入 UNKNOWN_OUTCOME 时，除了原 Run 进入 RECONCILING，Runtime 还必须能够从已持久化事实推导出一个 unresolved external side-effect obligation。它不是本轮新增的顶层实体，而是由 source ToolCall、Execution Envelope 和 Runtime Events 计算出的安全事实。

该 obligation 至少关联：

- source Run 和 ToolCall
- Task
- Workspace snapshot / workspaceId
- frozen sshTarget、sshHostId 和 targetDigest
- operation、已知资源目标和保守的 conflict scope
- UNKNOWN_OUTCOME 发生时间、delivery facts 和当前 reconciliation status

Cancel Run 只停止进一步 Agent execution，不会撤销已可能发生的远端效果，也不会把 obligation 标为 resolved：

~~~text
Run #1 mutating ToolCall
→ UNKNOWN_OUTCOME
→ Cancel Run #1
→ Run #1 CANCELLED
→ unresolved side-effect obligation remains
→ Retry creates Run #2
→ reconciliation precondition before mutation
~~~

在创建或恢复新的执行型 Run 以及发送任何 mutating Envelope 前，Runtime 必须执行 Task / Workspace safety preflight，查询由历史 events 推导的未决义务。至少以下范围必须参与冲突判断：

- 同一 Task
- 同一 Workspace 或 sshHostId，包括 SSHHost record 后来被修改的情况
- 与原 operation 可能作用于同一服务、文件、进程、端口、部署、数据或其他远端资源的 target

只要 Runtime 不能证明后续 mutation 与 obligation 不冲突，就按可能冲突处理。创建新 Task、创建新 Run、Retry、Cancel source Run、修改 SSHHost record 或重启 App 均不能清除该事实。

新 Run 可以作为新的执行尝试被持久化，但在相关 obligation 未解决前：

- 它必须先进入 RECONCILING 或等价的 reconciliation precondition。
- 只能执行为建立原远端真实状态所需的只读操作。
- Reconciliation 必须使用 source Envelope 的 frozen sshTarget；不能借当前 SSHHost record 重定向检查。
- 任何潜在冲突的 mutating ToolCall 都必须被 Runtime 阻断，模型规划和用户普通 Approve Once 都不能绕过。

Reconciliation 结果作为 append-only event 同时关联 source ToolCall 和执行 reconciliation 的 Run：

| Result | Obligation effect | Retry flow |
|---|---|---|
| CONFIRMED_NOT_EXECUTED | resolved | 可以重新规划；如需 mutation，创建新 Envelope 并正常判定 |
| CONFIRMED_EXECUTED | resolved as executed | 先进入 Verification，再按正常流程决定后续动作 |
| PARTIAL_OR_INCONSISTENT | remains unresolved | WAITING_USER；继续阻断潜在冲突 mutation |
| UNRESOLVED | remains unresolved | WAITING_USER 或 PAUSED；继续阻断潜在冲突 mutation |

如果用户再次 Cancel 执行 reconciliation 的 Run，source obligation 仍然保留。Close Task、Archive Conversation 或 Run 进入任意终态也不能把历史不确定性解释为已解决；生命周期操作和远端事实是独立维度。

> Unresolved external side effects survive Run cancellation and block conflicting mutating operations in subsequent Runs until reconciliation establishes remote truth.

---

## 15. Golden Path

用户：

> Production Nginx 返回 502，帮我诊断并修复。

流程：

1. 将用户输入持久化为 Message。
2. Runtime 识别为需要 SSH 的目标，创建 Task。
3. 解析 Workspace 和 Agent 配置，创建 Run Snapshot。
4. Run 进入 PLANNING，生成初始计划。
5. Runtime 合并 Snapshot 的历史约束与当前 Mandatory Safety Floor，只自动执行同时符合二者及当前 Safe Read Profile 的诊断 Envelope；其他只读请求进入 ASK 或 DENY。
6. 每项 observation 先经过 Data / Egress Guard，并应用当前 hard blocks，再以允许、脱敏、摘要或阻断后的形式进入 Provider context。
7. Agent 根据可用的服务状态、配置、日志、端口和上游证据更新计划。
8. Agent 提出精确修改、影响、回滚、Success Criteria 和 versioned Verification Plan。
9. Runtime 把 Tool Proposal 规范化为 immutable Execution Envelope 并计算 Digest。
10. Permission Engine 按“历史约束 + 当前 Mandatory Safety Floor”针对同一 Envelope 创建 ASK 请求，并引用关键 Verification Plan version。
11. 用户执行 Approve Once。
12. Runtime 持久化执行时 safety decision 和 EXECUTING 后，SSH Skill 执行 byte/semantically identical Envelope。
13. Run 进入 VERIFYING。
14. 通过 Criterion → Check → Evidence → Evaluator → Verdict 检查配置、服务状态、监听端口、HTTP 和必要的上游状态。
15. 所有 REQUIRED criteria 获得可信 PASS 后，Run 进入 COMPLETED，Task 进入 RESOLVED。
16. Timeline 中 Task Card 保留完整执行投影，并追加 RUN_RESULT Message。

失败分支：

- SSH 无法连接：有界重试后 PAUSED。
- 认证失败：WAITING_USER。
- Host Key 变化：阻止连接。
- 用户拒绝：Agent 换方案或停止，不重复原请求。
- 命令非零：作为 observation 返回，不直接等同 Run 失败。
- 修改期间断线：UNKNOWN_OUTCOME，Run 进入 RECONCILING 并阻断新的修改。
- 用户 Cancel Run：停止新步骤，尽力中断当前 channel；Task 默认仍为 OPEN，任何 unresolved side-effect obligation 继续保留。
- 模型暂不可用：有界退避后 PAUSED。
- Verification 失败：重新诊断或最终 FAILED。

---

## 16. Agent Runtime Logical Boundaries

本节只定义职责，不定义后续 System Architecture 的代码模块、线程或依赖注入。

| 组件 | 负责 | 不负责 |
|---|---|---|
| Agent Core | Agent Loop、Run 状态机、计划、上下文、预算、恢复 | SSH 协议、UI、自行批准 |
| Skill Runtime | Tool schema、输入校验、执行、事件、取消、结构化结果 | 产品流程、权限放行 |
| Permission Engine | 合并历史约束与当前 Mandatory Safety Floor，进行风险判定、ALLOW/ASK/DENY、审批绑定和审计 | 接受模型自我授权或让旧 Snapshot 绕过当前强制规则 |
| Observation / Egress Guard | 对 Tool Observation 应用历史数据约束和当前 hard blocks，做敏感度判定、脱敏、摘要、阻断或请求用户确认 | 决定命令能否执行、授予 Skill capability |
| Model Provider Adapter | 消息和 Tool schema 转换、stream、错误归一化 | Skill 执行、Permission |
| UI / Presentation | Agent、Conversation、Timeline、Activity、审批和报告展示 | 直接执行 SSH、保存业务真相 |
| Persistence | Domain facts、Event Log、恢复、Snapshot | 仅依赖临时 UI 状态 |
| Credential Vault | 加密秘密、按授权解析 Credential | 把秘密暴露给模型或日志 |
| Background Execution | 活跃 Run 生命周期、通知、取消和恢复触发 | 自动重放未知副作用 |

固定责任链：

~~~text
Model proposes
→ Agent Core orchestrates
→ Runtime creates immutable Execution Envelope
→ Historical constraints + Current Runtime Safety Floor are evaluated
→ Permission Engine decides
→ User approves when required
→ Skill executes
→ local Observation and Evidence are recorded
→ deterministic Verification may evaluate local structured evidence
→ Observation / Egress Guard controls the Provider-facing view
→ only Provider-safe Observation enters Working Context
~~~

Execution 与 Observation egress 是两条不同的安全边界：

~~~text
Execution Envelope
→ Historical constraints + Current Runtime Mandatory Safety Floor
→ Permission Engine
→ Tool Execution
→ Observation
    ├→ local Evidence / deterministic Evaluator
    └→ Historical data constraints + current Egress hard blocks
          → Data / Egress Guard
          → redact / summarize / block / ask
          → Model Provider
~~~

---

## 17. Agent Loop

每轮流程：

1. 检查取消、暂停、网络和预算。
2. 从 Persistence 重建当前 Run，并计算跨 Run 的 unresolved side-effect obligations。
3. 在任何修改前执行 Task / Workspace safety preflight；存在潜在冲突 obligation 时先进入 RECONCILING。
4. 构造受限 Working Context。
5. 请求模型返回结构化决策。
6. 校验 ToolCall schema 和 Skill capability。
7. 将 Tool Proposal 规范化为不可变 Execution Envelope 并计算 Digest。
8. 合并 Historical Policy Snapshot 与 Current Runtime Mandatory Safety Floor，针对该 Envelope 评估风险并追加 execution-time safety fact。
9. ALLOW：记录 Envelope 和当前 safety decision 后执行。
10. ASK：针对同一 Envelope 进入 WAITING_PERMISSION；执行前仍重新应用当前 safety floor。
11. DENY：把确定性拒绝原因返回 Agent。
12. Skill 通过当前 validators 后严格执行已记录的同一 Envelope，并流式产生本地 observation。
13. Observation / Egress Guard 合并历史数据约束和当前 hard blocks，判定输出能否以及以何种形式进入 Provider Working Context。
14. 如果 ToolCall 为 UNKNOWN_OUTCOME，立即进入 RECONCILING 并创建可跨 Run 恢复的 unresolved side-effect obligation。
15. Agent 继续、重规划、提问或进入 Verification。
16. 达到终止条件后生成 FinalReport 和必要的 RUN_RESULT Message。

模型结构化决策类型：

- Plan update
- Tool proposal
- User question
- Verification request
- Final response

### 17.1 默认预算

- 最多 24 个模型轮次。
- 最多 20 次实际 ToolCall。
- 最多 3 次连续工具失败。
- 模型瞬时错误最多重试 2 次。
- 普通 SSH 命令默认超时 60 秒。
- 更长命令必须显示明确超时。
- 活跃任务超过 30 分钟后请求用户继续。
- Context 使用量约达到模型窗口 70% 时开始压缩。

### 17.2 Working Context

可以包含：

- Historical Policy Snapshot 和 Current Runtime Mandatory Safety Floor 的有效安全结果
- Agent 和 Persona Snapshot
- Task goal 和 Plan
- Workspace Snapshot
- Conversation Summary
- 必要 Message
- 最近 observation
- Observation 的 sensitivity、redaction 和 egress decision
- 审批和拒绝结果
- Verification criteria
- 创建 Snapshot 后追加的 runtime inputs 和必要 runtime events

Working Context 从不可变 Snapshot、append-only runtime inputs、runtime events 和当前 Runtime Safety Floor 的新求值结果重建。服务器输出、导入 Persona、Conversation 和 Memory 均按不可信内容处理。它们不能改变 Skill 或 Permission；原始 observation 未经 Data / Egress Guard 不得进入 Provider context。

---

## 18. Background Execution and Cancellation

### 18.1 Background

- Background execution 必须由用户发起、对用户可感知，并且只承诺 best-effort continuation。
- 切换 App 或锁屏时，Runtime 在平台允许范围内尽力继续。
- 平台可见界面显示 Agent、Conversation、Run 状态和 Cancel Run 入口。
- 高风险批准必须回到 App 并解锁，不提供通知一键批准。
- 进程被系统终止后不承诺继续执行；恢复时通常进入 PAUSED，若当前或历史 Run 存在相关的未解决执行结果 / side-effect obligation，则进入 RECONCILING 或等价 precondition。
- 已开始且可能产生副作用的 ToolCall 不自动重放。

Baseline 不锁定最终 foreground service type、调度 API、Play policy 适配或其他 Android 实现方式，也不承诺无限后台执行。这些由 System Architecture 根据目标 Android 版本和发布渠道验证。

参考：

- https://developer.android.com/develop/background-work/services
- https://developer.android.com/develop/background-work/background-tasks
- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running

### 18.2 Cancellation

用户执行 Cancel Run 后：

1. 立即持久化取消意图。
2. 停止创建新的 ModelInvocation 和 ToolCall。
3. 尽力取消当前 SSH channel。
4. 记录远端操作的确定或未知结果。
5. 不自动执行回滚。
6. 从已持久化 ToolCall、Envelope 和 events 保留或重算 unresolved side-effect obligations。

Cancel Run 使当前 Run 进入 CANCELLED，但 Task 默认仍保持 OPEN，用户之后可以 Retry 或单独执行 Close Task。Close Task 只改变 Task lifecycle，不代表取消某个正在执行的 Run；如果 Task 仍有活跃 Run，UI 必须先要求用户处理该 Run。

如果被取消的 Run 含 UNKNOWN_OUTCOME，Retry 创建的新 Run 必须先执行跨 Run reconciliation precondition。原 Run 保持 CANCELLED，原 ToolCall 保持 UNKNOWN_OUTCOME；后续 Reconciliation event 只解决远端不确定性，不改写这些历史状态。在 obligation resolved 前，新 Run 中潜在冲突的 mutation 保持阻断。

取消无法撤销已经发生的服务器效果。回滚始终是新的、需要重新进行风险判定和审批的操作。

---

## 19. Skill Architecture

MVP 只交付内置 SSH Skill，但接口必须保持可扩展。

### 19.1 Skill Descriptor

- skillId
- version
- name
- description
- operations
- input schema
- output schema
- capabilities
- default risk hint
- side-effect declaration
- cancellation support
- streaming support

### 19.2 Tool Proposal

- Skill 和 operation
- 结构化输入
- target
- purpose
- expected effect
- idempotency hint
- rollback hint
- verification hint

模型的 risk hint 不能降低 Permission Engine 结果。

Tool Proposal 只是模型意图，不是可执行安全事实。它必须先由 Runtime 校验并规范化为 Execution Envelope，才可以进入 Permission Engine。

### 19.3 Execution Context

- Task、Run、ToolCall ID
- Workspace 和 Host 的受限只读视图
- Credential Resolver
- cancellation token
- timeout
- event sink
- permission ticket

Skill 不直接访问整个 Credential Vault，也不能自行扩大授权。

### 19.4 标准错误

- VALIDATION
- AUTH
- CONNECTION
- TIMEOUT
- CANCELLED
- PERMISSION
- REMOTE_EXIT
- PROTOCOL
- UNSUPPORTED
- UNKNOWN

### 19.5 Immutable Execution Envelope

Execution Envelope 是 ToolCall 内部持久化的不可变 value object，不是新的顶层 Domain entity。

MVP 至少包含：

- skillId 和 skillVersion
- operation
- immutable sshTarget
- logical credentialRef
- workingDirectory
- shellMode
- exactCommandBytes
- environment
- stdinMode
- timeout

还可以包含不改变执行语义的关联信息，例如 Run ID、ToolCall ID 和 schema version。

sshTarget 是 Envelope 内部的 immutable value object，至少包含：

- sshHostId
- sshHostRevision
- hostname，即 Envelope 创建时的配置值
- resolvedAddress，即本次批准所对应的实际网络地址
- port
- username
- pinnedHostKeyFingerprint
- targetDigest

hostname 为字面 IP 时，resolvedAddress 与其保持一致。hostname 对应多个地址时，必须在 Permission analysis 前选择本次实际使用的 resolvedAddress；未经新 Envelope 和新风险判定，不得在执行时回退到另一个 DNS 结果或备用地址。

规范化规则：

1. Runtime 在 Permission Engine 前，根据已验证 Tool Proposal 和当前 Run Snapshot 创建 Envelope。
2. 所有默认值、编码、sshTarget、Credential reference、目录、shellMode、environment、stdinMode 和 timeout 必须在计算 Digest 前确定。
3. exactCommandBytes 保留精确字节，不做 trim、空白合并、重排或审批后变量插值。
4. environment 使用确定性排序和明确编码；秘密不得直接放入 environment。
5. stdinMode 在 v0.1 默认为 NONE；任何未来非空 stdin 都必须成为 Envelope 的明确语义部分。
6. canonical serialization 和 digest algorithm 由后续 System Architecture 定义，但同一个 Envelope 必须产生稳定 Digest。

### 19.6 SSH target immutability

Runtime 在创建 Envelope 时可以读取 SSHHost，但 Envelope 完成后不得在执行路径中使用 sshHostId 重新解析可变的 hostname、address、port、username 或 Host Key identity。

~~~text
SSHHost at Envelope creation
→ immutable sshTarget + targetDigest
→ Permission analysis / approval
→ exact frozen network target
~~~

执行前 preflight：

- 重新计算 Envelope Digest，并与已分析、已批准 Digest 比较。
- 如 SSHHost 仍存在，可以比较当前 target-defining fields 与 frozen targetDigest。
- 仅显示名、标签或备注等非执行字段变化不能重定向 Envelope。
- hostname、resolved address、port、username 或 pinned Host Key identity 任一变化，都会使尚未发送的 Envelope 失效。
- 失效后不得自动采用当前 SSHHost 值；必须创建新 Envelope，重新进行风险判定，并在必要时重新审批。

SSH Skill 最终连接必须使用 Envelope 中冻结的 resolvedAddress、port、username 和 pinnedHostKeyFingerprint。Host Key 握手结果仍必须与 pinned fingerprint 匹配。

> The approved SSH target must be the exact target used for execution.

如果原 Envelope 已经可能送达并进入 UNKNOWN_OUTCOME，Reconciliation 必须针对原 Envelope 的 frozen sshTarget 建立远端真相，不能改为当前 SSHHost 指向的新目标。当前 Mandatory Safety Floor 如果禁止重新连接旧目标，则保持 unresolved 并进入 WAITING_USER 或 PAUSED。

Credential 使用单独语义：

- Envelope 保存逻辑 credentialRef，不保存秘密。
- 同一 credentialRef 下的 secret rotation 可以按现有 recovery 规则使用，并记录 rotation version。
- SSHHost 改为另一个 credentialRef 不会改变已有 Envelope；要使用新的 logical credentialRef 必须创建新 Envelope。
- Credential secret rotation 不等于 SSH execution target mutation。

固定引用链：

~~~text
validated Tool Proposal
→ immutable Execution Envelope + Digest
→ Permission analysis
→ Permission Request
→ Approve Once
→ SSH Skill execution
~~~

PermissionRequest 和 PermissionDecision 必须保存同一 Envelope Digest。UI 必须从 Envelope 渲染用户看到的动作，不能使用另一份命令文案。SSH Skill 必须执行 Envelope 中的 exactCommandBytes 和其他参数，不能在审批后重新拼装、补充、重写或替换命令。

任何语义字段，包括 sshTarget 或 logical credentialRef，发生变化都必须：

- 创建新的 Envelope 和 Digest
- 重新进行风险判定
- 在 ASK 情况下重新请求用户审批

> The action analyzed, the action approved, and the action executed must be byte/semantically identical under one canonical execution envelope.

---

## 20. SSH Skill

### 20.1 Host 配置

- id
- name
- hostname 或 IP
- port
- username
- authType
- credentialRef
- pinnedHostKeyFingerprint
- revision
- tags
- notes

MVP 支持密码、私钥和带口令私钥认证。首次连接必须由用户确认 Host Key fingerprint；后续不匹配时禁止 Agent 自动接受。

### 20.2 MVP 能力

- Test connection
- 非交互式命令执行
- stdout / stderr 分离并流式传输
- exit status
- timeout
- best-effort cancellation
- disconnect detection
- reconnect
- structured result

不提供长期交互式 Shell、SFTP、端口转发、Jump Host、复杂 TTY 或交互式 MFA。

### 20.3 执行输入

- immutable Execution Envelope
- matching Envelope Digest
- permission ticket
- Credential Resolver handle
- cancellation token
- event sink

purpose、expected risk 和 verification hint 属于 Tool Proposal 或相关 plan metadata，不允许 Skill 用它们重新推导实际命令。MVP 不允许模型提供秘密 stdin。审批后不得修改命令、sshTarget、logical credentialRef、目录、shellMode、environment、stdinMode 或 timeout。SSH Skill 不得从当前可变 SSHHost record 重新取得网络目标。

### 20.4 Streaming

每个事件包含：

- ToolCall ID
- stdout 或 stderr
- monotonic sequence
- timestamp
- text chunk
- truncation indicator

建议每个输出流本地最多保存 1 MiB，超过后保留头尾、总字节数和截断标记。发送给模型的内容进一步摘要和脱敏。

### 20.5 结构化结果

- executionId
- frozen sshTarget / working directory
- targetDigest
- executed Envelope Digest
- startedAt / endedAt
- delivery state
- stdout / stderr preview and log reference
- exit code 或 signal
- timedOut
- cancelled
- connectionLost
- certainty：CERTAIN 或 UNKNOWN
- normalized outcome

### 20.6 Retry

- 明确未送达且仍符合 Safe Read Profile 的 Envelope 可以自动重试一次。
- 已送达并确认无副作用的 Safe Read Envelope 可以按策略重试。
- 修改命令一旦可能送达但结果未知，禁止自动重试。
- Retry 或重连前先查询跨 Run unresolved side-effect obligations；命中时进入 RECONCILING，使用 source Envelope 的 frozen sshTarget 和符合当前安全规则的只读 Envelope 核验远端状态。
- Cancel、Run 终态、App 重启或新 Run ID 不重置 retry safety；只有 CONFIRMED_EXECUTED 或 CONFIRMED_NOT_EXECUTED 等建立远端真相的 resolution event 才解除相应 obligation。
- 回滚是新的 ToolCall。

---

## 21. Permission Engine

### 21.1 决策

- ALLOW：无需阻塞用户，但仍可见并被记录。
- ASK：等待用户批准精确操作。
- DENY：Runtime 禁止执行，普通审批不能绕过。

### 21.2 风险级别

| 风险 | 含义 | 默认行为 |
|---|---|---|
| READ | 没有预期远端修改；不代表输出不敏感，也不自动代表可 ALLOW | 仅匹配 Safe Read Profile 时 ALLOW，否则 ASK 或 DENY |
| LOW | 有限、临时、容易恢复的低影响操作 | 非生产仅在显式确定性策略匹配时可 ALLOW，生产默认 ASK |
| MODIFY | 修改配置、服务状态、文件或持久状态 | ASK |
| DESTRUCTIVE | 可能删除、覆盖、破坏数据或造成停机 | 强提示 ASK |
| CRITICAL | 极大爆炸半径、不可可靠恢复或触及关键安全边界 | DENY |

CRITICAL 包括磁盘格式化、广泛递归删除、可能造成永久失联的远程访问修改、删除关键认证材料、数据库整体删除，以及下载未知脚本后高权限执行。

### 21.3 判定输入

不能只匹配命令字符串，必须综合：

- Skill operation semantics
- shell token、管道、重定向和复合命令
- 文件、服务、进程和网络目标
- root / sudo
- Workspace environment
- blast radius 和回滚性
- 是否产生网络访问、进程启动或其他外部效果
- 通配符、变量展开和动态行为
- 当前 Run 已发生的操作

规则：

> 不确定性只能提高风险，不能降低风险。

复合命令取所有子操作的最高风险。Execution Risk 不负责决定 observation 能否发送给 Provider；该问题由独立 Data / Egress Guard 处理。Permission Engine 仍然可以根据目标路径、权限边界或明显的秘密访问行为提高“是否允许执行”的要求。

### 21.4 Safe Read Profile

Safe Read Profile 是 Runtime 控制的、保守且有边界的自动执行策略，不是模型提供的 risk label，也不是“理解任意 shell”的通用静态分析器。旧 Run 的自动执行资格取 Snapshot 冻结约束与当前 Safe Read Profile 的交集；当前 Profile 可以取消旧资格，不能为旧 Run 自动增加更宽松资格。

一个 SSH Envelope 只有同时满足以下条件，才可以作为 READ 自动 ALLOW：

- Skill、operation、executable 和参数形态均被 Runtime 明确认识。
- 目标、参数和允许的选项通过确定性 validator。
- shellMode 属于 Safe Read Profile 允许的模式。
- 没有预期远端修改或额外外部副作用。
- 不以 root 登录，也不包含 sudo 或其他权限提升。
- 不包含 shell redirection。
- 不包含 command substitution。
- 不包含 eval。
- 不调用任意 interpreter、脚本或动态代码。
- 不包含无法约束的变量、glob 或 dynamic shell expansion。
- 不包含未知 executable。
- 不产生 network egress。
- 不包含无法可靠拆解的 pipeline、compound command 或 control operator。
- 不访问 Safe Read Profile 明确排除的秘密路径或数据类别。

如果任何条件不能被 Runtime 证明：

- 不得进入 ALLOW。
- 可以根据风险和可解释性进入 ASK。
- 无法安全展示、分析或约束时进入 DENY。

Safe Read Profile 应优先由少量内置、可测试的 operation shape 和 validator 组成。v0.1 不尝试构建完整 shell static analyzer。

即使一个 Envelope 同时匹配历史约束和当前 Safe Read Profile，其输出仍必须经过 Observation / Egress Guard。Safe Read eligibility 不代表 Provider egress eligibility。

### 21.5 Execution-time safety reevaluation

Permission analysis 和用户 Approve Once 不能跳过发送前的当前 Mandatory Safety Floor。Runtime 在每次实际发送 Envelope 前必须：

1. 验证 Envelope Digest、frozen SSH target 和 schema/canonicalization。
2. 对 Frozen Run Policy 与 Current Runtime Mandatory Safety Floor 取更严格结果。
3. 检查 unresolved side-effect obligations。
4. 持久化本次 safety policy version、decision 和理由。

结果为 DENY 或 validator invalid 时不得发送；结果从 ALLOW 收紧为 ASK 时必须等待匹配审批。当前规则变宽松不能让 Snapshot 中原有的 ASK 或 DENY 自动变为 ALLOW。

### 21.6 Approve Once

批准绑定同一个不可变 Execution Envelope 和 Digest，包括：

- Skill 和 operation
- sshHostId 和 sshHostRevision
- hostname、resolvedAddress、port 和 username
- pinnedHostKeyFingerprint 和 targetDigest
- logical credentialRef
- workingDirectory
- shellMode
- exactCommandBytes
- environment
- stdinMode
- timeout
- Run 和 ToolCall
- PermissionRequest 所引用的关键 Success Criteria / Verification Plan version

任何 Envelope 语义字段变化都使批准失效。暂停、进程重启或超过短暂有效期后，尚未执行的批准失效。修改操作所依据的关键 Success Criteria 或 Verification Plan 如果在执行前被实质削弱，也必须重新生成 PermissionRequest。

MVP 不提供：

- 本 Task 始终允许
- 本 Agent 始终允许
- 永久允许此类命令
- 模型自我授权

---

## 22. Observation and Egress Boundary

Execution Risk 和 Data Sensitivity 是独立维度：

| 问题 | 负责边界 |
|---|---|
| 这个 Envelope 能不能执行 | Permission Engine |
| 执行结果能不能发送给模型 Provider | Observation / Egress Guard |

一个命令可以是无远端修改的 READ，但仍读取高度敏感内容。Credential exclusion 只能保护已知 Credential Vault 数据，不能保护任意服务器输出。

### 22.1 Observation flow

~~~text
Tool Execution
→ local raw Observation
→ sensitivity classification
→ egress decision
→ redact / summarize / block / ask
→ Provider-safe Observation
→ Working Context
~~~

原始 observation 可以按本地日志策略加密保存并在 UI 中受控展示，但未经 Egress Guard 处理不得进入 ModelInvocation。

### 22.2 Data Sensitivity

| 级别 | 含义 | 默认 Provider egress |
|---|---|---|
| NORMAL | 普通运行状态和无明显机密的诊断信息 | 基础清洗后允许 |
| SENSITIVE | 内部路径、主机拓扑、业务标识、配置片段、可能包含客户或机密信息的日志 | redact、summarize 或 ASK |
| SECRET | 私钥、token、密码、Authorization header、shadow hash、数据库 dump 或明确秘密文件内容 | raw output 默认 BLOCK |

典型 SECRET 或高风险来源：

- .env
- SSH 或 TLS private key
- access token / API key
- /etc/shadow
- password store
- database dump
- 含认证头、session 或用户机密的日志

无法可靠分类时，至少按 SENSITIVE 处理；不能因为命令被分类为 READ 就降为 NORMAL。

### 22.3 Egress decisions

- ALLOW：发送经过基础清洗的 observation。
- REDACT：删除或替换敏感片段，只发送剩余信息。
- SUMMARIZE：在本地或不暴露原文的受控路径生成必要摘要。
- BLOCK：不发送原始内容，向 Agent 返回结构化的“内容被策略阻断”结果。
- ASK：在策略允许用户决定时，明确说明数据类别、Provider 和发送范围。

已知 Credential、private key 和 token 的原始值不得通过普通 ASK 流程直接发送。用户批准执行命令不等于批准 observation egress。

Egress Guard 同时处理：

- stdout / stderr stream
- Tool structured result
- log excerpt
- Verification evidence 在进入 Provider 前的视图
- Tool output 中的 prompt injection 或伪造指令

v0.1 不要求实现完整 DLP。最低要求是保守分类、常见秘密模式和路径阻断、长度限制、明确的 Provider egress decision，以及 unknown-as-sensitive。

恢复旧 Run 或处理旧 observation 时，当前 Runtime Egress Guard 的 hard block 必须生效；它可以阻止历史 policy 曾允许的发送，但当前更宽松规则不能绕过 Snapshot 已冻结的数据限制。执行时 egress policy version 和有效 decision 作为 append-only event 保存，不修改 Snapshot，也不覆盖历史 decision。

### 22.4 Persistence

本节不增加新的顶层 Domain entity。现有 ToolEvent / LogReference 或 Runtime Event Log 必须记录：

- sensitivity
- egress decision
- applied redaction / summarization policy
- Provider-safe observation reference
- raw local observation reference，如策略允许保存

Provider-safe observation 与本地 raw observation 必须使用不同引用，防止后续 Context builder 绕过 Guard 误取原始数据。

---

## 23. Human in the Loop

Runtime 在以下情况等待用户：

- MODIFY 或 DESTRUCTIVE 操作
- 目标、范围或意图不明确
- 需要选择 Workspace、服务器、服务或修复方案
- SSH 认证或 Host Key 需要处理
- UNKNOWN_OUTCOME
- 需要延长预算
- 多个高风险诊断分支
- Verification 失败且继续可能扩大影响

Permission Card 必须显示：

- Agent、Conversation 和 Task
- Workspace、Host、用户和目录
- 完整精确命令
- 风险级别和原因
- 操作目的
- 预期效果
- 回滚说明
- Verification plan

用户操作：

- Approve Once
- Reject
- Cancel Run

凭证输入使用独立安全 UI，不进入 Conversation Message。

Close Task 是独立操作：它只把 OPEN Task 变为 CLOSED。存在活跃 Run 时，必须先 Cancel Run 或等待 Run 进入终态，再决定是否 Close Task。Close Task 不解决或删除历史 Run 的 unresolved side-effect obligation。

---

## 24. Verification

Verification 是一等阶段，不是 FinalReport 的一段模型文案。

正式信任链：

~~~text
Verification Criterion
→ Check
→ Evidence
→ Evaluator
→ Verdict
~~~

VerificationRecord 至少包含：

- criterion ID 和 version
- criterion description
- REQUIRED 或 ADVISORY
- expected condition
- check operation / Envelope reference
- immutable evidence reference 和 evidence digest
- evaluator type 和 evaluator version
- actual value 或 structured observation
- PASS / FAIL / INCONCLUSIVE
- evaluatedAt

### 24.1 Criterion and plan versioning

Run Snapshot 保存初始 Success Criteria。Agent 可以在执行过程中追加或细化 Verification Plan，但必须通过新版本记录，不能覆盖旧版本。

关键 Success Criteria / Verification Plan 一旦已经：

- 展示给用户，并且
- 被某个修改 PermissionRequest 引用

就不能在执行后被 Agent 静默削弱以获得 COMPLETED。

以下属于实质削弱：

- 删除 REQUIRED criterion
- 把 REQUIRED 改成 ADVISORY
- 降低 expected value
- 放宽阈值或匹配范围
- 用更弱的 check 替代已经展示的 check
- 把 FAIL 或 INCONCLUSIVE 重新解释为 PASS

增强或增加检查可以直接产生新 Plan version。实质降低成功标准必须重新规划、保留差异，并在必要时进入 WAITING_USER 获取明确确认；如果发生在修改动作执行前，还必须使引用旧 Plan 的 PermissionRequest 失效。

### 24.2 Evidence and evaluator trust

优先使用 deterministic evaluator，例如：

~~~text
expected HTTP status = 200
actual HTTP status = 200
→ PASS
~~~

~~~text
expected service state = active
actual service state = active
→ PASS
~~~

Evaluator 可以是：

- DETERMINISTIC：精确值、集合、范围或明确布尔规则。
- STRUCTURED_RULE：针对结构化输出的版本化规则。
- MODEL_ASSISTED：解释非结构化证据、提出下一项检查或生成候选结论。
- USER_CONFIRMED：当结果无法自动判断时，由用户在看到证据和风险后确认。

模型可以提出 Verification Plan、解释 Evidence 和建议后续检查，但 MODEL_ASSISTED 单独不能为安全关键 REQUIRED criterion 产生高可信 PASS。没有 deterministic 或 user-confirmed 支撑时，结果必须保持 INCONCLUSIVE。

Evidence 必须引用实际 Check 结果，不得只保存模型摘要。Evidence 进入模型前仍经过 Data / Egress Guard；经过脱敏的 Provider view 不覆盖本地原始 Evidence reference。

Nginx 修复示例：

- 配置语法检查
- 服务 active 状态
- 目标端口监听
- HTTP 响应
- 上游服务可达
- 相关新错误是否继续出现

规则：

- exitCode 0 只证明命令自身成功结束。
- 模型声称“应该好了”不是证据。
- 一个检查通过不代表整体成功。
- 所有 REQUIRED criterion 均为可信 PASS 后，修复型 Run 才能 COMPLETED。
- REQUIRED criterion 为 FAIL 时不能 COMPLETED。
- REQUIRED criterion 为 INCONCLUSIVE 时进入 WAITING_USER、继续检查或最终 FAILED，不能自动视作成功。
- Verification 失败可以重新规划，但后续修改仍受 Permission Engine 约束。
- UNKNOWN_OUTCOME 必须先经过 RECONCILING；确认已执行后才能进入正常 Verification。

---

## 25. Model Provider Abstraction

### 25.1 Provider 类型

- OpenAI-compatible
- Anthropic
- Custom Base URL

ProviderProfile：

- id
- type
- baseUrl
- credentialRef
- model configuration
- context configuration
- timeout
- capability probe result

### 25.2 Runtime 统一协议

Provider Adapter 归一化：

- system / user / assistant / tool observation
- structured Tool Definition
- text stream
- ToolCall stream
- usage
- finish reason
- context limits
- normalized errors

真实执行模式只允许通过结构化 ToolCall 能力探测的模型。纯文本模型可以普通聊天和给建议，但不能产生可执行 ToolCall。

Provider Adapter 只能接收 Observation / Egress Guard 产生的 Provider-safe view，不能直接读取 raw ToolEvent、raw log 或本地 Evidence。Context builder 也不得绕过该边界。

### 25.3 错误

- AUTH：停止重试，要求更新配置。
- RATE_LIMIT：遵守 Retry-After，有界等待。
- TIMEOUT / UNAVAILABLE：退避重试最多两次。
- CONTEXT_OVERFLOW：压缩后重试一次。
- INVALID_RESPONSE：结构修复一次。
- SAFETY_BLOCK：如实展示。
- CANCELLED：终止当前调用。

Credential、SSH 私钥和密码不进入模型上下文。除此之外，任意服务器输出仍可能包含秘密，必须经过 Data / Egress Guard；Credential exclusion alone is insufficient。

---

## 26. Memory and Context

正式区分：

| 层级 | Owner | 含义 | v0.1 |
|---|---|---|---|
| Agent Shared Memory | Agent | 跨 Conversation 长期知识 | 不实现 |
| Conversation Memory | Conversation | 当前持续上下文 | Messages + Summary |
| Workspace Memory | Workspace | 外部环境长期知识 | 显式 Workspace Context |
| Working Context | ModelInvocation | 当前模型实际输入 | 实现，但不是持久 Memory |

### 26.1 ConversationSummary

- conversationId
- version
- content
- sourceThroughOrdinal
- generatedAt
- modelId
- tokenEstimate

Summary：

- 是有损缓存，不是 Domain Fact。
- 可以重新生成或清空。
- 不能是 Permission、ToolCall、Verification 或 Workspace Snapshot 的唯一来源。
- 不能授予 Capability。
- Run Snapshot 记录使用的 Summary version。

MVP MemoryPolicy：

- NONE
- CONVERSATION_SUMMARY

不实现 Vector Database、Embedding Memory、自动长期人格记忆、Lorebook Engine 或 Memory Marketplace。

---

## 27. Character Compatibility

Character Card 导入未来遵循：

~~~text
Character Card
→ Versioned Import Adapter
→ Persona Import Draft
→ User Review
→ Persona
~~~

映射：

| 输入 | Persona |
|---|---|
| Name | displayName |
| Avatar | avatarRef |
| Description / Personality | description 或 typed persona data |
| Character Prompt | systemPrompt |
| First Message | greeting |
| Scenario | future extension |
| Example Dialogue | future typed extension |
| Lorebook | future Lorebook reference |
| Creator / Tags / Version | import metadata |

适配器永远不能导入：

- Skill
- Tool schema
- Permission
- Workspace
- Host
- Provider
- Credential
- 可执行扩展代码

如果未来提供 Character Template，其安全默认值必须是：

- Skills 为空
- Permission 为 DENY
- Default Workspace 为 null

Character-compatible Domain 属于 v0.1 Baseline，但 Character Template 和 Character-specific creation UI 不是 SSH MVP 的强制交付项。用户未来为 Character Agent 添加 SSH Skill，必须是独立、明确的 Agent Configuration 操作。

---

## 28. Persistence Domain

正式持久化实体：

- Agent
- Persona
- ProviderProfile
- SSHHost
- Workspace
- CredentialReference
- Conversation
- ConversationTimelineEntry
- Message
- ConversationSummary
- Task
- RunSnapshot
- Run
- PlanVersion / PlanStep
- ModelInvocation
- ToolCall
- ToolEvent / LogReference
- PermissionRequest / PermissionDecision
- VerificationRecord
- FinalReport

不使用 AgentProfile。

不使用 Turn 代替 Message 或 ModelInvocation。

建议使用：

- 追加式 Runtime Event Log，用于审计和恢复。
- 当前状态视图，用于 UI 和高效查询。
- 每个副作用前后的明确持久化边界。

本轮安全修订不增加新的顶层持久化实体：

- Execution Envelope 是 ToolCall 内部的不可变 value object，并保存 frozen sshTarget、targetDigest 和 Envelope Digest。
- Post-snapshot runtime inputs 使用 append-only Event Log 语义；是否拆成 RunInput 表由 System Architecture 决定。
- 每次执行时的 current Runtime Mandatory Safety Policy version、mandatory checks、effective decision 和理由作为 Runtime Event 追加；不修改 Historical Policy Snapshot。
- Reconciliation 结果作为 Runtime Event 追加并关联 source ToolCall 与执行 reconciliation 的 Run，原 UNKNOWN_OUTCOME 和 source Run 终态不覆盖。
- Unresolved side-effect obligation 由 UNKNOWN_OUTCOME、source Envelope、Task / Workspace 关系和 Reconciliation events 推导；System Architecture 可以建立物化索引，但不得以新 Run、Cancel 或终态作为清除条件。
- Data sensitivity、egress decision 和 Provider-safe reference 保存于现有 ToolEvent / LogReference 或 Runtime Event。
- Verification Criterion、Check、Evidence、Evaluator 和 Verdict 由现有 VerificationRecord 结构化承载。

必须在以下时点先持久化再继续：

- 创建 Run
- 写入模型请求和计划版本
- 创建 Tool Proposal
- 创建并持久化 immutable Execution Envelope、frozen sshTarget、targetDigest 和 Digest
- 创建 PermissionRequest
- 保存与同一 Envelope Digest 绑定的用户审批
- 在发送前保存 current Runtime Mandatory Safety Policy version、preflight checks、effective decision 和 target validation result
- 发送远端命令前记录 EXECUTING
- 保存 stream sequence
- 保存 raw observation reference、sensitivity、egress decision 和 Provider-safe reference
- 保存 exit status 或 UNKNOWN_OUTCOME
- UNKNOWN_OUTCOME 后保存足以重建 conflict scope 和 unresolved side-effect obligation 的 source facts
- 保存 append-only runtime inputs
- Cancel Run 或新建 Retry Run 时保留未解决义务，并保存 Task / Workspace safety preflight 结果
- 保存关联 source ToolCall 和 reconciliation Run 的 Reconciliation 结果
- 保存 versioned Verification Plan、Evidence、Evaluator 和 Verdict
- 写入终态和 FinalReport

Android 临时 UI Saved State 不能作为 Run 的事实来源。

参考：

- https://developer.android.com/topic/libraries/architecture/saving-states

---

## 29. Credential Security and Data Privacy

- API Key、SSH 密码和私钥使用数据密钥加密。
- 数据密钥由 Android Keystore 中的主密钥保护。
- Domain 数据只保存 CredentialReference。
- 可以使用设备凭证或生物识别解锁敏感操作。
- Credential 不进入普通日志、Message、Run Event 或模型上下文。
- 私钥导出默认关闭。
- 备份默认不包含 Credential。
- 日志对常见密钥和 Authorization Header 脱敏。

参考：

- https://developer.android.com/privacy-and-security/keystore
- https://developer.android.com/privacy-and-security/security-tips

首次运行前明确告知用户：

- 为诊断服务器，必要输出可能发送给所选 Provider。
- Credential 不会发送。
- 任意服务器输出可能包含 Credential Vault 之外的业务秘密。
- Observation / Egress Guard 会进行分类、脱敏、摘要或阻断，但简单规则不能保证发现全部业务秘密。
- 生产环境应使用可信 Provider 和适当策略。

---

## 30. Failure and Recovery Model

| 故障 | Runtime 行为 |
|---|---|
| Provider 认证错误 | WAITING_USER，不重试 |
| Provider 限流或暂时故障 | 有界退避后 PAUSED |
| Context overflow | 压缩后重试一次 |
| 无效 ToolCall | 修复一次，仍无效则暂停或失败 |
| SSH 连接失败 | 有界重连后 PAUSED |
| SSH 认证失败 | WAITING_USER |
| Host Key 不匹配 | 阻止连接 |
| SSHHost target-defining fields 在 Envelope 创建或审批后变化 | 不采用新值；尚未发送的 Envelope 失效，创建新 Envelope 并重新判定 / 必要时重新审批 |
| Envelope 的 frozen resolved address 不可达 | 不静默回退到其他 DNS 地址；需要新 Envelope |
| 命令非零退出 | 返回 Agent 重新判断 |
| 命令超时 | 尽力取消；可能 UNKNOWN_OUTCOME，并进入 RECONCILING |
| 执行期间断网 | 根据 delivery state 重试；存在未知副作用时进入 RECONCILING |
| UNKNOWN_OUTCOME | 创建可恢复的 unresolved side-effect obligation，阻断当前及后续 Run 中潜在冲突的 mutating ToolCall，只读 reconciliation |
| Reconciliation 无法建立远端真相 | WAITING_USER 或 PAUSED |
| Cancel Run 时仍有 UNKNOWN_OUTCOME | Run 进入 CANCELLED，但 obligation 保留；Retry 先 reconciliation |
| Retry 命中历史 Run 的 unresolved obligation | 新 Run 进入 RECONCILING 或等价 precondition；远端真相明确前不允许潜在冲突 mutation |
| stdout 过大 | 本地截断并标记；Provider view 另行摘要 |
| Observation 判定为 SENSITIVE | redact、summarize 或 ASK 后才允许 egress |
| Observation 判定为 SECRET | raw Provider egress 默认 BLOCK |
| Egress 分类不确定 | 至少按 SENSITIVE 处理 |
| Runtime safety update 比 Snapshot 更严格 | 记录新 policy version 和 decision，并应用更严格规则；必要时 ASK、DENY、PAUSED 或新建 Envelope |
| Runtime safety update 比 Snapshot 更宽松 | 保留 Snapshot 冻结的更严格约束，不自动扩大 ALLOW 或 egress |
| 旧 safety policy 无法由当前 Runtime 可靠解释 | fail closed；ASK、DENY、WAITING_USER 或 PAUSED |
| App 进入后台 | 用户可感知的 best-effort continuation |
| App 进程终止 | 无未知副作用时恢复为 PAUSED；当前 ToolCall 或历史 events 投影出相关 unresolved obligation 时恢复为 RECONCILING / 等价 precondition；均不重放副作用 |
| 用户拒绝 | 换方案或停止，不循环申请 |
| 用户 Cancel Run | 当前 Run CANCELLED，停止后续步骤并保存已发生效果与未决义务；Task 默认 OPEN，历史不确定性不被取消 |
| 达到预算 | WAITING_USER 请求继续 |
| Verification FAIL | 重新诊断或 FAILED |
| REQUIRED Verification INCONCLUSIVE | WAITING_USER、继续检查或 FAILED，不得 COMPLETED |
| Verification Plan 被实质削弱 | 版本化重规划，并在必要时等待用户确认 |
| 未知异常 | 保存错误快照，PAUSED 或 FAILED |

FAILED 用于终止性失败；网络、Provider 暂时故障和进程重启优先进入 PAUSED。

---

## 31. Lifecycle, Archive, Erase and Copy

Archive 是可恢复的产品状态；Erase 是不可恢复的本地数据删除。Erase 不会撤销服务器上的外部效果。

| 对象 | Archive | Erase/Delete | Copy |
|---|---|---|---|
| Agent | 支持，无活跃 Run 时执行 | 显式 Erase，删除 owned history | 复制配置与 Persona，不复制历史、Memory、Credential |
| Persona | 不单独归档 | 随 Agent Erase；Snapshot 保留历史副本 | 通过 Duplicate Agent |
| Conversation | 支持，无活跃 Run 时执行 | 显式 Erase，级联 Message、Task、Run 和日志 | MVP 不支持；未来 Branch |
| Message | 不支持 | MVP 不支持单条删除；随 Conversation Erase | 只复制文本 |
| Task | 可 Close，不单独归档；Close Task 不等于 Cancel Run | 无 Run 的草稿可删；其余随 Conversation Erase | Retry 创建新 Run |
| Run | 不支持；使用 Cancel Run 终止当前尝试 | 不单独删除；终态不可变 | 新建 Run |
| Workspace | 支持 | 无有效引用时可删，否则归档 | MVP 不支持 |
| ConversationSummary | 不归档 | 可清空或重建 | 不复制 |
| Tool/Permission/Verification | 不支持 | 只随 Conversation Erase | 不支持 |

删除 Agent 不级联删除 Workspace、Host、ProviderProfile 或 Credential。

Cancel Run 与 Close Task 的正式区别：

- Cancel Run：停止一次具体执行尝试，使 Run 进入 CANCELLED；Task 默认保持 OPEN。
- Close Task：表示用户不再追求该目标，使 Task 进入 CLOSED；它不直接改变 Run 状态，也不解决远端副作用不确定性。
- Task 存在活跃 Run 时，不能只执行 Close Task 来隐式取消运行。用户必须先 Cancel Run 或等待 Run 终止。

存在 unresolved side-effect obligation 时，不得通过 Erase 删除重建该义务所必需的最小 ToolCall、Envelope 和 resolution facts。用户应先完成 reconciliation；具体保留或安全墓碑结构由后续 System Architecture 决定，不在 Baseline 新增实体。

---

## 32. Agent Templates

Template 只产生初始配置，不创建不同 Runtime 类型：

~~~text
Template → ordinary Agent
~~~

v0.1 可提供：

| Template | MVP requirement | Persona | Skills | Permission | Workspace |
|---|---|---|---|---|---|
| Server / DevOps | Required | 运维助手 | SSH | Safe policy | 创建时选择 |
| General Assistant | Optional | 通用助手 | 无 | DENY | null |
| Custom | Required | 空白 | 用户选择 | 安全默认值 | 可选 |
| Character | Deferred / optional | 简单 Persona | 无 | DENY | null |

Character-compatible Domain 不要求 v0.1 交付 Character Template 或 Character-specific creation UI。用户仍可通过 Custom Agent 和 Persona 基础字段验证领域兼容性。Coding Template 等实际 Coding Skill 存在后再展示。

---

## 33. Mobile Information Architecture

一级导航：

~~~text
Home
Agents
Activity
Settings
~~~

产品原则：

> Agent-first, not Chat-first, not Task-manager-first.

### 33.1 Home

- Needs Attention
  - Waiting Permission
  - Waiting User
  - Paused
  - Failed
  - Reconciling
  - Unknown Outcome
- Continue Conversation
- Start Conversation

Home 负责“现在最值得继续什么”，不是完整 Task 列表。

### 33.2 Agents

- Agent identity
- Agent Detail
- Multiple Conversations
- New Conversation
- Agent Configuration

Agent Detail：

~~~text
Agent
├── Identity
├── Continue
├── Conversations
└── Configuration
    ├── Persona
    ├── Model
    ├── Skills
    ├── Permission
    ├── Memory Policy
    └── Default Workspace
~~~

用户体验可以像进入某个 Agent 的空间，但 Domain 仍然是 Agent → Conversations，不是 Folder → Chat Files。

### 33.3 Activity

- Active Runs
- Waiting Permission
- Waiting User
- Reconciling
- Paused
- Unknown Outcome
- Completed Tasks
- Failed Runs
- Audit History

Activity 是执行领域的权威入口。

### 33.4 Settings

- Providers
- Hosts
- Credentials
- Workspaces
- Security
- App Settings

Workspace 不占 Bottom Navigation，但可从 Settings、Agent Default Workspace、New Conversation 和 Task 补全流程进入。

### 33.5 Conversation 页面基线

本 Baseline 只锁定必须存在的信息，不定义最终交互：

- Agent Header
- Conversation Title
- Workspace Context
- Message Timeline
- Stable Task Card
- Active Run status
- Plan
- ToolCall
- Permission
- Live Output
- Verification
- RUN_RESULT
- Composer

这些组件的布局、展开策略、动效、移动端密度和 Server/Character 差异由单独的 Conversation & Execution UX Specification 定义。

---

## 34. Future Compatibility Without Premature Implementation

### 34.1 Conversation Branch

未来可以增加：

- parentConversationId
- branchFromMessageId

v0.1 不提前创建永远为 null 的字段，但必须：

- 使用稳定全局 ID。
- Message 提交后不覆盖。
- Timeline 使用明确 ordinal。
- 不假定每条 Message 只有一个后继。
- 不把整个 Conversation 存成单一不可拆分 JSON。

### 34.2 Regenerate / Swipe

未来可以增加：

- responseGroupId
- candidateIndex
- selectedCandidateId

v0.1 不实现，也不建立“一条 User Message 只能有一条 Assistant Message”的唯一约束。

### 34.3 Character and Memory

未来可增加：

- Character Card import UI
- Scenario
- Example Dialogue
- Lorebook
- Group Chat
- Agent Shared Memory
- Workspace Memory
- Embedding index
- 用户查看、编辑和遗忘 Memory

这些都不是 v0.1 交付物。

---

## 35. MVP Non-Goals

- 不做通用 Skill Registry。
- 不动态下载并执行第三方 Skill。
- 不做完整 Tavern 或 Character Card Import UI。
- Character Template 和 Character-specific creation UI 不是强制交付项。
- 不做 Lorebook、Swipe、Regenerate 或 Group Chat。
- 不做长期 Agent Shared Memory 或向量 Memory。
- 不做多 Agent 协作。
- 不做多服务器编排。
- 不做无人值守定时任务。
- 不做团队账户、云同步、共享审批和 RBAC。
- 不做服务器端常驻 daemon。
- 不做完整移动 IDE、文件编辑器或 Git 工作台。
- 不做长期交互式 SSH Terminal。
- 不做 SFTP、端口转发和 Jump Host。
- 不处理复杂交互式 sudo、MFA 和 TTY。
- 不实现可证明任意 shell command 安全性的完整静态分析器。
- 不实现完整 DLP 平台；只实现 Baseline 所要求的保守 sensitivity / egress boundary。
- 不在 Baseline 锁定 Android FGS type、调度 API 或 Play policy 实现。
- 不承诺进程被系统强制终止后继续执行。
- 不允许自动执行 CRITICAL 操作。
- 不从自然语言代码块猜测并执行命令。
- 不在首版同时支持多个活跃 Run。
- 不以 iOS 为首发约束目标。
- 不提前创建 Lightweight Tool Execution 等未来实体。

---

## 36. Acceptance Criteria

### 36.1 Agent-first Domain

- 用户可以创建多个 Agent。
- 正式实体名称为 Agent，不存在 AgentProfile。
- 每个 Agent 拥有独立 Persona。
- 一个 Agent 可以创建多个 Conversation。
- Agent Detail 可以继续最近 Conversation、浏览历史和创建新 Conversation。
- Template 不产生 Runtime 类型分支。
- Character-compatible Persona 可以通过 Domain 和 Custom Agent 验证；Character Template UI 不是 MVP 验收前提。

### 36.2 Conversation

- 普通 Chat 只产生 Message。
- Message 与 ModelInvocation 分开保存。
- Conversation 可以没有 Workspace。
- Conversation Timeline 可以同时显示 Message 和稳定 Task Card。
- Plan、ToolCall、Permission 和 Verification 不存为普通 Message。
- RUN_QUESTION 和 RUN_RESULT 可以引用 Run。
- Conversation 可以归档和恢复。
- Multiple Conversations 是 MVP 基础能力。

### 36.3 Task and Run

- SSH 请求必须创建 Task 和 Run。
- Task 引用触发它的 Message。
- Task 明确记录 workspaceId。
- Run 冻结不可变 Workspace Snapshot。
- 一个 Task 可以拥有多个 Run。
- Run 状态机跨 UI 重建和进程重启保存。
- Run Snapshot 冻结历史配置和当时的 policy provenance，但不冻结当前 Runtime 应用更严格 mandatory safety rules 的能力。
- 执行时 safety policy version、checks、effective decision 和理由以 append-only event 保存，不修改 Snapshot。
- Snapshot 后的用户回答、Permission、Credential recovery 和预算扩展以 append-only runtime input 保存。
- Runtime 可以从 Snapshot、runtime inputs 和 runtime events 恢复当前 Run context。
- 修改结果未知时，Run 进入 RECONCILING，不得自动重放。
- 未解决的 UNKNOWN_OUTCOME 阻止当前及后续 Run 中潜在冲突的 mutating ToolCall。
- Cancel Run 只取消当前 Run，Task 默认仍 OPEN；它不能解决或清除 unresolved side-effect obligation。
- Retry 在命中历史 Run 的 unresolved obligation 时，先进入 reconciliation precondition；只有远端真相明确后才能恢复正常 mutation flow。
- Close Task 不隐式取消活跃 Run。
- Close Task、Archive 或其他 lifecycle 终态不把 unresolved external side effect 标为 resolved。
- Verification 通过后 Task 才能 RESOLVED。

### 36.4 Workspace

- Agent 可以有 Default Workspace。
- Conversation 可以选择或不绑定 Workspace。
- Agent 默认值变化不影响已有 Conversation。
- v0.1 中已有执行型 Conversation 切换 Workspace 时，默认创建新 Conversation。
- 数据模型不阻止未来 Task 选择不同 Workspace。
- Workspace 不保存 Credential。

### 36.5 Provider and Host

- 支持 OpenAI-compatible、Anthropic 和 Custom Base URL。
- 真实执行只对通过结构化 ToolCall 能力探测的模型开放。
- 用户可以配置 SSH Host。
- 首次连接需要确认 Host Key。
- Host Key 变化不能静默接受。
- SSHHost target-defining fields 具有 revision；Execution Envelope 冻结本次实际连接目标，不依赖执行时重新读取可变 SSHHost。

### 36.6 Permission and Safety

- ToolCall 未持久化前不得执行。
- Tool Proposal 在 Permission Engine 前被规范化为 immutable Execution Envelope。
- Envelope 至少包含 Skill、operation、immutable sshTarget、logical credentialRef、workingDirectory、shellMode、exactCommandBytes、environment、stdinMode 和 timeout。
- immutable sshTarget 至少包含 sshHostId、sshHostRevision、hostname、resolvedAddress、port、username、pinnedHostKeyFingerprint 和 targetDigest。
- Permission analysis、PermissionRequest、Approve Once 和 SSH execution 引用同一个 Envelope Digest。
- 执行前重新计算的 Digest 必须与获批 Digest 匹配，否则阻止执行。
- Skill 不得在审批后重新拼装或改变命令。
- SSH Skill 必须连接 Envelope 冻结的 resolvedAddress、port、username 和 pinnedHostKeyFingerprint，不得通过 sshHostId 重新解析当前 SSHHost target。
- SSHHost 的 hostname/address、port、username 或 Host Key identity 变化不会被既有 Envelope 自动继承；必须新建 Envelope、重新风险判定并在必要时重新审批。
- 同一 logical credentialRef 的 secret rotation 可以按 recovery 规则使用并记录 rotation version；它不等于 SSH target mutation。
- ASK 未获得匹配审批时不得执行。
- DENY 永远不得执行。
- 审批绑定字段变化后必须重新审批。
- 每次实际执行都合并 Frozen Run Policy 与 Current Runtime Mandatory Safety Floor，并取更严格结果。
- 当前 Runtime hard DENY、Safe Read Profile、Envelope validators、critical-operation rules、Egress hard blocks 和 mandatory invariants 可以收紧旧 Run。
- Runtime update 不得把 Snapshot 冻结的 ASK、DENY 或其他安全约束静默放宽。
- READ 只有匹配 Runtime Safe Read Profile 才能自动 ALLOW。
- redirection、command substitution、eval、arbitrary interpreter、dynamic expansion、sudo/root、unknown executable、network egress 和不可分析 compound command 不得自动 ALLOW。
- 无法证明安全的 SSH Envelope 进入 ASK 或 DENY，不能因模型声明 READ 而 ALLOW。
- 模型不能直接调用 SSH。
- Persona、Memory、Conversation 和 Tool Output 不能授予权限。
- Character Card 导入不能修改 Skill、Permission 或 Workspace。
- Credential 不出现在 Message、模型上下文、普通日志或报告中。
- 修改命令结果未知时不得自动重放。
- 回滚作为新的修改操作重新审批。

### 36.7 Observation and Egress

- Execution Risk 与 Data Sensitivity 分别判定。
- Permission ALLOW 不等于 Provider egress ALLOW。
- Tool output 在进入 Provider 前经过 Observation / Egress Guard。
- Observation 至少支持 NORMAL、SENSITIVE 和 SECRET。
- 无法分类的 observation 至少按 SENSITIVE 处理。
- SENSITIVE output 支持 redact、summarize、ASK 或 BLOCK。
- SECRET raw output 默认 BLOCK。
- ToolEvent / LogReference 区分 raw local reference 和 Provider-safe reference。
- Context builder 不能直接读取 raw observation 绕过 Guard。
- Credential exclusion 不是唯一数据保护机制。
- 恢复旧 Run 时应用当前 Egress Guard hard blocks；当前更宽松规则不能绕过 Snapshot 冻结的数据限制。

### 36.8 Execution and Verification

- 用户可以看到每条实际 SSH 操作和输出。
- 符合 Safe Read Profile 的 READ 动作可以自动允许，但仍可见和可审计。
- MODIFY 和 DESTRUCTIVE 操作显示 Permission Card。
- 用户批准后可以真正修改服务器。
- Cancel Run 后不得在被取消的 Run 中创建新的 ToolCall。
- 被取消 Run 的 unresolved UNKNOWN_OUTCOME 在 Retry Run 中仍阻断潜在冲突的 mutation，直至 reconciliation 建立远端真相。
- 修复后执行独立 Verification。
- VerificationRecord 明确保存 Criterion、Check、Evidence、Evaluator 和 Verdict。
- 安全关键 REQUIRED criterion 优先使用 deterministic evaluator。
- MODEL_ASSISTED 结论单独不能产生高可信 PASS。
- 所有 REQUIRED criteria 获得可信 PASS 后才允许 COMPLETED。
- 已展示并用于批准修改的关键 Success Criteria / Verification Plan 不得被静默削弱。
- 实质降低成功标准必须版本化重规划，并在必要时要求用户确认。
- 不能只看 exitCode 或模型文案宣告成功。
- FinalReport 包含根因、证据、修改、验证和当前状态。

### 36.9 Recovery

- App 进入后台后，运行保持用户可感知并在平台允许范围内 best-effort continuation。
- UI 重建不丢失 Domain 状态。
- 进程终止后恢复 Task、Run、Plan、Permission 和 ToolCall。
- 恢复后不自动重新执行可能产生副作用的命令。
- 存在 UNKNOWN_OUTCOME 时恢复到 RECONCILING 或等价 phase。
- 恢复时从历史 ToolCall、Envelope 和 events 重建跨 Run unresolved side-effect obligations，并在新 Run mutation 前执行 Task / Workspace safety preflight。
- 恢复旧 Run 时重新应用 Current Runtime Mandatory Safety Floor；当前规则比 Snapshot 更严格时采用当前规则，比 Snapshot 更宽松时保留 Snapshot 约束。
- 当前 safety policy version 和执行时有效 decision 作为新 event 保存，旧 Snapshot 和历史 decision 不被覆盖。
- WAITING_PERMISSION 和 WAITING_USER 可以跨进程保存。
- 已完成 Run 的记录不可被恢复流程改写。

### 36.10 Failure Tests

至少覆盖：

- 错误 SSH 密码
- SSH 端口不可达
- Host Key 变化
- Approve Envelope → 修改 SSHHost hostname 或 port → 尝试执行；既有 Envelope 必须失效并阻止发送，不得连接修改后的 target，使用新 target 必须创建新 Envelope
- Approve Envelope → 修改 SSHHost username 或 Host Key identity → 尝试执行；必须阻止并创建新 Envelope，不得继承变化
- frozen resolvedAddress 不可达但 DNS 存在其他地址；不得在既有 Envelope 下静默 fallback
- 同一 logical credentialRef 的 secret rotation；在不改变 SSH target 的前提下按 recovery 规则使用并记录 rotation version
- Provider 401、429、timeout 和 context overflow
- 无效结构化 ToolCall
- Permission analysis、用户批准和执行前的 Envelope Digest 不一致
- Skill 尝试在审批后补充、改写或重新拼装命令
- 命令非零退出
- 命令 timeout
- 修改命令发送后立即断网
- UNKNOWN_OUTCOME 的 CONFIRMED_NOT_EXECUTED、CONFIRMED_EXECUTED、PARTIAL_OR_INCONSISTENT 和 UNRESOLVED 分支
- 未解决 UNKNOWN_OUTCOME 时尝试发起新的修改
- 用户执行中 Cancel Run
- UNKNOWN_OUTCOME → Cancel Run → Retry；新 Run 的潜在冲突 mutating ToolCall 仍被阻断并先进入 reconciliation precondition
- UNKNOWN_OUTCOME → Cancel Run → CONFIRMED_EXECUTED；新 Run 先进入 Verification，之后允许正常流程
- UNKNOWN_OUTCOME → Cancel Run → CONFIRMED_NOT_EXECUTED；解除 obligation 后允许重新规划和正常 retry flow
- 历史 obligation 与新 Run 属于同一 Workspace / Host 且 target conflict 不可证明为无冲突；必须保守阻断 mutation
- Close Task 时仍存在活跃 Run
- unresolved obligation 存在时尝试 Erase source history；不得删除重建安全义务所需的最小 facts
- 用户拒绝 Permission
- Verification 失败
- REQUIRED Verification INCONCLUSIVE
- 模型尝试把 REQUIRED criterion 静默改为 ADVISORY
- 超大 stdout
- READ 命令包含 redirection、command substitution、eval、interpreter、sudo、unknown executable、network egress 或 compound command
- READ 操作输出 .env、private key、token、/etc/shadow、database dump 或 confidential log
- Context builder 尝试读取 raw observation reference
- App 在等待审批、发送命令前和命令执行中分别被终止
- Agent 配置或 Workspace 在活跃 Run 期间被修改
- 创建 Run → PAUSED → Runtime Mandatory Safety Policy 升级为更严格版本 → Resume；更严格 current rule 必须生效并记录为新 event
- 创建 Run → PAUSED → Runtime Mandatory Safety Policy 更新为更宽松版本 → Resume；不得放宽 Snapshot 冻结的安全约束
- 已批准但未发送的 Envelope 在 Runtime upgrade 后命中新的 hard DENY 或 validator；必须阻止执行，旧审批不得覆盖
- 历史 egress ALLOW 在当前 Runtime 命中新的 Egress hard block；原始 observation 不得发送给 Provider
- 同一 Agent 下多个 Conversation 的隔离
- Character Persona 文本尝试声明系统权限

---

## 37. Consistency Decisions

本 Baseline 已统一以下冲突点：

1. AgentProfile 全部统一为 Agent。
2. Turn 拆分为产品领域 Message 和 Runtime 领域 ModelInvocation。
3. Conversation、Task、Run 和 Message 分别建模。
4. Timeline 只投影 Message 和 Task，不把 Run 伪装为 Message。
5. v0.1 所有 SSH invocation 进入 Task/Run；不将该规则永久扩展到所有未来 Skill。
6. Conversation Workspace 是上下文默认值，Task Workspace 是目标引用，Run Snapshot 是最终安全事实。
7. v0.1 默认通过新 Conversation 切换 Workspace，但 Domain 保留未来多环境 Workflow 的演进空间。
8. Persistence 增加 Persona、Conversation、Message、ConversationSummary 和 ModelInvocation。
9. Navigation 从 Task-first 统一为 Home / Agents / Activity / Settings。
10. Character compatibility 只影响 Persona，不影响 Capability。
11. Non-Goals 不包含任何已被 Acceptance Criteria 要求的能力。
12. Multiple Conversations 和 Agent-first UX 已进入 MVP 验收标准。
13. READ 与 automatic ALLOW 已拆分；只有 Safe Read Profile 可以自动执行。
14. Execution Risk 与 Data Sensitivity 已拆分；Provider 只能接收 Egress Guard 产生的安全视图。
15. Tool Proposal、Permission 和 SSH execution 通过同一 immutable Execution Envelope 和 Digest 绑定。
16. Run Snapshot 保持不可变；其后的交互以 append-only runtime inputs 和 events 参与恢复。
17. UNKNOWN_OUTCOME 通过 RECONCILING phase 处理，并阻断后续修改。
18. Verification 已定义 Criterion → Check → Evidence → Evaluator → Verdict 信任链。
19. Cancel Run 与 Close Task 已分离。
20. Character-compatible Domain 保留，但 Character Template UI 不作为强制 MVP 范围。
21. Android background 只承诺用户发起、可感知、best-effort continuation；具体平台机制留给 System Architecture。
22. 本轮未增加顶层持久化实体；新增内容均为现有实体的 value object、结构化字段、append-only event 或 event-derived safety projection。
23. SSH execution target 在 Envelope 中冻结为 immutable sshTarget；审批、连接和 reconciliation 均使用同一 target，不通过可变 SSHHost 静默重定向。
24. Historical Policy Snapshot 与 Current Runtime Mandatory Safety Floor 已拆分；旧 Run 保留历史约束，同时接受当前更严格规则，任一更新都不能静默放宽安全边界。
25. UNKNOWN_OUTCOME 的 unresolved side-effect obligation 跨 Cancel、Run 终态、Retry 和进程恢复保留，并在后续 Run mutation 前执行 Task / Workspace safety preflight。
26. 生命周期状态与远端事实已拆分；Cancel Run、Close Task、Archive 或新 Run ID 均不代表远端不确定性已经解决。

---

## 38. Next Design Stages

本 Baseline 已通过 Final Architecture Review，状态为 `Architecture Baseline Approved`，Architecture Baseline 阶段至此结束。后续拆成两个独立设计问题；两者必须引用本 Approved Baseline。

### A. System Architecture

定义：

- UI / Presentation
- Domain
- Agent Runtime
- Skill Runtime
- Permission Engine
- Provider Adapter
- SSH
- Persistence
- Credential Vault
- Background Execution

重点包括模块关系、依赖方向、线程或协程模型、持久化边界和恢复机制。

### B. Conversation & Execution UX Specification

定义用户如何体验：

- Message
- Task Card
- Active Run
- Plan
- ToolCall
- Permission Card
- Live Output
- Verification
- Run Result
- Composer
- Agent Header
- Workspace Context

同时定义 Server Agent 与 Character Agent 在共享 Conversation Framework 下的差异化呈现。

两份设计都必须引用本 Approved Baseline，不得自行改变这里的 Domain 与安全事实。当前先进入 Conversation & Execution UX Specification；System Architecture 保持独立，且两项完成前不进入 Implementation Plan 或代码。
