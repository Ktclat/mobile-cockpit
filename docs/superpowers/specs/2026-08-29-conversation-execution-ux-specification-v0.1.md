# Conversation & Execution UX Specification v0.1

- Version: 0.1
- Status: Conversation & Execution UX Specification v0.1 — Approved
- Date: 2026-08-29
- Primary platform: Android-first mobile
- Upstream authority: [Mobile-Native Personal Agent Runtime — v0.1 Architecture Baseline](./2026-08-29-mobile-native-personal-agent-runtime-v0.1-baseline.md)
- Document role: Approved Baseline 之上的 Conversation、Execution 和 Agent-first 移动端体验 authoritative source of truth
- Freeze status: Frozen；任何已批准核心 Interaction Contract 的变更必须通过 `UX Specification Change Request`
- Phase boundary: 不进入 System Architecture、Implementation Plan、UI framework 选型或代码

本规范定义用户如何看到、理解和控制 Agent、Conversation、Task、Run、Permission、ToolCall、Verification 与恢复状态。它只定义 Presentation、Interaction 和 UX contract，不重定义 Approved Baseline 的 Domain、Runtime 或安全语义。

如果本规范与 Approved Baseline 冲突，以 Baseline 为准，并必须提出 `Architecture Baseline Change Request`；不得在本规范中静默改变上游不变量。任何后续需要改变本规范已经批准的核心 Interaction Contract，必须明确提出 `UX Specification Change Request`；若同时影响 Approved Baseline，则仍须提出 `Architecture Baseline Change Request`。System Architecture 和后续下游设计不得静默改变本规范。

---

## 1. Executive UX Decision

### 1.1 核心答案

普通聊天、Character-style conversation 和真实 Agent execution 共用一套：

> **Agent-owned Conversation Shell + typed Timeline projections + capability-driven execution surfaces**

统一框架不是把所有内容都做成聊天气泡，而是把两种不同性质的内容放在同一条有序、可持续的 Conversation Timeline 中：

~~~text
Conversation Timeline
= expressive Message
+ stable Task Projection
~~~

- 普通聊天、Persona greeting、Character-style 对话、RUN_QUESTION 和 RUN_RESULT 使用 Message。
- Plan、ToolCall、Live Output、Permission、Verification 和 Run state 始终属于 Task Card / Run Detail，不伪装成 Message。
- Agent、Conversation、Task、Run 仍是不同实体；视觉统一不代表领域合并。
- 任何 Agent 只要被明确配置了真实 Skill，就可以在同一 Conversation 中产生 Task Card；没有 Skill 时只呈现对话，不显示空的执行 UI。
- 不建立 `Server Mode`、`Character Mode` 或第二套 Runtime。

### 1.2 为什么它不是 ChatGPT / DeepSeek clone

本产品的主对象不是模型，也不是一条无差别聊天列表，而是持续存在的 Agent：

- Home 首先回答“哪个 Agent 或哪件 Agent 工作现在需要我”。
- Agents 是一级导航；Conversation 属于 Agent。
- 用户继续的是某个 Agent 的关系和工作上下文，不是回到一个模型名称下的 Chat。
- Conversation 能把“说了什么”和“真实做了什么”同时保留，但用不同视觉语法表达。
- 活跃 Run、精确 Permission、真实输出、Verification Evidence 和安全恢复在手机上持续可见。
- Activity 是执行与审计的权威入口，而不是 Home 伪装成 Task Manager。
- Provider / Model 是 Agent 配置，不抢占 Conversation 的身份位置。

产品差异化公式：

~~~text
Agent identity and continuity
+ ordinary conversation
+ visible structured execution
+ explicit human authority
+ evidence-backed completion
= Mobile Agent Cockpit
~~~

### 1.3 UX 北极星

> 用户在任何时刻都能快速回答：我在和谁交流、它正作用于哪里、它现在在做什么、下一步需要谁行动、真实世界结果是否已被验证。

### 1.4 首版体验主线

~~~text
Choose / continue an Agent
→ converse naturally
→ intent becomes a Task when real SSH work is required
→ Task Card shows plan and evidence gathering
→ immutable operation requires explicit review
→ live execution remains visible
→ independent verification establishes outcome
→ RUN_RESULT returns the result to the conversation
~~~

---

## 2. Upstream Contract and Scope

### 2.1 必须继承的 Baseline 事实

本规范不得改变：

- Agent → Conversations 的 ownership。
- Agent、Persona、Conversation、Message、Task 和 Run 的分离。
- Conversation Timeline = Message + Task Projection。
- Conversation context / Task target / Run Snapshot 三层 Workspace 语义。
- immutable Run Snapshot。
- immutable Execution Envelope 和 frozen SSH target。
- Permission Engine 的 ALLOW / ASK / DENY 语义。
- Safe Read Profile 与“READ 不等于自动允许”。
- Historical Policy Snapshot / Current Runtime Mandatory Safety Floor。
- Observation / Egress boundary。
- UNKNOWN_OUTCOME / RECONCILING 和跨 Run side-effect obligation。
- Verification Criterion → Check → Evidence → Evaluator → Verdict。
- Persona / Character 与 Capability、Permission、Workspace 的隔离。
- v0.1 同一时间最多一个活跃 Run。

### 2.2 本规范负责

- Home、Agents、Agent Detail、Conversation、Activity 的信息结构。
- Multiple Conversations 的创建、切换、继续和归档体验。
- Message 与 Task Card 在同一 Timeline 中的视觉关系。
- Active Run、Plan、ToolCall、Live Output、Permission、Verification 和 RUN_RESULT 的渐进披露。
- Composer 在普通、执行中、等待审批和等待回答时的行为。
- Workspace context 的三层可见性。
- Needs Attention 的优先级和入口。
- Server Agent 与未来 Character Agent 的共享框架和差异化表达。
- Execution Envelope 的 byte-faithful 安全展示规则。
- 状态、错误、恢复、可访问性和 UX Acceptance Criteria。

### 2.3 本规范不负责

- UI toolkit、design system library 或导航框架选型。
- 数据库、线程、协程、stream transport 或状态管理实现。
- Android foreground service、通知渠道或 Play policy 选型。
- Runtime / Permission / SSH / Egress Guard 的内部代码结构。
- Character Card import、Lorebook、Swipe、Regenerate、Group Chat 或长期 Memory UI。
- Skill Marketplace、动态 Skill 安装或多 Agent 协作。
- 多个同时活跃 Run、无人值守队列或任务调度。
- 完整 SSH terminal、SFTP、文件编辑器或移动 IDE。

### 2.4 下游变更纪律

本规范中的 Presentation projection 不是新 Domain entity。System Architecture 可以选择实现方式，但不能以实现便利为由改变：

- 哪些内容是 Message，哪些是 Task / Run projection。
- 哪些用户动作是结构化 PermissionDecision 或 runtime input。
- 哪个 Workspace / target 才是执行事实。
- 哪些状态能够被恢复、取消或视为完成。

---

## 3. UX Goals, Users and Success

### 3.1 主要用户任务

用户需要在手机上完成：

1. 选择一个可信且熟悉的 Agent。
2. 在多个 Conversation 中保留不同主题或 Workspace context。
3. 像普通聊天一样描述问题和补充背景。
4. 看到 Agent 已把真实执行意图升级为 Task。
5. 在有限屏幕上理解计划、当前步骤和最新证据。
6. 安全审阅不可变的真实操作并作出结构化决定。
7. 离开页面后仍能知道 Run 是否活跃、暂停或需要处理。
8. 区分“命令执行完成”和“目标已经验证成功”。
9. 在断线、取消或 UNKNOWN_OUTCOME 后理解远端不确定性。
10. 回到 Agent 和 Conversation，继续关系而不只查看任务记录。

### 3.2 UX 成功条件

在可用性测试中，目标用户应能在不阅读内部架构说明的前提下：

- 从 Home 识别唯一最紧急的 Needs Attention 项及其所属 Agent。
- 从 Conversation 首屏识别 Agent、Conversation、Workspace context 和活跃 Run 状态。
- 区分普通 Assistant Message 与 Runtime-owned Task Card。
- 找到当前 Plan step、最近一次真实操作和对应输出。
- 在审批前识别 Workspace、Host、resolved target、username、目录和完整精确命令。
- 明白输入“可以”“yes”不会批准操作，必须使用 Permission action。
- 明白 Cancel Run 不等于回滚，也不等于解决 UNKNOWN_OUTCOME。
- 明白 `Completed — Verified` 与 `Command succeeded`、`Failed`、`Inconclusive`、`Remote state unknown` 的区别。
- 在 Character-style 对话中仍能识别 system-owned safety surfaces，且不会把 Persona 文案误认为系统权限。

### 3.3 体验原则

1. **Agent-first**：Agent 身份和连续性先于模型品牌、聊天文件和任务列表。
2. **Conversation is the home of context**：语言交互与真实执行发生在同一上下文，但不混为一种内容。
3. **Progressive disclosure**：首屏只显示现在重要的事实，完整审计始终可达。
4. **Action follows authority**：只有 Runtime-owned surface 可以提供 Approve、Reject、Resume、Cancel 等权威动作。
5. **State over animation**：恢复后的确定状态比流畅但虚假的“仍在运行”动效重要。
6. **Evidence before success**：用户看到的是验证结论及证据，不是模型自信程度。
7. **Safe interruption**：离开页面、切换 Conversation、发普通消息都不隐式取消或改变 Run。
8. **Persona never decorates away risk**：身份风格不能覆盖风险颜色、系统标签、精确命令或安全警告。
9. **No hidden mode**：页面根据能力、上下文和状态呈现，不依赖 Server / Character 二元模式。
10. **Mobile attention is scarce**：阻塞项突出，日志和历史按需展开，重要操作不依赖横向比较多个面板。

---

## 4. Design Alternatives and Decision

### 4.1 Alternative A — Everything as Message

把 Plan、ToolCall、Permission 和 Verification 都插入聊天流，类似带工具调用的普通 AI Chat。

优点：

- 表面简单。
- 用户熟悉聊天气泡。

缺点：

- 违反 Approved Baseline 的 Message / Task / Run 分离。
- 长任务产生大量噪声，移动端难以回看。
- Permission 容易被模型文本视觉模仿。
- 多次 Run、恢复和 Verification 难以形成稳定结构。
- 最终仍像 ChatGPT clone 加几种特殊消息。

结论：拒绝。

### 4.2 Alternative B — Separate Chat and Operations Modes

普通对话放在 Chat 页面，真实执行切换到独立 Operations / Terminal 页面；Character Agent 走另一套 Chat UI。

优点：

- 执行内容空间充足。
- 安全页面边界明显。

缺点：

- 用户上下文被切断。
- Server / Character 被硬编码成产品类型。
- 用户必须理解“当前在哪个模式”，且可能在错误页面继续对话。
- 两套体验容易演化成两套 Runtime 假象。

结论：拒绝。

### 4.3 Alternative C — Unified Conversation Shell with Typed Task Projection

Conversation 是持续上下文；Timeline 中只有 Message 和稳定 Task Card 两种一级 item。Task Card 在原位置更新状态，复杂执行进入 Run Detail；活跃 Run 通过 sticky strip 保持可见。

优点：

- 与 Approved Baseline 完全一致。
- 普通聊天、Character-style conversation 和真实执行共享框架。
- 安全 surface 拥有清晰系统边界。
- 移动端可以在摘要、展开和详情之间渐进披露。
- 通过 Agent identity、Multiple Conversations、Activity 和证据链形成明显差异化。

代价：

- 必须认真设计 Task Card、active strip 和 Composer context。
- Timeline 更新与滚动位置需要稳定，不能简单追加所有 runtime event。

结论：采用。

---

## 5. Unified Conversation Framework

### 5.1 Conversation Shell

所有 Agent Conversation 共用五个区域：

~~~text
Conversation Shell
├── Agent Header
├── Conversation / Workspace Context Rail
├── Active Run Strip, conditional
├── Timeline
│   ├── Message
│   └── Task Card
└── Composer
~~~

共享 Shell 保证用户无需学习“对话产品”和“执行产品”两套导航。

### 5.2 Timeline 一级 item

| Item | 领域来源 | 视觉语法 | 是否原位更新 |
|---|---|---|---|
| Message | Message | 人类或 Agent 的表达 | STREAMING 期间更新；提交后不覆盖 |
| Task Card | Task + latest Run projection | 系统拥有的结构化工作卡 | 随领域事实更新 projection，保持 Timeline 位置 |

Plan、ToolCall、Permission、Live Output、Verification 和 Run history 是 Task Card 的内部视图或详情内容，不成为 Timeline 一级 item。

### 5.3 同一 Conversation 中的三种体验

| 场景 | Message | Task Card | Workspace chrome |
|---|---|---|---|
| 普通聊天 | 主要内容 | 无 | 无 Workspace 时最小化 |
| Character-style conversation | 主要内容，强化 Persona identity | 默认无 | 未配置时隐藏 |
| 真实 Agent execution | 对话仍可继续 | 出现并承载真实工作 | 明确且持续可见 |

它们不是模式。一个 Conversation 可以从普通讨论自然进入真实执行，也可以在 Run 进行时继续普通聊天。

### 5.4 表达、事实、权限、证据四层

用户必须能从视觉上区分内容权威来源：

| 层 | 示例 | 视觉要求 |
|---|---|---|
| Agent expression | 普通回复、解释、Persona 语气 | 使用 Agent identity，可带个性化视觉 |
| Runtime fact | Run state、ToolCall status、exit code、target | system-owned card，稳定中性样式 |
| User authority | PermissionDecision、Egress decision、Cancel | 只能通过受信任 action surface 产生 |
| Evidence | Verification actual value、log reference | 明确来源、时间、Evaluator 和 verdict |

模型文本即使写出“已批准”“系统验证通过”或模仿按钮，也只能被渲染为普通文本，不能获得 system-owned chrome、状态 icon 或可交互 action。

### 5.5 Presentation projections, not entities

以下名称仅表示 UI projection，不新增 Persistence entity：

- Agent Summary
- Conversation Summary Row
- Task Card Projection
- Active Run Strip
- Needs Attention Item
- Activity Row
- Permission Review View
- Verification Summary

每个 projection 必须可以追溯到 Approved Baseline 中的领域事实。

---

## 6. Visual Trust Layers

### 6.1 三层视觉所有权

~~~text
System Trust Layer
    risk, permission, target, status, verification, warnings

Agent Identity Layer
    avatar, name, accent, Persona expression, greeting

Untrusted Content Layer
    model prose, imported Persona text, tool output, logs
~~~

优先级从上到下递减。下层不能改变上层的颜色含义、标签、交互控件或信息顺序。

### 6.2 System Trust Layer

必须满足：

- 使用固定的状态 icon、文字标签和对比度规则。
- 风险不能只靠颜色表达。
- Permission、Egress、Verification 和 UNKNOWN_OUTCOME 使用产品固定组件。
- Persona accent 不得替换 warning、destructive、verified 或 unknown 的系统语义色。
- Runtime-owned card 带有一致的系统标识；普通 Message 不使用该标识。
- Tool output 中的 Markdown、ANSI、伪按钮和链接不能生成系统控件。

### 6.3 Agent Identity Layer

可以变化：

- avatar 或 portrait。
- Agent name、description 和 greeting。
- 非安全区域的 accent、背景纹理和消息节奏。
- Assistant Message 的语言风格。

不能变化：

- Permission action 的名称和位置语义。
- 风险标签、真实 target、Verification verdict。
- Task / Run status copy 的事实含义。
- Credential、Skill 或 Workspace 权限。

### 6.4 Untrusted Content Layer

- Model prose 使用普通内容排版，不能伪装为系统 banner。
- Tool output 在隔离的 output surface 中显示，不解析 ANSI 控制效果。
- 来自日志的 URL 默认作为文本；只有明确安全策略允许时才成为外部链接。
- 双向文字控制符、零宽字符和其他视觉欺骗字符在安全关键 surface 中必须可见化。

---

## 7. Information Architecture and Navigation

### 7.1 Root navigation

Approved root navigation 保持：

~~~text
Home | Agents | Activity | Settings
~~~

| Root | 回答的问题 | 不承担 |
|---|---|---|
| Home | 现在最值得继续哪个 Agent / Conversation / attention item | 完整任务和审计列表 |
| Agents | 我有哪些 Agent，它们各自有哪些 Conversation | 全局运行历史 |
| Activity | 哪些 Run 正在发生、等待处理或已经发生 | Agent 创建与 Persona 编辑 |
| Settings | Provider、Host、Credential、Workspace 和安全设置 | 日常 Conversation |

### 7.2 Deep navigation

~~~text
Home / Agents
→ Agent Detail
→ Conversation
→ Run Detail / Permission Review / Evidence Detail

Activity
→ Activity Item
→ Conversation Task Card or Run Detail
~~~

- Conversation、Run Detail 和 Permission Review 是沉浸式深层页面，可隐藏 root bottom navigation 以释放空间。
- 系统 Back 只导航，不 Cancel Run、不 Reject Permission、不丢失草稿。
- 离开 Permission Review 不等于作出决定。
- 从 Activity 打开 Run 后，必须能回到所属 Conversation 和 Task Card。

### 7.3 Global active Run affordance

v0.1 最多一个活跃 Run，因此全局只显示一个 Active Run affordance：

- 在所属 Conversation 中：显示于 header/context rail 下方的 sticky Active Run Strip。
- 在其他 root 页面：显示为 root 内容与 bottom navigation 之间的 compact active strip。
- 在其他 Conversation 中：显示“另一个 Conversation 有活跃 Run”，点击返回所属 Conversation。
- 点击 strip 打开 Task Card 或 Run Detail。
- Cancel 是明确 action，不通过关闭 strip、返回或滑动触发。

### 7.4 Navigation invariants

- 切换 root、Agent 或 Conversation 不改变 Run state。
- 一个 Conversation 的 Workspace context 不因查看另一个 Conversation 而变化。
- Deep link 必须落在重建后的当前状态，不展示缓存的过期按钮。
- Notification 只能 deep-link 到受信任页面，不能直接 Approve 高风险操作。

---

## 8. Mobile Information Density

### 8.1 四级渐进披露

| Level | Surface | 适合内容 |
|---|---|---|
| L0 Ambient | chip、badge、sticky strip | Agent、Workspace、Run state、是否需行动 |
| L1 Inline Summary | Message、Task Card、attention card | 当前目标、步骤、最新事实、主 action |
| L2 Quick Detail | Bottom Sheet | 短选择、上下文检查、Conversation switcher、Workspace picker |
| L3 Authoritative Detail | Full Page | Permission Review、Run Detail、完整 output、Verification Evidence、Audit |

### 8.2 Surface selection rules

使用 Inline Card：

- 内容与当前 Timeline 位置强相关。
- 用户需要持续看到状态。
- 可以在不阅读全部历史的情况下作出下一步选择。

使用 Bottom Sheet：

- 选择是短暂、可逆、项目数量有限的。
- 用户完成选择后立即回到原上下文。
- 内容不是安全审批的唯一载体。

使用 Full Page：

- 内容较长、需要滚动或交叉检查。
- 涉及 Permission、精确 bytes、完整 target、Evidence 或审计。
- 用户需要明确进入和离开一个专注任务。

使用 Dialog：

- 只确认单一明确后果，例如 Cancel Run 或永久 Erase。
- 不承载长命令、日志、Verification 或多段说明。

### 8.3 Default disclosure matrix

| 内容 | Timeline 默认 | 展开后 | Authoritative detail |
|---|---|---|---|
| 普通 Message | 完整正文 | 辅助 metadata | 通常不需要 |
| 历史 Task | 状态、结果、目标 | Plan / latest Run summary | Run Detail |
| 活跃 Task | 当前步骤、最新事件、主 action | Plan、ToolCall、Verification preview | Run Detail |
| Plan | 当前步骤 + 下一步 +完成数 | 当前 Plan 全部步骤 | Run Detail / Plan history |
| ToolCall | operation、状态、时长、结果摘要 | command / output preview | ToolCall Detail |
| Live Output | 最新少量逻辑行 | 较大 preview | Full Output Page |
| Permission | 风险、动作、target 摘要、Review CTA | 不在 card 内审批长内容 | Permission Review Page |
| Verification | 总体 verdict + failures / current check | criterion list | Evidence Detail |
| Run history | 最新 Run | attempt list | 独立 Run Detail |
| Digest / policy version | 隐藏于摘要 | technical facts | Audit Detail |

### 8.4 Density limits

- Home 首屏优先显示一个 active item、最多三个 Needs Attention item 和少量 Continue items；其余通过“查看全部”进入对应 root。
- Active Task Card 默认不超过约一个移动端视口；长内容必须下钻。
- Plan 默认突出 current step，只预览少量相邻步骤。
- Live Output inline preview 只显示最近若干逻辑行，不承担完整 terminal 功能。
- 已通过的 Verification criteria 默认折叠；FAIL、INCONCLUSIVE 和当前检查优先展开。
- 历史已验证 Task 默认折叠，安全未知和等待用户的 Task 默认展开。

这些是信息层级规则，不锁定像素、dp、字体或具体组件库。

---

## 9. Home

### 9.1 Purpose

Home 回答：

> 现在最值得我回到哪个 Agent，以及有没有必须由我处理的安全或执行状态？

Home 不是 Chat history，也不是完整 Task dashboard。

### 9.2 Content order

~~~text
Home
├── Global active Run, conditional
├── Needs Attention, conditional
├── Continue with Agents / Conversations
└── Start Conversation
~~~

没有 active Run 或 Needs Attention 时，Home 从 Continue 区开始，避免长期显示空的运维警报区域。

### 9.3 Global active Run

显示：

- Agent avatar 和 name。
- Conversation / Task title。
- 当前 Run state 的用户语言标签。
- current step 或“正在规划 / 验证 / 对账”。
- Workspace environment，如存在。
- elapsed time，只表示持续时间，不暗示完成百分比。

主 action 是“返回运行”。Cancel 只在明确菜单或 Run surface 中提供，不把 Home 卡片的关闭手势解释为取消。

### 9.4 Needs Attention

每张 attention card 以 Agent identity 开头，再显示：

- 需要什么：Review operation、Answer question、Resume、Reconcile、Fix credential 等。
- 属于哪个 Conversation / Task。
- Workspace / Host，如相关。
- 为什么被阻塞。
- 已等待时间。
- 单一主 CTA。

Home 不提供直接 Approve；Permission item 的 CTA 必须是 `Review operation`，进入 Permission Review Page。

如果 global active Run 自身就是 waiting / attention state，active card 吸收对应主 CTA，Home 不再为同一 Run 重复显示第二张 Needs Attention card。

### 9.5 Continue

Continue item 不是裸 Conversation row，而是 Agent-owned continuity：

- Agent identity。
- Conversation title。
- 最近 Message preview 或 Task outcome。
- Workspace chip，如存在。
- 最近活动时间。
- active / attention badge，如相关。

同一 Agent 的多个近期 Conversation 可以组合展示，但每个 Conversation 仍然独立。

### 9.6 Start Conversation

入口行为：

- 若只有一个可用 Agent，可直接进入该 Agent 的新 Conversation 设置。
- 若有多个 Agent，先选择 Agent，再可选 Workspace。
- 不先选择 Model；Model 是 Agent 配置。
- 若选择了与已有 Conversation 不同的 Workspace，创建新 Conversation，不改变已有执行型 Conversation。

### 9.7 Empty and first-run state

没有 Agent 时：

- 解释 Agent 是持续身份、能力与配置的组合。
- 主 CTA：`Create your first Agent`。
- 次要入口：了解 Server / Custom Agent 的区别，但不展示未实现 Character 功能。

有 Agent 但没有 Conversation 时：

- 以 Agent identity 展示 `Start a conversation`。
- 如果 Agent 有默认 Workspace，在开始前明确显示。

---

## 10. Agents

### 10.1 Purpose

Agents root 是用户管理“谁在为我工作 / 与我交流”的地方，不是 Provider model list。

### 10.2 Agent row / card

默认显示：

- avatar、name、简短 description。
- capability summary，例如 `SSH`，不列出完整 schema。
- Default Workspace，如存在。
- 最近 Conversation 的 title / preview。
- attention 或 active Run badge。
- 最近活动时间。

不默认显示：

- Provider API endpoint。
- policy schema version。
- 完整 SkillBinding。
- Token、成本或 Runtime 调试信息。

这些属于 Agent Configuration 或 diagnostics。

### 10.3 Ordering and grouping

默认顺序：

1. 有 active Run 或 Needs Attention 的 Agent。
2. 最近使用的 ACTIVE Agent。
3. 其他 ACTIVE Agent。

Archived Agent 进入独立入口，不与 active list 混排。

### 10.4 Actions

- Tap：进入 Agent Detail。
- Primary contextual action：Continue recent Conversation。
- New Conversation：显式按钮，不通过隐含 swipe 创建。
- Create Agent：root-level action。

Archive / Erase 不作为列表快速手势，避免误触；进入 Agent settings 后处理。

### 10.5 Agent identity over model identity

- Conversation 和 Agent list 的主标题永远是 Agent name。
- Provider / model 只在配置摘要中作为辅助 metadata。
- Provider 出错时可以显示错误来源，但不把 Agent 临时改名为模型品牌。

---

## 11. Agent Detail

### 11.1 Page structure

~~~text
Agent Detail
├── Identity Hero
├── Continue / New Conversation
├── Needs Attention for this Agent, conditional
├── Recent Conversations
├── Capabilities and Default Context summary
└── Configuration entry
~~~

### 11.2 Identity Hero

显示：

- avatar / portrait。
- Agent name。
- Persona description 的短摘要。
- ACTIVE / ARCHIVED lifecycle status。
- capability chips。
- Default Workspace chip，如存在。

不把 Skill、Permission 或 Workspace 描述为 Persona 的人格能力。例如不写“因为它是运维角色所以拥有 root”，而是分别显示 `Persona` 与 `Enabled capabilities`。

### 11.3 Primary actions

- `Continue`：打开最近 ACTIVE Conversation；没有时创建一个。
- `New Conversation`：始终创建独立 Conversation。
- Archived Agent 不允许创建 Conversation，页面改为 `Restore Agent`。

### 11.4 Recent Conversations

每行显示：

- title。
- last Message / Task outcome preview。
- Workspace context。
- active / waiting / unknown badge。
- lastActivityAt。

默认显示少量最近项，`All conversations` 进入完整列表。

### 11.5 Configuration summary

分开显示：

- Persona。
- Model Provider。
- Skills。
- Permission Policy。
- Memory Policy。
- Default Workspace。

配置修改入口可以属于后续页面，但本规范要求信息边界清晰。修改 Agent 后，活跃 Run 仍显示其 frozen revision；UI 不暗示新配置已经影响旧 Run。

### 11.6 Active Run and archive

存在 active Run 时：

- Hero 下方显示 active strip。
- Archive action 不可直接执行。
- 用户必须返回 active Run，选择继续、暂停语义允许的动作或 Cancel Run。

Cancel Run 与 Archive Agent 是两个不同动作，不组合成一个确认框。

---

## 12. Multiple Conversations

### 12.1 Product meaning

Multiple Conversations 用于同一 Agent 下不同主题、关系阶段或 Workspace context。它不是文件夹，也不是不同 Agent 的替代物。

### 12.2 Entry points

- Agent Detail → Recent / All Conversations。
- Conversation Header → Conversation Switcher。
- Home → Continue item。
- Activity → 所属 Conversation。

### 12.3 Conversation Switcher

使用 Bottom Sheet，因为它是短暂选择并需要回到当前页面。

每项显示：

- Conversation title。
- Workspace chip 或 `No workspace`。
- recent preview。
- active / attention badge。
- last activity。

Switcher 顶部固定当前 Agent identity；不混入其他 Agent 的 Conversation。

### 12.4 New Conversation

流程：

1. Agent 已确定。
2. 可选择 Workspace；默认使用 Agent Default Workspace。
3. 创建 Conversation。
4. 有 greeting 时显示 PERSONA_GREETING。
5. 聚焦 Composer。

用户从已有执行型 Conversation 选择不同 Workspace 时，UX 文案必须明确：

> `Use this workspace in a new conversation`

不能写成会原地改变当前 Conversation 的 `Switch workspace`。

### 12.5 Switching while a Run is active

- 允许查看和进入其他 Conversation。
- active Run 继续，除非用户明确 Cancel。
- 其他 Conversation 顶部显示 compact global active strip，说明 Run 属于哪个 Agent / Conversation。
- 用户在另一 Conversation 请求新的真实执行时，由于 v0.1 只能有一个 active Run，不能静默启动第二个 Run。
- 可以保存新的 USER_INPUT，并明确提供：返回 active Run、只讨论方案、或创建 OPEN Task 等待用户稍后显式启动。
- 不实现无人值守自动排队或当前 Run 结束后自动执行。

### 12.6 Archive and restore

- Archive Conversation 不影响其他 Conversation。
- 有 active Run 时不能归档所属 Conversation。
- Archive 不清除 unresolved side-effect obligation。
- 恢复后保持原 Agent ownership 和 Workspace context。

---

## 13. Conversation Screen Anatomy

### 13.1 Layout

~~~text
┌──────────────────────────────────┐
│ Back  Agent identity       Menu  │
│ Conversation title / switcher    │
│ Workspace context, conditional   │
├──────────────────────────────────┤
│ Active Run Strip, conditional    │
├──────────────────────────────────┤
│                                  │
│ Timeline                         │
│  Message                         │
│  Message                         │
│  Stable Task Card                │
│  Message / RUN_RESULT            │
│                                  │
├──────────────────────────────────┤
│ Composer context / blocker       │
│ Composer                         │
└──────────────────────────────────┘
~~~

### 13.2 First-screen hierarchy

首屏优先级：

1. Agent identity。
2. Conversation title。
3. Workspace context，如执行相关。
4. blocking / active Run state。
5. 当前 Timeline 内容。
6. Composer。

不能让模型品牌、token usage、调试日志抢占前四项。

### 13.3 Header collapse

- 初始或回到顶部时可以展示较完整 Agent identity。
- 向下阅读时压缩为 compact header，保留 avatar、name、Conversation title 和关键 Workspace / Run badge。
- 折叠不能隐藏 Waiting Permission、UNKNOWN_OUTCOME 或 active Run 的存在。
- Character-style portrait 可以在非执行状态更突出，但 active safety state 出现时 header 必须自动收敛，为 system status 让出空间。

### 13.4 Page actions

Conversation menu 可以包含：

- Rename Conversation。
- Open Agent Detail。
- Workspace context detail。
- Archive Conversation，符合生命周期规则时。
- Conversation info / audit links。

Cancel Run、Close Task 和 Erase 不放在同一个含义模糊的 `Delete / Stop` action 下。

### 13.5 Scroll stability

- Task Card projection 更新时保持其 Timeline anchor，不把用户强制拉回卡片。
- 用户不在底部时，新 Message 或 Task event 使用 `New activity` affordance。
- 用户正在查看 Live Output 且已离开底部时，停止自动跟随并显示新增行计数。
- Permission 或 RUN_QUESTION 到达时可以显示 sticky attention prompt，但不突然改变用户的滚动位置。

---

## 14. Agent Header and Workspace Context

### 14.1 Agent Header

必须显示：

- Agent avatar。
- Agent name。
- Conversation title 或打开 switcher 的入口。

可按空间显示：

- capability indicator。
- active state badge。
- compact Persona descriptor。

Model name 不作为 header 主身份。

### 14.2 Three workspace layers in UX

| Layer | 出现位置 | 用户含义 |
|---|---|---|
| Conversation Workspace | Header context rail | 本 Conversation 的默认和讨论上下文 |
| Task Workspace | Task Card header | 这项 Task 目标所指向的 Workspace |
| Run Snapshot / frozen SSH target | Permission Review、Run Detail、ToolCall Detail | 这次实际执行使用的不可变事实 |

UI 不能从 Conversation chip 推断 Permission 页面 target，也不能用当前 SSHHost record 替换 Run / Envelope 的冻结事实。

### 14.3 Conversation Workspace chip

Workspace 存在时显示：

- Workspace name。
- environment：Development / Staging / Production。
- Host label 的简短摘要。
- locked indicator，当首个 Task 后不允许原地切换。

Production 必须有文字标签，不能只靠红色。

Workspace 不存在时：

- 普通聊天只显示低强调的 `No workspace` 或完全收起到 context menu。
- 用户表达真实 SSH intent 时，必须出现 Workspace selection step；不能默认猜测 Host。

### 14.4 Workspace Context Sheet

点击 chip 打开 Bottom Sheet，显示：

- Workspace name 和 environment。
- primary Host label。
- default username 和 working directory，如有。
- context description。
- 当前 Conversation 是否已锁定该 context。

如果已经有 Task，选择其他 Workspace 的 action 是 `Start new conversation with this workspace`。

### 14.5 Frozen execution target

Task Card 可以显示 Workspace 和 Host label，但只有以下 surface 承担精确执行 target：

- Permission Review。
- ToolCall Detail。
- Run Detail / Audit。
- Reconciliation detail。

这些 surface 必须使用 Run Snapshot / Execution Envelope 的 frozen values，并明确标记 `Frozen for this run / approval`。

如果当前 SSHHost 已变化：

- 历史 Run 继续显示冻结值。
- 尚未发送的旧 Envelope 显示 `Stale — host configuration changed`。
- Approve / Execute action 被移除或禁用。
- 用户必须查看新的 Envelope；不能只刷新显示名后继续执行旧请求。

---

## 15. Conversation Timeline

### 15.1 Ordering

Timeline 依据稳定 ordinal 展示 Message 和 Task entry。Runtime event 不直接占用一级 ordinal。

典型顺序：

~~~text
USER_INPUT Message
Task Card anchored to the originating goal
optional ordinary Messages while Run continues
RUN_QUESTION Message, if needed
user answer
RUN_RESULT Message linked to Task / Run
~~~

### 15.2 Stable Task Card

- Task Card 位于 Task 创建时的稳定位置。
- 状态变化更新 card projection，而不是不断追加新 system message。
- 用户滚动离开后，通过 Active Run Strip 或 attention prompt 返回。
- 多次 Run 时默认展示 latest Run，历史 attempts 下钻查看。

### 15.3 Timeline grouping

- 连续普通 Messages 可以按 sender 和时间做轻量视觉分组。
- Task Card 始终打断 Message grouping，保持结构边界。
- RUN_RESULT 虽然是 Assistant Message，但显示 `Result for <Task>` 关联标签并可跳回 Task Card。
- RUN_QUESTION 显示 `Input needed for <Task>` 关联标签。

### 15.4 Timestamps and metadata

- 默认只显示对理解顺序有帮助的时间。
- 完整 created / started / ended / evaluated timestamps 进入 detail。
- Message 不默认展示 model name 或 invocation count。
- Runtime card 可以显示 attempt number、duration 和 last update。

### 15.5 Content trust

- 普通 Markdown 只能产生内容格式，不能产生 Runtime card 样式。
- Agent 回复中的伪造 `[Approve]`、状态 badge 或命令框没有系统 action。
- Tool output 不能突破自己的 scroll / clipping container 覆盖页面控件。
- 外部内容中的 sticky、absolute、动画或终端控制语义一律不执行。

---

## 16. Message UX

### 16.1 Message types

| source | UX purpose | Runtime authority |
|---|---|---|
| USER_INPUT | 用户真实表达 | 不是 PermissionDecision |
| NORMAL_RESPONSE | Agent 普通回复 | 不能声明系统事实为真 |
| PERSONA_GREETING | Conversation 开场 | 不能授予 Capability |
| RUN_QUESTION | Agent 为 Run 请求语言输入 | 回答作为 append-only runtime input 关联 Run |
| RUN_RESULT | 把 Run 结果带回 Conversation | 事实状态必须来自 Run / Verification |

### 16.2 User Message

- 使用清晰但不过度装饰的用户消息容器。
- 提交后不支持原地编辑。
- 可以复制文本；删除单条 Message 不是 MVP。
- 用户文本中的 `approve`、`run this` 或代码块不能绕过 structured Tool Proposal 和 Permission。

### 16.3 Assistant Message

- 以 Agent avatar / name 表明发言主体。
- 比传统左右双气泡更接近 full-width readable prose，使 Agent identity 和长解释更自然。
- Persona styling 可以影响语气、avatar 和非安全 accent。
- Provider retry、stream chunk 和 internal reasoning 不显示为额外 Message。

### 16.4 Streaming

- STREAMING Message 可以原位增长。
- Stop response 只取消当前普通 ModelInvocation，不等于 Cancel Run；若 Message 属于 Run interaction，必须明确显示作用范围。
- FAILED 显示可理解错误和 `Try again`，表示恢复同一次普通响应，不实现候选 Swipe / Regenerate 体验。
- 用户离开页面再返回时展示持久化 Message 状态，不重放打字动画。

### 16.5 RUN_QUESTION

RUN_QUESTION 仍是 Assistant Message，但附带 system-owned `Input needed` marker：

- 明确关联 Task / Run。
- 显示问题正文和为什么需要回答。
- 需要安全输入时不使用普通 Composer，而是进入 Credential / Host Key 等专用安全 surface。
- Composer 自动附着 `Replying to Run #…` context；用户可显式切回普通 Conversation message。
- 用户答案在 Timeline 中只显示一次，同时被 Runtime 作为 append-only input 引用；不制造重复的“系统答案消息”。

### 16.6 Message actions

普通 Message actions 可以包括：

- Copy。
- Select text。
- 查看关联 Task / Run，如存在。
- 对失败发送执行 retry。

不包括：

- 通过长按 Agent Message 授予 Permission。
- 把普通代码块直接转成执行。
- 修改已提交历史以改变 Run context。

---

## 17. Task Card

### 17.1 Role

Task Card 是 Conversation 中真实 Agent work 的稳定 cockpit。它不是 Task Manager row，也不是一个超大的聊天消息。

### 17.2 Anatomy

~~~text
Task Card
├── Task title and lifecycle
├── Workspace / environment
├── Latest Run state and attempt
├── One-line factual status
├── Current Plan step / progress
├── Latest ToolCall or observation summary
├── Blocking action, conditional
├── Verification summary, conditional
└── Open Run Detail
~~~

### 17.3 Header

默认显示：

- Task title。
- Task status：Open / Resolved / Closed。
- latest Run state。
- Workspace name 和 Production 等 environment label。
- attempt number，当存在多个 Run。

Task lifecycle 与 Run state 必须分开。例如：

~~~text
Task: Open
Run #1: Canceled
Remote state: Unknown
~~~

不能只显示一个含义模糊的 `Canceled`。

### 17.4 Summary states

| Run / Task condition | 默认展开 | 主要摘要 | Primary action |
|---|---|---|---|
| OPEN，无 Run | 是 | 还缺 Workspace / capability 或尚未启动 | Complete setup / Start |
| CREATED / PLANNING | 是 | 正在准备或规划 | Open details / Cancel Run |
| RUNNING | 是 | current step + latest event | View run |
| WAITING_PERMISSION | 是 | 需要审阅精确操作 | Review operation |
| WAITING_USER | 是 | Agent 需要回答 / 凭证 / 选择 | Answer / Resolve issue |
| RECONCILING | 是 | 正在建立远端真实状态 | Review uncertainty |
| PAUSED | 是 | 暂停原因 | Resume / Resolve issue |
| VERIFYING | 是 | 当前 check + criterion progress | View verification |
| COMPLETED + RESOLVED | 否 | Completed — Verified | View result |
| FAILED | 是 | 失败阶段和可恢复性 | Review / Retry when allowed |
| CANCELLED，无未知 | 否 | Run canceled；Task remains Open | Retry / Close Task |
| CANCELLED + obligation | 是 | Remote state unknown | Reconcile |

### 17.5 Factual summary language

Task Card 的 system summary 来自 structured state，不允许直接使用模型自由文本替代。例如：

- `Checking Nginx service state` 可以来自 current Plan step。
- `Command exited with code 0` 是 ToolCall fact。
- `Service restored` 只有对应 REQUIRED Verification PASS 后才能使用。
- `Agent thinks it is fixed` 不能成为 verified system label。

### 17.6 Multiple Runs

- 默认展示 latest Run。
- Header 显示 `Attempt 2` 等明确标识。
- `Run history` 打开 attempt list。
- COMPLETED、FAILED、CANCELLED Run 的历史事实不被新 Run 改写。
- source Run 已 CANCELLED 但 unresolved obligation 存在时，历史 attempt 和当前 reconciliation relation 都必须可见。

### 17.7 Card expansion

Inline expanded state优先包含：

1. 当前 Plan step。
2. blocking action。
3. latest ToolCall / observation。
4. Verification summary。

完整日志、所有 Envelope 字段、所有 Plan version 和 Evidence 进入详情页，避免一个 Task Card 无限增长。

### 17.8 Task actions

- Open Run Detail。
- Review Permission / answer question / resume，根据状态只显示适用 action。
- Cancel Run，只针对 active Run。
- Retry，创建新 Run，不恢复终态 Run。
- Close Task，只在没有 active Run 时。

如果 unresolved side-effect obligation 存在：

- Retry action 的真实含义是先进入 reconciliation precondition。
- Close Task 不清除 warning。
- Erase source history 不可用，直到满足 Baseline 的安全保留规则。

---

## 18. Active Run

### 18.1 Active Run Strip

Active Run Strip 是轻量持续控制面，避免用户必须滚回旧 Task Card。

显示：

- Agent identity，跨页面时必须有。
- Task title。
- Run state 用户标签。
- current step 或 blocking reason。
- Workspace environment，如相关。
- elapsed time。
- attention badge。

点击打开 Task Card / Run Detail。

### 18.2 Progress semantics

- PLANNING 使用不确定进度，不显示虚假百分比。
- 有稳定 current Plan version 时可以显示 `Step 3 of 6`，同时标明 Plan 可能更新。
- Plan version 变化后重新计算 step count，不保留误导性的旧百分比。
- VERIFYING 使用 `3 of 5 required checks passed`，不把 ADVISORY 混入 required denominator。

### 18.3 Active controls

始终可达：

- View run。
- Cancel Run。

按状态可达：

- Guide this Run。
- Review operation。
- Answer question。
- Resume。

不存在：

- Pause-as-guaranteed-background-control，除非 Runtime 当前真的支持对应状态转换。
- 隐式 `Stop all`。
- 从 notification 直接 Approve。

### 18.4 Cancel Run UX

Cancel confirmation 必须根据 delivery state 表达：

- 尚未执行 mutating ToolCall：说明停止后续步骤。
- 正在执行或可能已送达：警告取消不能撤销效果，结果可能变成 unknown。
- 已处于 RECONCILING：说明取消只停止 reconciliation，历史不确定性仍保留。

确认文案必须说 `Cancel Run`，不能使用会被理解为取消整个 Task 的标签。

Cancel 完成后：

- Run 显示 CANCELLED。
- Task 默认仍 OPEN。
- 如果有 obligation，Attention item 继续存在。
- 提供 Retry 或 Close Task 时明确它们的不同语义。

### 18.5 Background and re-entry

- 离开 App 前后的 Run 状态以 Runtime facts 为准。
- 返回后不假设仍在运行；先显示恢复后的 RUNNING、PAUSED 或 RECONCILING。
- active strip 的 last update time 在长时间无新 event 时可见。
- notification deep-link 回到当前 Run，不展示独立且可能过期的 action state。

---

## 19. Plan

### 19.1 Purpose

Plan 帮用户理解 Agent 的方向和当前步骤，但不是预先批准未来所有操作的授权清单。

### 19.2 Inline Plan

默认显示：

- Plan version 的简短标记。
- 已完成步骤数量。
- current step 的标题和状态。
- 下一到两个步骤。
- blocked step 的原因。

已完成的早期步骤折叠为摘要。

### 19.3 Presentation states

UI projection 可以使用：

- Upcoming。
- Current。
- Completed。
- Blocked。
- Skipped / superseded。

这些是 PlanStep facts 的展示，不改变 Run state。`Completed step` 不等于 `Task completed`。

### 19.4 Plan revision

- Agent 重新规划时显示 `Plan updated` 和新 version。
- 默认展示当前 version。
- 用户可以进入 Plan history 查看改变原因和差异。
- 已用于批准修改的 Success Criteria / Verification Plan 若被实质削弱，显示 blocking warning，不能只把旧步骤从 UI 中消失。

### 19.5 Plan and permission

- Plan step 只能说明意图。
- Permission Review 必须显示具体 immutable Envelope。
- “计划执行 restart”不等于用户已经批准未来任何 restart command。
- Plan 中的 rollback 是说明；执行 rollback 仍会产生新的 ToolCall 和 Permission flow。

---

## 20. ToolCall and Live Output

### 20.1 ToolCall row

每个实际操作显示：

- Skill / operation 的用户语言名称。
- ToolCall status。
- Execution risk。
- Workspace / frozen target 摘要。
- startedAt / duration。
- exit code、signal、timeout、cancelled 或 certainty。
- output sensitivity / egress indicator。

### 20.2 ToolCall user labels

| ToolCall state | 用户标签 |
|---|---|
| PROPOSED | Proposed operation |
| AWAITING_PERMISSION | Approval required |
| APPROVED | Approved — not yet sent |
| EXECUTING | Running on host |
| SUCCEEDED | Command finished |
| FAILED | Command failed |
| CANCELLED | Command canceled |
| UNKNOWN_OUTCOME | Remote outcome unknown |

`SUCCEEDED` 只能表示 ToolCall 结束事实，不能在视觉上等同 Task 的 `Completed — Verified`。

### 20.3 Command visibility

- READ ALLOW 操作也必须在 Task / Run 中可见和可审计。
- 执行前无需用户审批的 Safe Read 可以在 row 中显示 compact exact command preview。
- MODIFY / DESTRUCTIVE 的完整命令通过 Permission Review 展示。
- 执行后的 ToolCall Detail 继续使用 executed Envelope 的 exact bytes，不用模型摘要替换。

### 20.4 Live Output inline

Inline preview：

- 明确区分 stdout / stderr。
- 只显示最近少量逻辑行和 truncation marker。
- 显示 Follow 状态；用户向上滚动后停止自动跟随。
- 不能输入 shell command；它不是 terminal。
- ANSI escape 不执行，控制字符不改变页面布局。
- output 中看似按钮、提示或 Permission 文案的内容保持纯文本。

### 20.5 Full Output Page

提供：

- stdout / stderr filter。
- 时间 / sequence。
- search within locally available output。
- head / tail 和 truncation facts。
- sensitivity classification。
- Provider egress state：not sent、redacted、summarized、blocked 或 sent-safe-view。
- 关联 ToolCall、Envelope Digest 和 frozen target 的入口。

它不提供交互式 stdin。

### 20.6 Sensitive local output

| Sensitivity | Inline default | Detail behavior |
|---|---|---|
| NORMAL | 可预览 | 正常查看 |
| SENSITIVE | 脱敏摘要或折叠 warning | 用户主动 reveal locally；明确不等于发送 Provider |
| SECRET | 不显示 raw preview | 专用本地 reveal flow，可要求设备解锁；Provider raw egress 默认 BLOCK |

“用户批准执行命令”与“用户允许 observation egress”在 UI 中必须是两个不同决定。

### 20.7 Output injection resistance

- 不解析 ANSI 颜色、cursor move、clear screen、OSC hyperlink 或 title change。
- 对 BiDi / zero-width / control content 使用可见化或安全替换。
- 超长无换行内容不能覆盖系统 action；保持在独立 scroll container。
- Prompt injection 文本不使用 system badge。
- 从 output 复制内容时提示其来源，不提供一键执行。

---

## 21. Permission and Egress Decision UX

### 21.1 Trust rule

> 用户批准的必须是 Runtime 当前仍认为有效的同一个 immutable Execution Envelope，而不是模型对操作的描述，也不是经过 UI 美化或重新拼装的命令。

Approved Baseline 所称的 Permission Card 在本规范中是一个跨两种 presentation state 的受信任组件，而不是只指 Timeline 中的折叠矩形：

1. Compact Permission Prompt：位于 Timeline / Task Card，说明有决定待处理，以 `Review operation` 为主 action；可以安全地提供结构化 `Reject`，但绝不直接 Approve。
2. Expanded Permission Card / full-screen Permission Review Page：展示 Baseline 要求的完整精确命令、安全上下文、rollback 和 Verification Plan，并产生 Approve Once / Reject / Cancel Run。

Compact prompt 不能替代 Baseline 要求的完整 Permission Card。普通 MODIFY / DESTRUCTIVE 操作不得只凭 compact preview 直接 Approve；作出任何批准前必须进入 expanded state 并显示完整 exact command。

### 21.2 Permission Card

默认显示：

- `Approval required` system marker。
- Runtime 判定的 risk 和 reason。
- operation 的短名称。
- Workspace、Host label、username。
- Agent explanation 的一行目的摘要，明确标记来源。
- `Review operation` 主 CTA。
- `Reject` 次要 action；可以直接产生结构化拒绝，并可随后补充可选原因。

Card 不显示：

- Persona 自定义按钮。
- `Always allow`。
- 批量批准未来命令。
- 根据模型文本生成的漂亮版 command。

### 21.3 Permission Review Page anatomy

~~~text
Permission Review
├── System risk banner
├── Frozen execution target
├── Exact command byte view
├── Execution parameters
├── Agent explanation, labeled
├── Expected effect and blast radius
├── Rollback explanation, not automatic
├── Versioned verification plan
├── Technical identity / digest
└── Approve Once | Reject | Cancel Run
~~~

### 21.4 Frozen execution target block

默认展开并显示：

- Agent。
- Conversation 和 Task。
- Workspace name、environment 和 frozen Workspace revision。
- SSH Host label。
- `sshHostId` 和 `sshHostRevision`，可放 technical label 但必须可见。
- configured hostname。
- frozen resolved address。
- port。
- username。
- pinned Host Key fingerprint。
- working directory。
- `Frozen for this approval` 标记。

hostname 与 resolved address 不同或 Host 为 Production 时，两者都保持显著，不能只显示友好 Host name。

Workspace / Host friendly label 与 frozen semantic fields 使用不同标签。hostname、resolvedAddress、username、workingDirectory、environment 和 fingerprint 等安全字段同样禁止解释 ANSI / control / BiDi；遇到异常字符时使用与 exact command 一致的可见化原则，不能只保护 command body。

### 21.5 Exact command source

命令区域必须：

- 只从 immutable Envelope 的 `exactCommandBytes` 生成。
- 与 PermissionRequest、PermissionDecision 和最终 SSH execution 使用同一 Envelope Digest。
- 不 trim、不合并空白、不格式化 shell、不补全变量、不替换引号。
- 不把 Agent explanation 中的代码块当作执行命令。
- 不把结构化 operation 名称当作 exact command 的替代品。

可以另行显示 `Agent explanation`，但必须与 `Exact command` 使用不同标题和视觉容器。

### 21.6 Byte-faithful safe rendering

Exact command viewer 采用本地、确定性、可逆识别的 Safe Escaped Byte View。

规则：

1. 安全可打印字符按实际字符显示。
2. 字面 backslash 与 escape token 使用不同视觉语法，避免把两个字节 `\` + `n` 误认成 newline。
3. LF、CR、TAB、ESC、NUL、DEL 和其他 control byte 显示为不可伪造的系统 token，例如 `⟦LF 0A⟧`，并给 screen reader 提供完整名称。
4. 实际 newline 在 token 后产生明确的新视觉行；字面 `\n` 不产生换行。
5. ANSI sequence 不执行。ESC 显示为 token，后续 bytes 按字面显示。
6. 有效 UTF-8 中的 BiDi control、zero-width、line / paragraph separator 和其他视觉欺骗 code point 显示为 `⟦U+XXXX NAME⟧` token。
7. 无效 UTF-8 byte 使用 `⟦0xNN⟧`。
8. UI 不做 Unicode normalization；显示 encoding status 和总 byte count。
9. ASCII space 保留实际 byte 数量并占据可辨识 cell；leading、trailing 和连续多个 space 使用非内容性的 whitespace marker，用户可以切换显示全部 whitespace。
10. 有效的非 ASCII printable code point 可以显示原字符，但必须可点选查看 code point / UTF-8 bytes；mixed-script 或已知 confusable pattern 触发 warning。
11. viewer 显示明确的 end-of-command marker；trailing space、trailing newline 和 NUL 后缀不能在视觉上消失。
12. 默认关闭 soft wrap，使用横向滚动；用户开启 wrap 时，每个视觉折行显示非内容性的 wrap marker。
13. 长命令可以按 viewport 渲染，但所有 bytes 必须可达，不允许以省略号替代未展示内容后直接宣称“完整命令”。

示例语义：

| Exact bytes | 安全显示含义 |
|---|---|
| `5C 6E` | 字面 `\n`，两个可打印字符 |
| `0A` | `⟦LF 0A⟧` 后换到新视觉行 |
| `1B 5B 33 31 6D` | `⟦ESC 1B⟧[31m`，不变红 |
| UTF-8 BiDi override | `⟦U+202E RIGHT-TO-LEFT OVERRIDE⟧` |

命令 surface 固定使用 LTR isolation；App 语言为 RTL 时也不能让 byte 顺序在视觉上重排。

### 21.7 Visual deception warnings

以下情况在 command viewer 上方显示 system warning：

- control byte 或 ANSI escape。
- BiDi / zero-width / unusual separator。
- mixed-script / confusable Unicode。
- invalid UTF-8。
- leading / trailing whitespace、trailing newline、异常多行、超长单行或不可见字符占比异常。
- shellMode、environment 或 stdinMode 使用户仅看命令正文可能误解语义。

Warning 不替代风险判定，也不能降低 Permission Engine 结果。

### 21.8 Execution parameters

必须在批准前可见：

- skillId / skillVersion 和 operation。
- shellMode。
- workingDirectory。
- environment 的非秘密 exact entries，并对 key / value 应用安全字符可见化；为空时显示 `No environment overrides`。
- stdinMode；v0.1 正常显示 `NONE`。
- timeout。
- targetDigest 和 Envelope Digest，允许以短 fingerprint 起始并可展开完整值。
- 引用的 Success Criteria / Verification Plan version。

任何参数非默认或具有行为影响时，不得折叠在模糊的 `More` 之后而无提示。

### 21.9 Explanation and factual labels

| 内容 | 标题 | 权威来源 |
|---|---|---|
| Purpose | Agent explanation | 模型建议，不是执行事实 |
| Risk | Runtime risk assessment | Permission Engine |
| Target | Frozen execution target | Execution Envelope |
| Expected effect | Agent expectation | 计划信息 |
| Rollback | Rollback explanation | 未来新操作，不会自动发生 |
| Verification | Verification plan vN | versioned plan |

视觉上不能把 Agent expectation 写成系统保证。

### 21.10 Actions

`Approve Once`：

- 只绑定当前 Envelope Digest。
- 可以要求设备解锁或生物识别，但具体机制由后续 System Architecture 确定。
- action 文案包含一次性语义。
- 执行前仍受 Current Runtime Mandatory Safety Floor 复核。

`Reject`：

- 产生结构化 PermissionDecision。
- 可选添加原因供 Agent 重规划；原因是 runtime input，不是新的批准。
- 不循环展示同一个 request。

`Cancel Run`：

- 与 Reject 分开。
- 显示取消可能无法撤销远端效果。

输入 `yes`、`approve` 或任何自然语言不产生 PermissionDecision。

### 21.11 Risk-specific behavior

| Risk | Permission UX |
|---|---|
| READ + Safe Read ALLOW | 不阻塞，但 ToolCall 可见和可审计 |
| READ but not safely provable | ASK 或 DENY；ASK 使用完整 Review Page |
| LOW automatic policy match | 可按 Runtime 结果执行，仍显示 facts |
| MODIFY | Full Review + Approve Once |
| DESTRUCTIVE | 强 warning、后果摘要、清晰二次确认语义 |
| CRITICAL | 不显示可批准 action，只显示 Runtime DENY 和原因 |

不提供 `Always allow this command / Task / Agent`。

### 21.12 Stale or invalid permission

Permission Review 打开期间若发生以下情况：

- Envelope Digest 变化。
- frozen target validator 失败。
- SSHHost target-defining fields 变化使 Envelope 失效。
- Permission Request 过期。
- Current Runtime Safety Floor 收紧为 ASK / DENY 或 validator invalid。
- 引用的关键 Verification Plan 被实质改变。

则页面立即进入 `Stale / blocked` state：

- Approve action 消失或禁用。
- 已展示的历史 Envelope 保留供比较。
- 解释为什么必须重新审阅。
- 新请求必须作为新的 PermissionRequest 显示，不能把 button 悄悄绑定到新 Digest。

### 21.13 Post-decision visibility

Task / Run Detail 显示：

- 决定：Approved Once / Rejected / Expired / Invalidated。
- 决定时间。
- 关联 Envelope fingerprint。
- 谁作出用户决定；单用户 MVP 可以显示 `You`。
- 实际 executed Envelope Digest 是否匹配。

UI 不允许修改历史 PermissionDecision。

### 21.14 Egress Decision Card

Observation egress 使用独立 system card，标题不得使用 `Approve command`：

~~~text
Send diagnostic data to <Provider>?
Data class: SENSITIVE
Local source: <ToolCall / log>
Proposed view: redacted / summarized
~~~

必须显示：

- Provider identity。
- sensitivity。
- 将发送的是 raw、redacted 还是 summarized view。
- 被移除的数据类别。
- preview，仅来自 Provider-safe candidate view。

Actions：

- `Send safe view`，策略允许时。
- `Do not send`。
- `Cancel Run`，单独 action。

SECRET raw output 没有普通用户 override action。执行 Permission 与 Egress decision 永远不能合并成一个 checkbox。

---

## 22. Verification UX

### 22.1 Trust objective

Verification surface 必须让用户理解：

> “Agent 执行了命令”与“目标已经被可靠验证”是两个不同事实。

### 22.2 Task Card summary

显示：

- 当前 phase：Verifying / Verified / Failed / Inconclusive。
- REQUIRED criteria progress。
- current check。
- FAIL / INCONCLUSIVE 的首要原因。
- `View evidence`。

`exitCode 0` 不能产生绿色 verified badge。

### 22.3 Criterion row

每个 criterion 显示：

- description。
- REQUIRED / ADVISORY。
- expected condition。
- actual value / structured observation。
- evaluator type。
- PASS / FAIL / INCONCLUSIVE。
- evidence timestamp 和 source。

默认展开当前、失败和不确定项；已通过项折叠为计数，用户可展开。

### 22.4 Evaluator trust labels

| Evaluator | 用户标签 | PASS treatment |
|---|---|---|
| DETERMINISTIC | Deterministic check | 可形成高可信 PASS |
| STRUCTURED_RULE | Structured rule | 显示 rule version |
| MODEL_ASSISTED | Agent interpretation | 单独不能形成安全关键高可信 PASS |
| USER_CONFIRMED | Confirmed by you | 显示用户看到的 evidence scope |

不能用同一个 check icon 隐藏 evaluator 差异。

### 22.5 Verification Plan versioning

- 显示当前 Verification Plan version。
- 已被 PermissionRequest 引用的关键版本显示 `Used for approval`。
- 增强检查时显示新增项。
- 删除 REQUIRED、降级 ADVISORY、降低 expected condition 或换成更弱 check 时显示 blocking `Success criteria changed`。
- 需要用户确认时进入 WAITING_USER；不能用折叠或重排把旧标准从视图中消失。

### 22.6 Evidence Detail

Full Page 显示：

- criterion / check / evidence / evaluator / verdict chain。
- immutable evidence reference 和 digest。
- local raw evidence 的受控入口。
- Provider-safe view 与 raw local view 的区别。
- check target 和执行时间。

SENSITIVE / SECRET Evidence 使用与 Live Output 相同的本地 reveal 和 egress 边界。

### 22.7 Overall outcome labels

只使用以下明确语义：

- `Completed — Verified`：所有 REQUIRED criteria 获得可信 PASS。
- `Verification failed`：至少一个 REQUIRED criterion FAIL，且 Run 进入相应处理。
- `Verification inconclusive`：必要结果无法建立。
- `Verifying`：仍在检查。
- `Remote state unknown`：必须先 reconciliation，不能跳入普通成功状态。

---

## 23. RUN_RESULT

### 23.1 Role

RUN_RESULT 是把结构化 Run 结果重新带回自然 Conversation 的 Assistant Message。它不替代 Task Card、FinalReport 或 Evidence。

### 23.2 Content structure

RUN_RESULT 包含：

1. Outcome headline。
2. 根因或当前最可信解释。
3. 实际修改摘要。
4. Verification summary。
5. 未解决问题或风险。
6. `View Task / Run details` link。

### 23.3 Outcome integrity

- `Fixed`、`Resolved` 或 equivalent success wording 只有在 Task RESOLVED / Run COMPLETED 后使用。
- FAILED、CANCELLED、INCONCLUSIVE 和 UNKNOWN 必须如实出现。
- UNKNOWN_OUTCOME 后若仍未解决，Message 明确写“远端操作是否生效仍未知”。
- CONFIRMED_EXECUTED 后先展示 Verification 结果，不能把“确认执行过”写成“确认成功”。

### 23.4 Persona and system facts

RUN_RESULT 的叙述语气可以符合 Persona，但 Outcome badge、Verification summary 和 unresolved warning 属于固定 System Trust Layer。

未来 Character-style Agent 可以用角色语气解释结果，但不能把失败说成成功、隐藏 target 或改写风险。

### 23.5 Relationship to Task Card

- RUN_RESULT 显示 `Result for <Task title>`。
- 点击关联跳到 Task Card 或 Run Detail。
- Task Card 保留完整结构；RUN_RESULT 保持短而可读。
- 一个 Run 不因多个内部 ModelInvocation 产生多个 RUN_RESULT。

---

## 24. Composer

### 24.1 Core rule

> Composer 始终是语言输入入口；它不是 Permission 按钮、shell prompt 或隐式 Run controller。

### 24.2 Normal state

- Placeholder：`Message <Agent name>`。
- 发送产生 USER_INPUT Message。
- Workspace chip 在 context rail 中处理，不把 Host 写进 placeholder。
- 普通文本不会直接执行 code block。

### 24.3 Run-aware context

默认 Composer 仍发送普通 Conversation Message。用户只有通过以下明确入口才把语言输入关联 active Run：

- Task Card → `Guide this Run`。
- Active Run Strip → `Add guidance`。
- RUN_QUESTION 自动建立 `Replying to Run #…` context。

关联后，Composer 上方显示可移除的 context chip：

~~~text
Regarding: Run #2 · Fix Nginx 502
~~~

该输入在 Timeline 中显示一次 USER_INPUT Message，并通过 related Run reference 被记录为 append-only runtime interaction，在安全的 Agent loop boundary 被消费；它不修改 Run Snapshot，也不产生重复 system message。

active Run 期间发送的普通 Message 不进入该 Run Working Context，除非用户显式附着 Run context。Agent 的普通回复可以等待安全的 model boundary；UI 应显示“Message saved / Agent will reply”之类的非执行状态，而不能假装该消息已经影响当前步骤。

### 24.4 Guidance semantics

- 正在执行的 Envelope 不会因新文字被改写。
- guidance 在当前 ToolCall 安全结束或进入未知状态后处理。
- 如果 guidance 只补充事实或偏好，可以触发 re-plan。
- 如果它实质改变 Workspace、target、Skill、Task goal 或冻结安全配置，UI 必须解释原 Run 不可原地改变，并提供新 Task / Run / Conversation 的正确路径。
- guidance 不能批准 Permission，也不能解除 DENY。

### 24.5 Composer state matrix

| Current state | Default composer | Special affordance | Text cannot do |
|---|---|---|---|
| No active Run | Normal Message | none | 直接执行 SSH |
| PLANNING / RUNNING | Normal Message | Guide this Run | 改写 executing Envelope |
| EXECUTING ToolCall | Normal Message | Queue guidance for safe boundary | 中断或修改已发送命令 |
| WAITING_PERMISSION | Ask about operation / normal Message | Review operation | Approve / Reject |
| WAITING_USER + RUN_QUESTION | Reply to Run context | Switch to normal Message | 伪造 Credential / Permission |
| Credential required | 普通输入不收秘密 | Open secure credential UI | 把 secret 写入 Message |
| RECONCILING | Normal Message / answer | Review uncertainty | 请求冲突 mutation 绕过 obligation |
| PAUSED | Normal Message | Resume / resolve issue | 假装 Runtime 已恢复 |
| VERIFYING | Normal Message | Add evidence / guidance | 静默降低 criteria |
| COMPLETED / FAILED / CANCELLED | Normal Message | Retry / Close via Task Card | 恢复终态 Run |

### 24.6 Waiting Permission

- Composer 保持可用，用户可以询问“为什么需要这个操作”。
- 页面在 Composer 上方固定 `Approval required` prompt。
- Agent 的解释不会自动更新 PermissionRequest；Envelope 变化必须新请求。
- 用户输入“不要执行”可以让 Agent提出 Reject 建议，但正式拒绝仍通过结构化 action，避免歧义；UI 可以在明确意图后引导到 Reject。

### 24.7 Waiting User

- 当前 RUN_QUESTION 在 Composer 上方保持可见。
- Text answer 关联 source Run。
- 多段回答作为一次用户提交或明确的多个 runtime inputs，不能被 UI 静默合并重写。
- 需要选择项时使用 chips / sheet，选择结果仍作为结构化 runtime input。
- 需要 Credential 时离开普通 Message flow。

### 24.8 Draft and navigation

- 切换页面不丢失当前 Conversation draft。
- Run guidance draft 与普通 Message draft 必须按 context 区分，避免回来后误发到错误目标。
- 切换 Conversation 不把 draft 带到另一个 Conversation。
- Back 不发送、不批准、不取消。

### 24.9 One active Run constraint

当另一个 Conversation 已有 active Run，新的执行 intent：

- 可以先保存为 Message。
- 明确告知 `Another Run is active`。
- 提供 `Return to active Run`、`Discuss without executing`，或创建 OPEN Task 等待用户稍后显式开始。
- 不自动 Cancel 当前 Run，不自动排队执行第二个 Run。

---

## 25. Waiting States and Needs Attention

### 25.1 Needs Attention is a projection

Needs Attention 不是新 Domain entity 或 Task status。它从 Run、Permission、Credential、Egress、Verification 和 unresolved obligation 的当前事实计算。

### 25.2 Priority

| Priority | Condition | 用户含义 |
|---|---|---|
| P0 Safety uncertainty | UNKNOWN_OUTCOME、unresolved side-effect obligation、RECONCILING blocked | 远端真实状态尚未建立 |
| P1 Explicit decision | WAITING_PERMISSION、Egress ASK、Host Key confirmation | 需要安全决定 |
| P2 Required input | RUN_QUESTION、Workspace、Credential、方案选择 | Agent 缺少用户输入 |
| P3 Recoverable interruption | PAUSED、Provider / network / auth issue | 处理原因后可恢复 |
| P4 Outcome attention | Verification FAIL / INCONCLUSIVE、Run FAILED | 查看结果并决定下一步 |

P0 在任何生命周期状态下保留，不能因为 source Run CANCELLED、Task CLOSED 或 Conversation ARCHIVED 而被降为普通历史。

### 25.3 Attention card anatomy

- Agent identity。
- 状态 icon + 文字。
- Conversation / Task title。
- Workspace / Host，如相关。
- 一句 factual reason。
- waiting since / last update。
- 单一 primary action。
- 可选 secondary action 进入详情。

### 25.4 State-specific CTA

| Condition | Primary CTA |
|---|---|
| Waiting Permission | Review operation |
| Egress ASK | Review data sharing |
| RUN_QUESTION | Answer Agent |
| Credential issue | Update / unlock credential |
| Host Key issue | Review Host Key |
| RECONCILING | View reconciliation |
| Unresolved after Cancel | Establish remote state |
| PAUSED | Resume / resolve blocker |
| Verification INCONCLUSIVE | Review evidence |
| FAILED | Review failure |

### 25.5 Deduplication

- 同一 Task / Run 同一时刻在 Home 只出现最高优先的 attention item。
- 同一 active Run 的 attention 已由 Home global active card 承载时，不在 Needs Attention section 重复。
- Activity 可以展示其完整事件和其他 blockers。
- Permission 已被 Runtime 新规则 invalidated 时，旧 request 不继续显示为可批准 attention。
- 一个 unresolved obligation 可以关联 source canceled Run 和当前 reconciliation Run，但 Home 只显示一个明确 item。

### 25.6 Resolution

Attention item 只在其来源事实真正解决后消失：

- PermissionDecision 已作出或 request invalidated。
- RUN_QUESTION 已回答且 Runtime 接收。
- Credential / Host Key 问题已处理。
- Reconciliation 建立 CONFIRMED_EXECUTED 或 CONFIRMED_NOT_EXECUTED。
- Verification / failure 进入不再要求用户行动的终态。

打开卡片、关闭页面、Cancel Run、Close Task 或 Archive 不是 resolution。

### 25.7 Notifications

- Notification copy 使用相同状态 vocabulary。
- 只提供安全 deep link 或 Cancel Run 等明确且允许的控制。
- 高风险 Permission 不在 notification 上 Approve。
- Notification 被清除不清除 Needs Attention。

---

## 26. Activity

### 26.1 Purpose

Activity 是执行领域的权威入口：

> 现在和过去发生了哪些真实 Agent actions，它们是否等待处理、是否验证、是否仍存在不确定性？

它不是创建 Agent 或发起普通 Conversation 的主要入口。

### 26.2 Page structure

~~~text
Activity
├── Active
├── Needs Attention
├── Recent outcomes
└── Audit history / filters
~~~

空 section 不占据显著高度。

### 26.3 Activity row

显示：

- Agent avatar / name，作为第一识别信息。
- Task title。
- Conversation title。
- Workspace / Host environment。
- Run attempt 和 state。
- outcome / attention label。
- start / last update / end time。

Task 与 Run 必须分开。例如历史行显示 `Task resolved · Run #2 completed — verified`。

### 26.4 Filters

至少支持概念上的：

- Agent。
- Workspace / environment。
- Active / Needs Attention / Completed / Failed / Canceled / Unknown。
- time range。

风险和 ToolCall 类型可留在 detail filtering，不要求 Home 承担。

### 26.5 Active and Needs Attention

- Active section 最多有一个 Run。
- Needs Attention 按第 25 节 priority 排序。
- unresolved obligation 即使 source Task CLOSED / Run CANCELLED 也继续显示。
- 不提供 batch Approve、swipe Approve 或清空全部 unknown。

### 26.6 History

历史 row 可以展开 outcome summary，完整内容进入 Run Detail：

- Plan versions。
- ToolCalls / Envelopes。
- Permission history。
- outputs / egress decisions。
- Verification evidence。
- FinalReport。
- reconciliation relation。

### 26.7 Cross-navigation

- 点击 Message-related title 回所属 Conversation。
- 点击 status / attempt 进入 Run Detail。
- 点击 Permission / Evidence 进入对应 authoritative page。
- 从历史 detail 返回保持 Activity filter 和滚动位置。

### 26.8 Audit integrity in UX

- 已完成历史不显示可编辑 fields。
- 当前配置与 frozen Snapshot 同时存在时明确标注 `Current` 与 `Used by this run`。
- policy tightening event、target invalidation 和 reconciliation resolution 可在 technical audit 中查看。
- UI 不通过隐藏 canceled attempts 让 retry 看起来像第一次执行。

---

## 27. Server and Character-style Differentiation Without Modes

### 27.1 No Agent type branch

不新增 `SERVER_AGENT` / `CHARACTER_AGENT` Runtime type。Presentation 由以下现有事实推导：

~~~text
Persona assets and expression
+ enabled Skill capabilities
+ Workspace presence
+ current Conversation / Run state
= presentation emphasis
~~~

`presentation emphasis` 是 UI 计算结果，不持久化为授权信息，也不授予 Capability。

### 27.2 Shared foundation

所有 Agent 共用：

- Agent Detail。
- Multiple Conversations。
- Conversation Shell。
- Message source types。
- Composer。
- Timeline ordering。
- Task Card / Run surfaces，在真实 execution 存在时。
- Permission、Egress、Verification 和 Activity。

### 27.3 Conversational emphasis

当 Persona identity 丰富、没有 Workspace 或没有真实 execution 时，UI 可以：

- 展示更大的 portrait / avatar。
- 给予 greeting 和 Agent description 更大空间。
- 使用更宽松的 Message spacing。
- 使用 Persona accent 或非安全背景纹理。
- 降低空 Workspace / capability chrome 的视觉存在。

这支持普通聊天和未来 Character-style conversation，但不交付 Character Card、Lorebook、Swipe 或其他 Tavern 功能。

### 27.4 Operational emphasis

当 Conversation 有 Workspace、active Task 或 execution attention 时，UI 应：

- 压缩 portrait header。
- 提升 Workspace / environment 可见性。
- 显示 Active Run Strip。
- 使用更紧凑的 Task / Plan / ToolCall 信息密度。
- 保持 system risk 和 status 色彩不受 Persona accent 影响。

### 27.5 Blended behavior

- Server-oriented Agent 进行普通聊天时，不显示空 Task Card 或 terminal chrome。
- Future Character-style Agent 如果用户明确配置 SSH Skill 和 Workspace，真实操作使用完全相同的 Task / Permission / Verification UI。
- Persona 可以解释“我准备重启服务”，但 Permission Review 仍使用中性系统语言。
- Character portrait 和对话语气在 Permission full page 中降为辅助 identity，不包裹 exact command。

### 27.6 Visual invariants

| Area | 可被 Persona 影响 | 不可被 Persona 影响 |
|---|---|---|
| Header | avatar、name、accent、portrait prominence | active / risk / Workspace facts |
| Message | 语气、非安全排版、avatar | Message source 和提交事实 |
| Task Card | Agent identity accent 的轻量引用 | state、risk、target、actions |
| Permission | Agent identity 作为上下文 | exact bytes、risk、target、decision controls |
| Verification | 解释语气 | evaluator、evidence、verdict |
| Activity | avatar | audit order 和 outcome facts |

### 27.7 Avoiding clone behavior

以下是必须避免的 UI：

- Conversation header 主要显示 `GPT-5 / Claude / DeepSeek`。
- Home 只是按时间排列的所有 chats。
- Agent 只是模型选择器的别名。
- 工具调用只作为灰色折叠消息，完成后消失。
- Character 通过单独 mode 或独立 Runtime 获得不同安全规则。
- 一旦开始 Task 就把用户强制送到与 Conversation 无关的 Task Manager。

---

## 28. End-to-End Interaction Flows

### 28.1 Ordinary conversation

~~~text
Home / Agent Detail
→ Continue Conversation
→ USER_INPUT
→ NORMAL_RESPONSE
→ Conversation remains open indefinitely
~~~

- 不创建 Task。
- 没有 Workspace 时不强迫选择。
- Agent identity 和 Multiple Conversations 仍让体验区别于 model-first chat。

### 28.2 Character-style conversation compatibility

~~~text
Agent Detail with Persona identity
→ New Conversation
→ PERSONA_GREETING
→ expressive Messages
→ no execution chrome unless real capability is invoked
~~~

- 使用同一 Message / Conversation framework。
- 没有硬编码 Character Mode。
- v0.1 不要求 Character-specific creation UI。

### 28.3 SSH golden path

~~~text
USER_INPUT: "Production Nginx 返回 502，帮我修复"
→ origin Message remains in Timeline
→ stable Task Card appears
→ Workspace / target context confirmed
→ Run PLANNING
→ Safe Read ToolCalls visible in Task Card
→ Plan updates from evidence
→ MODIFY Envelope requires Permission
→ Permission Card: Review operation
→ Full Permission Review from immutable Envelope
→ Approve Once
→ ToolCall EXECUTING + Live Output
→ VERIFYING with explicit criteria
→ COMPLETED — Verified
→ Task RESOLVED
→ RUN_RESULT Message
~~~

### 28.4 Missing Workspace

~~~text
Execution intent
→ Task OPEN, no Run yet
→ Workspace needed card / sheet
→ user selects Workspace
→ Run Snapshot created
→ normal execution flow
~~~

UI 不把 Agent Default Workspace 后续变化回写到已有 Task / Run。

### 28.5 Waiting Permission

~~~text
Run WAITING_PERMISSION
→ Task Card and Needs Attention show Review operation
→ user may ask ordinary questions in Composer
→ user opens Permission Review
→ Approve Once / Reject / Cancel Run
→ decision persists before state continues
~~~

输入自然语言不会批准。

### 28.6 Waiting for Agent question

~~~text
Run WAITING_USER
→ RUN_QUESTION Message
→ Composer context attaches to Run
→ user answers
→ answer shown once in Timeline
→ append-only runtime input incorporated
→ Run returns to Planning / Running / Verifying
~~~

如果是 Credential，改用 secure UI，不在 Message 中显示秘密。

### 28.7 User guidance during active Run

~~~text
Active Run
→ user taps Guide this Run
→ Composer shows explicit Run context
→ user sends guidance
→ guidance queues until safe boundary
→ Runtime incorporates append-only input
→ re-plan if valid
~~~

如果 guidance 要改变 frozen target / Workspace，UX 引导创建新 Run / Task / Conversation，不修改 Snapshot。

### 28.8 Cancel without unknown outcome

~~~text
Cancel Run
→ confirmation explains no rollback
→ Run CANCELLED
→ Task remains OPEN
→ Retry creates new Run OR Close Task
~~~

### 28.9 Cancel with UNKNOWN_OUTCOME

~~~text
mutating ToolCall may have been delivered
→ UNKNOWN_OUTCOME
→ Run RECONCILING
→ user Cancel Run
→ source Run CANCELLED
→ Remote state unknown attention remains
→ Retry creates new Run
→ reconciliation precondition
→ read-only establishment of remote truth
~~~

结果：

- CONFIRMED_NOT_EXECUTED：解除 obligation，重新规划。
- CONFIRMED_EXECUTED：解除不确定性，进入 Verification。
- PARTIAL / UNRESOLVED：继续 Needs Attention，阻断潜在冲突 mutation。

### 28.10 Host changed after approval request

~~~text
PermissionRequest shows frozen target
→ mutable SSHHost target-defining field changes
→ request becomes stale / invalid
→ Approve disabled
→ user reviews a newly created Envelope if they still want execution
~~~

UI 绝不把旧 page 中的 friendly Host label 更新后继续绑定旧 approval action。

### 28.11 Runtime safety floor tightened

~~~text
Old Run / old PermissionRequest
→ current Runtime safety rule becomes stricter
→ execution-time review returns ASK / DENY / invalid
→ UI shows tightened current rule and blocks stale action
→ Snapshot remains historical and unchanged
~~~

### 28.12 Background and process recovery

~~~text
User leaves App
→ visible background affordance where platform permits
→ process may survive or terminate
→ user returns
→ UI rebuilds from persisted facts
→ RUNNING / PAUSED / RECONCILING shown truthfully
→ no mutating ToolCall auto-replayed
~~~

### 28.13 Another execution request while one Run is active

~~~text
Conversation B receives execution intent
→ ordinary Message persists
→ UI identifies active Run in Conversation A
→ discuss-only OR create OPEN Task OR return / cancel current Run
→ no second active Run starts automatically
~~~

---

## 29. Run Detail

### 29.1 Purpose

Run Detail 是一次执行尝试的 authoritative mobile view。它提供完整上下文，但仍以移动端单列阅读为主，不模拟桌面多窗格控制台。

### 29.2 Page structure

~~~text
Run Detail
├── Task / Run identity and state
├── Blocking or safety banner
├── Frozen context summary
├── Current Plan / Plan history
├── ToolCalls and output
├── Permission decisions
├── Verification
├── FinalReport / reconciliation outcome
└── Audit facts and actions
~~~

可以使用 section anchor 便于跳转，但不能要求横向 tab 才能发现 waiting action。

### 29.3 Header

显示：

- Agent identity。
- Task title。
- `Run # / Attempt #`。
- Run state。
- Task lifecycle status。
- elapsed / start / end time。
- Workspace environment。

若存在 blocking item，banner 位于首屏，不隐藏在下方 section。

### 29.4 Frozen context

默认摘要显示：

- Agent revision。
- Workspace snapshot name / revision。
- Host / username / directory。
- safety policy provenance 的用户可理解摘要。

Technical detail 显示：

- Runtime / policy versions。
- Provider / model snapshot。
- Skill versions。
- current safety floor reevaluation events。
- Credential logical reference 和实际 rotation version，不显示秘密。

当前配置与 frozen Snapshot 不同，使用并列标签：

~~~text
Used by this Run | Current configuration
~~~

不能只显示当前配置。

### 29.5 Attempt navigation

- 从 Task Card 进入时默认打开 latest Run。
- 可以打开 earlier Run，但页面 header 始终显示 attempt number 和 terminal state。
- earlier Run 的 Permission / ToolCall / Evidence 不可编辑。
- Reconciliation 在新 Run 中发生时，显示 source ToolCall link 和 obligation relation。

### 29.6 Actions

按状态显示：

- Active：Cancel Run。
- WAITING_PERMISSION：Review operation。
- WAITING_USER：Answer / resolve secure input。
- PAUSED：Resume / resolve blocker。
- RECONCILING：View current checks；只读限制说明。
- Terminal Run：Retry Task、Close Task 或返回 Conversation，符合 Baseline 条件时。

Run Detail 不提供“编辑 Snapshot”或“恢复此终态 Run”。

### 29.7 Audit disclosure

普通用户默认看到语义化 facts；高级用户可以展开：

- Envelope / target Digest。
- exact timestamps。
- policy versions 和 decisions。
- stream sequence / truncation。
- reconciliation source / resolution events。
- Evidence digests。

高级信息不能替代首层的人类可理解状态。

---

## 30. Error, Recovery and Empty States

### 30.1 Principles

- 说明“发生了什么”“是否可能有远端效果”“用户现在能做什么”。
- 不把所有错误都归为 `Something went wrong`。
- 可恢复错误优先显示 PAUSED / WAITING_USER，而不是永久 FAILED。
- 所有 retry action 遵守 Runtime retry safety，不能因 UI 方便盲目重放。

### 30.2 State matrix

| Condition | Surface | Required copy / action |
|---|---|---|
| Provider auth failed | Task Card + Attention | Update Provider credential；不自动重试 |
| Provider rate limit / unavailable | Task Card | Paused / retry timing；Resume when allowed |
| Model lacks structured ToolCall | Conversation / Task setup | Chat is available；real execution requires capable model |
| SSH unreachable | Task Card | Connection failed；bounded retry / pause facts |
| SSH auth failed | Secure attention | Update credential；secret 不进 Message |
| Host Key mismatch | Blocking security page | Old / observed fingerprint；no silent accept |
| Workspace missing | Task Card / sheet | Select Workspace before Run creation |
| Envelope invalidated | Permission Review | Stale；review new request |
| Current safety floor DENY | Runtime system card | Blocked by current safety rule；no Approve |
| Tool exit nonzero | ToolCall | Command failed / exit code；Task outcome pending |
| Tool timeout / disconnect | ToolCall | certainty 和 reconciliation requirement |
| Output truncated | Output view | byte counts、head / tail retained、not complete |
| Egress blocked | Egress card | data kept local / safe view unavailable |
| Verification FAIL | Verification | failed criteria + next options |
| Verification INCONCLUSIVE | Attention | evidence insufficient；user input / further checks |
| Process recovered | Active strip / Task Card | recovered current state；no replay |
| UNKNOWN_OUTCOME | P0 Attention | remote outcome unknown；reconcile before conflict mutation |

### 30.3 Empty states

| Surface | Empty state |
|---|---|
| Home, no Agent | Explain Agent + Create Agent |
| Agent, no Conversation | Start first Conversation |
| Conversation, no Message | greeting 或 focused Composer |
| Activity, no execution | Explain that real Agent work will appear here；return to Agents |
| Needs Attention empty | 不显示占位大卡；Home 继续突出 Agent continuity |
| Run, no ToolCall yet | Preparing / Planning；不显示空 terminal |
| Verification not started | 不显示灰色 PASS；说明 pending |

### 30.4 Offline

- 历史 Conversation、Task、Run 和 local audit 可继续查看，受本地数据策略约束。
- 新 Provider / SSH action 说明网络不可用。
- 已提交但未确定 delivery 的 mutating ToolCall 按 UNKNOWN_OUTCOME 处理，不显示简单 `Retry`。
- 离线输入可以保留 draft；是否发送必须由用户在恢复连接后明确触发，不能伪装成已送达。

### 30.5 Host Key review

首次 Host Key 和 mismatch 使用专用安全 page：

- Hostname、resolved address、port、username。
- observed fingerprint。
- previously pinned fingerprint，如有。
- 清楚区分首次信任和身份变化。
- Agent explanation 不能替代 fingerprint comparison。
- mismatch 不提供 Agent 自动接受。

### 30.6 Erase with unresolved obligation

当用户尝试 Erase source Conversation / Agent history：

- 检测 unresolved side-effect obligation。
- 阻止删除重建义务所需的最小 facts。
- 引导先执行 reconciliation。
- 不以“我知道风险”的普通确认直接清除远端不确定性。

---

## 31. Accessibility, Internationalization and Privacy

### 31.1 Accessibility

- 支持平台动态字体；安全字段在放大后保持顺序，不横向重叠。
- interactive target 满足平台可触达尺寸，不把两个相反安全 action 紧贴。
- 状态、风险和 verdict 同时使用 icon、文字和结构，不只靠颜色。
- screen reader 先读状态和 action，再读解释与 metadata。
- Live Output 更新节流播报，不逐行打断用户。
- focus 在 Permission stale、RUN_QUESTION 或 UNKNOWN attention 出现时移动到提示，但不强制滚动离开用户正在阅读的 exact content。
- 支持 reduced motion；运行状态不能只靠动画表达。

### 31.2 Exact command accessibility

- 每个 control / Unicode token 有唯一 accessible label，包括 byte / code point。
- screen reader 能区分 literal backslash-n 与 LF byte。
- code region 使用 LTR isolation 并提供“第 N 行 / byte count”等结构信息。
- 横向滚动不阻止键盘、switch access 或 screen reader 到达完整 bytes。
- warning 与 exact content 均可被辅助技术读取。

### 31.3 Internationalization

- UI status 和解释可以本地化；Domain state name 在 technical detail 中保持稳定。
- Command、hostname、username、fingerprint、digest 和 log 不翻译。
- RTL locale 中安全关键 byte view、IP、digest 和 fingerprint 使用隔离方向。
- 本地化不能把 `Approve Once` 翻译成含永久授权歧义的词。
- `Cancel Run`、`Close Task`、`Archive Conversation` 使用不同本地化术语。

### 31.4 Privacy and shoulder surfing

- Credential 永不显示在 Message、Permission command、普通日志或截图友好摘要中。
- SENSITIVE / SECRET output 默认折叠规则适用于 app switcher preview。
- 回到前台时，已 reveal 的 SECRET 可以重新遮蔽。
- Notification 不包含 raw command、secret output 或完整 Host Key，除非用户明确配置且策略允许；MVP 默认保守。
- Agent portrait 或 Character-style background 不覆盖 privacy warning。

### 31.5 Large screens and orientation

- 规范以 phone portrait 单列为基线。
- landscape / tablet 可以把 Timeline 与 Run Detail 并排，但必须保持相同 authority hierarchy。
- 不要求并排视图才能完成 Permission、回答问题或 Cancel Run。
- 折叠到窄屏时，先下移辅助 metadata，不能隐藏 target、risk 或 primary action。

---

## 32. UX Vocabulary and Copy Rules

### 32.1 Run state labels

| Domain state | Preferred user label |
|---|---|
| CREATED | Preparing |
| PLANNING | Planning |
| RUNNING | Working |
| WAITING_PERMISSION | Approval required |
| WAITING_USER | Your input is needed |
| RECONCILING | Checking remote state |
| PAUSED | Paused |
| VERIFYING | Verifying |
| COMPLETED | Completed — Verified |
| FAILED | Run failed |
| CANCELLED | Run canceled |

UNKNOWN_OUTCOME 是 ToolCall / safety fact，使用：

> `Remote outcome unknown`

不能只写 `Canceled` 或 `Failed` 隐藏它。

### 32.2 Task labels

| Task status | Label |
|---|---|
| OPEN | Open task |
| RESOLVED | Resolved |
| CLOSED | Closed |

Task label 与 Run label并列显示，不合并为单一状态。

### 32.3 Action labels

使用：

- `Review operation`
- `Approve Once`
- `Reject`
- `Cancel Run`
- `Retry Task`
- `Close Task`
- `Archive Conversation`
- `Review data sharing`
- `Send safe view`
- `Establish remote state`
- `View evidence`

避免：

- `OK` 用于高风险决定。
- `Stop` 同时表示取消 ModelInvocation、Run 和 Task。
- `Done` 表示未经 Verification 的结果。
- `Continue` 同时表示 Resume 和 Approve。
- `Trust me`、`Safe` 等缺少证据的 Agent 文案作为系统标签。

### 32.4 Fact vs interpretation copy

- Runtime fact 使用陈述语气：`Command exited with code 1`。
- Agent interpretation 明确归属：`Agent explanation: Nginx may be using an invalid upstream address`。
- Verification 使用证据语气：`Expected active; observed active; deterministic PASS`。
- Unknown 使用不确定性：`The command may have reached the server; its effect is not yet known`。

### 32.5 Time copy

- Active state 显示 elapsed 和 last update。
- Waiting state 显示 waiting since。
- Historical state 显示 completed / failed / canceled time。
- `Just now` 等相对时间可用于摘要，detail 保留绝对时间。

---

## 33. Presentation Source-of-Truth Matrix

| UX component | Required source | Forbidden shortcut |
|---|---|---|
| Agent Header | Agent + Persona revision | Provider model name 代替 Agent |
| Conversation title | Conversation | 从最新模型回复临时推断且不保存 |
| Conversation Workspace chip | Conversation.workspaceId | Agent 当前默认值覆盖旧 Conversation |
| Task Card | Task + latest Run projection | 一段 Assistant summary 代替状态 |
| Active Run Strip | active Run + current events | 本地 animation timer 猜测 state |
| Plan | PlanVersion / PlanStep | 模型散文列表代替 versioned plan |
| ToolCall row | ToolCall + executed Envelope + ToolEvents | Markdown command block |
| Permission Review | immutable Envelope + PermissionRequest + Runtime decision | model-provided pretty command / mutable SSHHost |
| Live Output | local ToolEvent / LogReference | Provider-safe summary 冒充 raw local output |
| Egress Card | sensitivity + egress policy decision | execution permission 推断 |
| Verification | VerificationRecord + Evidence | exitCode / model claim |
| RUN_RESULT facts | Run / Task / Verification / FinalReport | Persona 自由发挥改变 outcome |
| Needs Attention | current blocking facts + unresolved obligations | dismissed notification state |
| Activity | persisted Task / Run / audit facts | Timeline cache |

### 33.1 Action source matrix

| User action | Must produce / reference | Text input equivalent? |
|---|---|---|
| Approve Once | PermissionDecision + matching Envelope Digest | No |
| Reject | PermissionDecision | No implicit approval; explicit action required |
| Answer RUN_QUESTION | append-only runtime input + Message reference | Yes, only in explicit Run reply context |
| Update Credential | secure Credential flow | No |
| Cancel Run | persisted cancel intent for exact Run | No ambiguous `stop` text |
| Close Task | Task lifecycle action | No effect on Run / obligation |
| Guide Run | USER_INPUT Message + related append-only runtime interaction | Yes, explicit Run context |
| Send safe observation | egress decision | No execution permission inference |

### 33.2 Restoration contract

页面恢复必须从 authoritative facts 重新构建 projection：

~~~text
Persisted Domain facts and events
+ current Runtime safety decisions
+ unresolved obligation projection
→ current UX state
~~~

UI cache、navigation state、notification state和动画不是真实来源。

---

## 34. UX Acceptance Criteria

### 34.1 Agent-first IA

- Root navigation 是 Home / Agents / Activity / Settings。
- Home 以 Agent continuity 和 Needs Attention 为主，不成为完整 Task list。
- Agents 是一级入口，Conversation 明确属于 Agent。
- Agent Detail 支持 Continue、New Conversation、Multiple Conversations 和 Configuration summary。
- Provider / model 不成为 Conversation 的主身份。
- Activity 行先显示 Agent identity，再显示 Task / Run facts。
- 从 Activity 可以回到所属 Conversation 和 Task Card。

### 34.2 Unified Conversation Framework

- 普通聊天、Character-style conversation 和真实 execution 使用同一 Conversation Shell。
- Timeline 一级 item 只有 Message 和 Task Card projection。
- Plan、ToolCall、Permission、Live Output 和 Verification 不伪装为 Message。
- 普通说明类问题只产生 Message，不产生空 Task Card。
- 真实 SSH intent 产生 Task；Run 前置条件不足时 Task 保持 OPEN 并显示补全入口。
- 不存在 Server / Character Runtime mode switch。
- Persona styling 不能改变 Capability、Permission、Workspace、risk 或 outcome facts。
- Future Character-style Agent 获得真实 Skill 后使用同一 Task / Permission / Verification UI。

### 34.3 Multiple Conversations

- 一个 Agent 可以创建和继续多个独立 Conversation。
- Conversation Switcher 只显示当前 Agent 的 Conversations。
- 每项显示 title、Workspace、recent preview 和 active / attention badge。
- 切换 Conversation 不 Cancel Run、不改变 Workspace、不迁移 draft。
- 已有执行型 Conversation 选择其他 Workspace 时创建新 Conversation。
- 有 active Run 的 Conversation 不能被 Archive。
- Archive 不清除 unresolved side-effect obligation。

### 34.4 Home, Agents and Agent Detail

- active Run 存在时 Home 显示单一 global active affordance。
- Home Permission attention 只能进入 Review，不可直接 Approve。
- Needs Attention 按 safety uncertainty、decision、input、recovery、outcome 排序。
- 没有 Needs Attention 时 Home 不保留大型空告警区。
- Agent list 优先 active / attention Agent，并显示 Default Workspace / capability summary。
- Agent Detail 的 Agent identity、Conversation 列表和配置摘要边界清楚。
- Archived Agent 不允许创建新 Conversation / Run。

### 34.5 Conversation Shell and Timeline

- Conversation 首屏可识别 Agent、Conversation title、Workspace context 和 active / blocking state。
- Task Card 在 origin Message 后保持稳定 Timeline anchor。
- Task projection 更新不强制改变用户滚动位置。
- off-screen active Task 可通过 sticky Active Run Strip 返回。
- RUN_QUESTION 和 RUN_RESULT 是关联 Run 的 Message，并能跳到 Task Card。
- Runtime internal events 不逐条污染 Timeline。
- 普通 Markdown、model text 和 tool output 不能渲染 system-owned actions。
- 新 activity 不在用户阅读旧内容时强制 autoscroll。

### 34.6 Task Card and Run Detail

- Task lifecycle 和 latest Run state 同时显示且不混淆。
- active / waiting / unknown Task Card 默认展开；历史 verified Task 默认折叠。
- Task Card 显示 current Plan step、latest factual event、blocking action 和 Verification summary。
- 多次 Run 显示 attempt number，历史 Run 可达且不可改写。
- CANCELLED Run + unresolved obligation 同时显示两个事实。
- Run Detail 显示 frozen Snapshot，而不是只显示 current Agent / Host config。
- Run Detail 提供 Plan、ToolCalls、Permission、Output、Verification、FinalReport 和 reconciliation relation。
- Retry 创建新 Run；UI 不提供恢复终态 Run。

### 34.7 Active Run and Composer

- v0.1 全局只出现一个 active Run affordance。
- 离开 Conversation 不停止 active Run。
- Cancel Run 是独立明确 action，并说明不会自动回滚。
- 可能已经送达 mutating ToolCall 时，Cancel warning 说明可能产生 UNKNOWN_OUTCOME。
- 普通 Composer 默认发送 Conversation Message。
- active Run 期间的普通 Message 不影响 Run；若回复需要等待安全 boundary，UI 明确显示而不伪装已被 Run 消费。
- Guide this Run 使用显式 Run context chip，并记录 append-only runtime input。
- guidance 不能改写正在执行或已批准的 Envelope。
- guidance 实质改变 Workspace / target / goal 时，UI 引导创建新的安全执行上下文。
- WAITING_PERMISSION 时普通文本不能 Approve / Reject。
- WAITING_USER 时 Composer 明确关联 RUN_QUESTION。
- Credential 不进入普通 Composer。
- 另一个 active Run 存在时不自动启动第二个 Run，也不实现自动队列。

### 34.8 Workspace Context

- Conversation Workspace、Task Workspace 和 Run / Envelope frozen target 在不同 surface 明确标注。
- Header chip 只表示 Conversation context，不冒充 execution target。
- Task Card 显示 Task Workspace / environment。
- Permission、ToolCall Detail、Run Detail 和 reconciliation 显示 frozen execution facts。
- Production 有文字标签。
- 没有 Workspace 的普通聊天不被强制配置 Workspace。
- execution intent 缺 Workspace 时必须选择，不能猜测 Host。
- Host target-defining fields 变化后旧 Permission surface 进入 stale / blocked。

### 34.9 Permission Review

- MODIFY / DESTRUCTIVE 不能从 compact preview 直接批准，必须进入 full-screen Permission Review。
- full-screen Permission Review 是 Baseline Permission Card 的 authoritative expanded state；批准前显示完整 exact command、rollback 和 Verification Plan。
- Review Page 默认显示 Agent、Conversation、Task、Workspace、environment、hostname、resolvedAddress、port、username、Host Key fingerprint 和 workingDirectory。
- frozen target 和 execution parameter 中的 control、ANSI、BiDi、zero-width 等字符同样安全转义，friendly labels 不能冒充 semantic fields。
- exact command 只来自同一 immutable Envelope 的 `exactCommandBytes`。
- UI 不 trim、格式化、换引号、变量插值或生成漂亮版命令。
- LF、CR、TAB、ESC、NUL、ANSI、BiDi、zero-width、invalid UTF-8 和其他危险 byte / code point 被确定性可见化。
- literal `\n` 与 LF byte 在视觉和 screen reader 中可区分。
- leading / trailing / repeated spaces、trailing newline 和 end-of-command 在视觉上可辨识。
- mixed-script / confusable Unicode 产生 warning，且每个非 ASCII code point 可检查其 bytes。
- command viewer 不执行 ANSI，使用 LTR isolation，默认不 soft-wrap。
- 所有 bytes 可达；省略 preview 不能承载最终 approval。
- 非空 environment、shellMode、stdinMode、timeout 和 Working Directory 在批准前可见。
- Runtime risk 与 Agent explanation 视觉和标签分离。
- Rollback 说明明确为未来新操作，不暗示自动执行。
- Verification Plan version 和 `Used for approval` relation 可见。
- Approve Once 绑定当前 Digest，不提供永久允许。
- CRITICAL 只有 DENY，没有 Approve action。
- 输入 `yes / approve` 不产生 PermissionDecision。
- Envelope、target、policy 或 Verification Plan 变化后旧 page 立即 stale，不能让按钮静默绑定新请求。
- Current Runtime Safety Floor 的新 DENY 可以移除旧 approval action。
- 历史 PermissionDecision 和 executed Digest 在 Run Detail 可审计。

### 34.10 Observation and Egress

- Live Output 明确区分 stdout / stderr、truncation、certainty 和 sensitivity。
- Live Output 不是交互式 terminal。
- ANSI、prompt injection 和伪 system control 只能作为不可信文本显示。
- NORMAL 可以 inline preview；SENSITIVE 默认脱敏 / 折叠；SECRET 默认隐藏 raw preview。
- local reveal 与 Provider egress 是不同 action。
- Full Output Page 显示 provider-safe view 的 egress state。
- Execution Permission 不推断 Egress permission。
- Egress ASK 使用独立 card，显示 Provider、data class 和 proposed safe view。
- SECRET raw output 不提供普通 override send action。

### 34.11 Verification and RUN_RESULT

- Task success 不从 exitCode 或 model claim 推断。
- Verification 显示 REQUIRED / ADVISORY、expected、actual、evaluator、evidence 和 verdict。
- DETERMINISTIC、STRUCTURED_RULE、MODEL_ASSISTED 和 USER_CONFIRMED 在 UI 中可区分。
- MODEL_ASSISTED 单独不能显示安全关键高可信 PASS。
- 当前、FAIL 和 INCONCLUSIVE criteria 优先展开。
- 已被 Permission 引用的 Verification Plan version 可见。
- 静默削弱 criteria 时产生 blocking warning，不允许 COMPLETED。
- `Completed — Verified` 只用于所有 REQUIRED criteria 获得可信 PASS。
- RUN_RESULT 显示 outcome、root cause、changes、verification 和 unresolved risk。
- RUN_RESULT 使用 Persona 语气时，system outcome / warning 不被改写。
- UNKNOWN_OUTCOME 未解决时 RUN_RESULT 不使用 success wording。

### 34.12 Needs Attention and Activity

- Needs Attention 是 projection，不新增 Task status。
- P0 unresolved uncertainty 跨 Cancel、Close、Archive、process restart 和新 Run ID 保留。
- Home 同一 Task 只显示最高优先 attention；Activity 保留完整 facts。
- 打开、dismiss notification 或离开 page 不解决 attention。
- Permission、RUN_QUESTION、Credential、Host Key、Paused、Verification 和 Unknown 使用不同 CTA。
- Activity 包含 Active、Needs Attention、Recent outcomes 和 Audit history。
- Activity 不提供 batch Approve 或批量清除 unknown。
- 历史 retry 不隐藏 canceled / failed attempts。

### 34.13 Recovery and failure

- UI rebuild / process restart 后从 persisted facts 重建状态。
- 可能有副作用的 ToolCall 不因页面重建出现普通 Retry。
- recovered state 为 PAUSED / RECONCILING 时如实显示，不伪造 RUNNING。
- UNKNOWN_OUTCOME → Cancel → Retry 仍先显示 reconciliation precondition。
- CONFIRMED_NOT_EXECUTED 后允许正常重新规划。
- CONFIRMED_EXECUTED 后先进入 Verification。
- PARTIAL / UNRESOLVED 继续阻断潜在冲突 mutation。
- Erase 不能删除 unresolved obligation 所需的最小 facts。

### 34.14 Accessibility and privacy

- 所有安全状态不只靠颜色。
- 动态字体下 risk、target、command 和 primary action 仍按正确顺序可读。
- exact byte tokens 有 screen reader label。
- RTL locale 不重排 exact bytes、fingerprint 或 digest。
- reduced motion 不丢失运行状态。
- sensitive output 不进入 app switcher / notification preview。
- 已 reveal SECRET 回到前台时可重新遮蔽。
- Persona visual styling 不降低 system safety surface 对比度。

---

## 35. UX Validation Scenarios

至少验证以下场景：

| Scenario | Expected UX result |
|---|---|
| 普通询问 Nginx 原理 | 只有 Messages；无 Task / Run chrome |
| “如果修复你会怎么做” | 普通建议；不连接 SSH |
| “检查 production 502” | origin Message + stable Task Card + Workspace facts |
| 无 Workspace 的 execution intent | Task OPEN；Workspace selection；Run 尚未创建 |
| Safe Read auto ALLOW | ToolCall 可见、output 可见、无需 Permission Card |
| READ command 无法证明安全 | ASK / DENY UX；不因 Agent 声称 READ 自动执行 |
| MODIFY waiting permission | Home / Conversation attention → full Review Page |
| 用户在 Composer 输入 “yes” | 产生普通 Message 或引导；不批准 Envelope |
| exact command 含 literal `\n` 与真实 LF | 两者视觉与 accessibility 明确不同 |
| command 含 ANSI ESC | 不执行颜色 / cursor；显示 ESC token 和 warning |
| command 含 BiDi override / zero-width | 显示 code point token；顺序不被欺骗 |
| command 含 mixed-script confusable | 原 code point 可检查并显示 warning；不做 normalization |
| invalid UTF-8 bytes | 使用 hex token，显示 encoding warning |
| command 含 trailing spaces / trailing newline | whitespace 和 end marker 明确显示，不被 trim |
| 超长多行 command | 全部 bytes 可达；approval 不依赖省略 preview |
| hostname 与 resolvedAddress 不同 | Permission target block 同时显示两者 |
| Host label / hostname / username 含 control 或 BiDi | friendly label 与 frozen field 分开；异常字符可见化 |
| Approve request 后 SSHHost hostname / port 变化 | page stale；Approve blocked；不更新到新 target 后继续 |
| Approve request 后 username / Host Key 变化 | page stale；必须新 Envelope |
| Credential 同 logical reference rotation | target 不显示为改变；rotation 在 audit 可见 |
| Runtime safety floor 从 ALLOW 收紧为 ASK / DENY | 当前 page 更新为等待新审阅或 blocked；Snapshot 历史不改 |
| Runtime rules 变宽松 | 旧 Snapshot 更严格约束仍在 UI 生效 |
| Verification Plan 实质削弱 | blocking criteria-change UI；旧 Permission relation 可见 |
| Tool output 包含伪 `[Approve]` | 纯 output 文本；无系统 action |
| Tool output 为 SENSITIVE | inline 折叠 / redacted；local reveal 与 egress 分离 |
| Tool output 为 SECRET | raw preview hidden；Provider raw send unavailable |
| Live Output 用户向上滚动 | 停止 follow；显示新增行数量 |
| exitCode 0 但 HTTP check FAIL | ToolCall finished；Task 不显示 verified success |
| MODEL_ASSISTED 声称成功但无 deterministic evidence | criterion INCONCLUSIVE；不能 Completed — Verified |
| RUN_QUESTION | Message + explicit Run composer context；回答只显示一次 |
| Credential question | secure UI；secret 不进入 Timeline |
| guidance while ToolCall executing | 进入 queue for safe boundary；不改写 Envelope |
| guidance 改变 Workspace | 解释 immutable Snapshot；创建新安全上下文路径 |
| Cancel before mutation | Run CANCELLED；Task OPEN；Retry / Close 分开 |
| Cancel while mutation delivery uncertain | unknown warning；obligation survives |
| UNKNOWN → Cancel → Retry | 新 Run 先 reconciliation；mutation blocked |
| UNKNOWN → CONFIRMED_EXECUTED | 进入 Verification；不直接 success |
| UNKNOWN → CONFIRMED_NOT_EXECUTED | obligation resolved；允许 re-plan |
| Archive / Close source Task with unresolved obligation | attention 仍在；不视为 resolved |
| Erase source history with unresolved obligation | Erase blocked / minimal facts retained |
| App 在 WAITING_PERMISSION 时被终止 | 恢复 request；重新验证有效性；不自动 approve |
| App 在 EXECUTING 时被终止 | 恢复为真实 state；不盲目 replay |
| 另一 Conversation 已有 active Run | global strip；新 Run 不自动启动 |
| 切换 Conversation | 原 Run 不 Cancel；draft 隔离 |
| Future Character-style Agent 无 Skill | expressive Messages；无 execution chrome |
| Future Character-style Agent 有 SSH Skill | 同一 Task / Permission safety UI；Persona 不覆盖 risk |
| Dynamic type / screen reader | target、byte tokens、risk、actions 全部可达且顺序正确 |
| RTL locale | exact command、IP、fingerprint、digest 顺序保持 |

---

## 36. Consistency Decisions

本规范锁定以下 UX 决策：

1. 使用统一 Conversation Shell，不建立 Chat / Operations 双模式。
2. Timeline 一级 item 只有 Message 与 Task Card projection。
3. Task Card 在 origin 位置稳定更新，Active Run Strip 解决 off-screen 可见性。
4. Agent identity 是全局主身份，Provider / model 是配置 metadata。
5. Home 负责“现在继续什么”，Activity 负责完整执行和审计。
6. Multiple Conversations 位于 Agent ownership 内，不作为全局 chat files。
7. Workspace 在 Conversation、Task、Run / Envelope 三层分别显示，不互相冒充。
8. 普通 Composer 默认产生 Message；Run guidance 需要显式 context。
9. WAITING_PERMISSION 不锁死普通 Conversation，但自然语言不能批准。
10. Permission Card 只负责提示；真实批准使用 full-screen Permission Review。
11. exact command 使用 byte-faithful safe rendering，不存在可批准的 pretty command。
12. Permission target 来自 frozen Envelope，不来自当前 mutable SSHHost。
13. Execution Permission 与 Observation Egress Decision 使用独立 card 和 action。
14. ToolCall finished 与 Task verified 使用不同状态语言。
15. RUN_RESULT 是 Message，但 system outcome 来自 Run / Verification facts。
16. Needs Attention 是 event / state projection，不是新 Domain entity。
17. UNKNOWN_OUTCOME 的注意项跨生命周期保留，直到 reconciliation 建立远端真相。
18. Server / Character 差异来自 Persona、capability、Workspace 和当前状态，不来自硬编码 Agent mode。
19. Persona styling 永远不能覆盖 System Trust Layer。
20. 本规范没有发现需要提出 `Architecture Baseline Change Request` 的冲突。

---

## 37. UX Non-Goals

- 不重定义 Agent、Conversation、Task、Run、Message 或 Workspace Domain。
- 不增加新的 Runtime type 或 Agent type。
- 不增加 Character Card import、Lorebook、Swipe、Regenerate、Group Chat 或长期 Memory UI。
- 不增加 Marketplace、Skill 安装或动态 executable extension。
- 不设计完整 interactive terminal、file editor、SFTP 或 mobile IDE。
- 不设计多个并发 active Run。
- 不设计自动任务队列、无人值守执行或定时任务。
- 不设计团队审批、RBAC、共享 Conversation 或云同步。
- 不锁定 Material、Compose、Flutter 或其他 UI framework。
- 不锁定 view model、database、event bus、stream 或 navigation 实现。
- 不锁定 Android background execution 机制。
- 不创建 Implementation Plan、工程任务或代码。

---

## 38. UX Approval and Freeze

本规范当前状态：

> `Conversation & Execution UX Specification v0.1 — Approved`

本规范已冻结为 v0.1 Conversation、Execution 和 Agent-first 移动端体验的 authoritative source of truth。任何后续需要改变已批准核心 Interaction Contract 的设计，必须先提出 `UX Specification Change Request`；若变更同时影响 Approved Architecture Baseline，则还必须提出 `Architecture Baseline Change Request`，不得在 System Architecture 或其他下游文档中静默改变。

本阶段已完成：

- Agent-first mobile IA 的完整展开。
- Unified Conversation Framework。
- Home、Agents、Agent Detail、Multiple Conversations、Conversation Timeline 和 Activity。
- Message、Task Card、Active Run、Plan、ToolCall、Live Output、Permission、Verification、RUN_RESULT 和 Composer。
- Workspace Context、Needs Attention、恢复和跨 Run unknown obligation 的 UX。
- Server / future Character-style differentiation without modes。
- immutable Execution Envelope 的安全展示和 UX validation scenarios。

本阶段没有：

- 进入 System Architecture。
- 进入 Implementation Plan。
- 选择 UI framework。
- 除按 Final Architecture Review 更新批准 / 冻结状态外，没有改变 Approved Baseline 的产品、领域、安全或 Runtime 语义。
- 编写代码。

UX Specification 阶段至此结束。下一步进入独立的 System Architecture v0.1 设计阶段；该阶段必须同时引用 Approved / Frozen Architecture Baseline 与本 Approved / Frozen UX Specification。在 System Architecture 通过 Review 前，不进入 Implementation Plan 或代码。
