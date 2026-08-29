# System Architecture v0.1

- Version: 0.1
- Status: System Architecture v0.1 — Approved / Frozen
- Date: 2026-08-30
- Primary platform: Android-first mobile
- Upstream authority:
  - [Mobile-Native Personal Agent Runtime — v0.1 Architecture Baseline](./2026-08-29-mobile-native-personal-agent-runtime-v0.1-baseline.md)
  - [Conversation & Execution UX Specification v0.1](./2026-08-29-conversation-execution-ux-specification-v0.1.md)
- Document role: Approved / Frozen Baseline 与 Approved / Frozen UX Contract 的 v0.1 authoritative implementation architecture
- Phase boundary: System Architecture 阶段结束；下游可进入 Implementation Plan，但本文件不创建工程或编写代码

本文件定义 v0.1 在移动端本地运行的模块边界、依赖方向、Runtime 编排、并发、持久化、Projection、Provider、Permission、Observation / Egress、SSH、Credential、后台执行、恢复和测试架构。

本文件已经批准并冻结。Implementation Plan与实现必须引用本文件，不能静默重定义其module、authority、persistence、concurrency、recovery或security contract。若后续实现发现必须改变本文件或上游契约，应停止受影响部分并明确提出：

- `System Architecture Change Request`：改变本文件已经冻结的核心实现架构。
- `Architecture Baseline Change Request`：改变 Approved Architecture Baseline。
- `UX Specification Change Request`：改变 Approved UX 核心 Interaction Contract。
- 同时影响多个已冻结文档时，必须提出所有对应的 Change Request。

本轮设计没有发现需要提出上述 Change Request 的冲突。

---

## 1. Executive Architecture Decision

### 1.1 核心方案

v0.1 采用：

> **Android modular monolith + Ports and Adapters + authoritative relational facts + append-only Runtime Event Ledger + rebuildable projections + one serialized Run Coordinator**

它具有以下含义：

1. 整个产品默认在手机本地运行，不要求部署 companion server、控制平面或远端 Agent Runtime。
2. Domain 和 Runtime Core 不依赖 Android UI、具体 Provider、具体 SSH library 或数据库 framework。
3. Run 的所有状态变化只有一个逻辑 writer：`RunCoordinator`。
4. 任何模型提出的 SSH 操作都必须依次经过 trusted normalization、Permission、execution-time safety gate、SSH execution 和 Observation / Egress；没有旁路。
5. 安全事实和外部副作用事实先持久化，再推进执行；UI 只消费事实的 Projection。
6. Android 后台能力只承载“用户发起、用户可感知、best-effort continuation”，不成为事实来源，也不承诺进程永生。

### 1.2 最关键的所有权答案

> `RunCoordinator` 是 Run state 的唯一 writer。

Provider Adapter、Skill、SSH、Permission Engine、Egress Guard、Projection Engine、Notification 和 UI 都不能直接修改 Run state。它们只能：

- 接收不可变输入；
- 返回结构化结果；
- 或向 `RunCoordinator` mailbox 投递带有 `runId + attemptId + expectedRunVersion` 的结果事件。

`RunCoordinator` 使用确定性的 `RunStateReducer` 验证转换，随后通过单个事务追加事实并推进持久化状态。

### 1.3 架构成功条件

本架构只有同时满足以下条件才成立：

- 被分析、展示、批准和执行的是同一个 canonical `ExecutionEnvelope`。
- 实际 SSH send 前不存在绕过 current safety floor、cross-run obligation、Envelope、Permission 或 target validation 的路径。
- `AuthorizedExecutionTicket` 签发前只允许未认证的 SSH transport preparation；Credential、SSH authentication、exec channel 和 command bytes 均不能提前获得旁路能力。
- Provider 代码结构上无法读取 raw Observation storage。
- mutable `SSHHost` 无法在审批后重定向 frozen target。
- UI、Activity、Notification 与 Saved State 丢失时，持久化事实仍能恢复安全状态。
- 一个旧 Run、Cancel、Retry、Archive 或新 Run ID 都不能清除 unresolved external side-effect obligation。
- 所有需要可信完成语义的 Task 都由 Verification facts，而不是模型文案，决定 `COMPLETED`。

---

## 2. Upstream Contracts and Scope

### 2.1 继承且不重定义的契约

本架构直接继承：

- Agent → Multiple Conversations。
- Timeline 一级 item 只有 Message 与 stable Task Card projection。
- Message / Task / Run / ModelInvocation 分离。
- Conversation context / Task target / immutable Run Snapshot 三层 Workspace 语义。
- SSH ToolCall 必须属于 Task / Run。
- Persona / Character 与 Skill / Permission 隔离。
- Safe Read Profile 的保守自动执行边界。
- Execution Risk 与 Data Sensitivity 分离。
- immutable ExecutionEnvelope 与 frozen SSH target。
- Historical Policy Snapshot 与 Current Runtime Mandatory Safety Floor 取更严格结果。
- UNKNOWN_OUTCOME、RECONCILING 和 cross-run side-effect obligation。
- Verification Criterion → Check → Evidence → Evaluator → Verdict。
- Approved UX 中统一 Conversation Shell、显式 Composer destination、trusted Permission Review 和 exact byte rendering。

这些语义的权威定义仍位于上游文件；本文件只回答软件结构如何强制它们。

### 2.2 本文件负责

- 模块及允许依赖。
- Port / Adapter 边界。
- 单 writer Runtime orchestration。
- 并发和 linearization points。
- 数据表类别、事件、Projection 和事务边界。
- Composer destination 的提交验证。
- Provider normalization 和流式生命周期。
- raw Observation 与 Provider-safe view 的物理隔离。
- Permission、Envelope、Digest、ExecutionTicket 和 SSH send gate。
- Exact Command Viewer 的安全 renderer 边界。
- SSH transport、Host Key、credential lease、delivery certainty 和 reconciliation。
- Android 后台承载与进程死亡恢复。
- 系统级测试策略、风险与 ADR 候选。

### 2.3 本文件不负责

- Implementation Plan、任务拆分、里程碑或工期。
- UI framework、具体 navigation library 或 visual design token。
- Kotlin package、class、SQL migration 或 API 的最终代码签名。
- Marketplace、ACP Registry、Memory、Tavern 或新增 Skill 类型。
- Character 创建 UI。
- 多 active Run、远端 Runtime 或跨设备同步。
- 为方便实现而放宽任何上游安全语义。

---

## 3. Architecture Alternatives and Decision

### 3.1 Alternative A — Conventional layered app with shared repositories

结构是 UI → ViewModel → Repository → Provider / SSH / Database，各模块共享 repository 和 model。

优点：

- 初始文件少。
- 普通 CRUD 和聊天容易实现。

拒绝原因：

- Skill 或 UI 容易直接拿到 SSH client。
- Provider Context Builder 容易误读 raw output。
- 多个 ViewModel / Service 可能竞争写 Run state。
- Permission、host mutation 和 execution 之间难以建立一个可信 linearization point。

### 3.2 Alternative B — Full event-sourced microkernel with dynamic plugins

所有 Domain 都完全 Event Sourcing，Skill 和 Provider 作为动态插件加载。

优点：

- 扩展性与审计能力很强。
- 可自然支持远端 Runtime 和多 Skill 生态。

拒绝原因：

- v0.1 只有 SSH 这一种真实 Skill，动态加载扩大供应链与权限攻击面。
- 完整 Event Sourcing 会把配置 CRUD、schema migration 和调试复杂度一起前置。
- Android 本地资源与首版验证目标不需要微服务式复杂度。

### 3.3 Alternative C — Modular monolith with a Runtime ledger and narrow capability ports

优点：

- 单进程内可建立强顺序和 one-writer 语义。
- Domain / Runtime 保持平台无关。
- 安全关键调用通过窄 capability port 强制串联。
- 只对需要恢复、审计和副作用安全的 Runtime 采用 append-only facts，避免全量 Event Sourcing。
- 未来可以把 Adapter 或 Runtime 移到别的进程 / 设备，而不改变 Domain contract。

决定：

> 选择 Alternative C。

---

## 4. System Context and Trust Boundaries

### 4.1 运行拓扑

~~~mermaid
flowchart LR
    User[User] --> UI[Android UI]
    UI --> APP[Application Use Cases]
    APP --> CORE[Domain + Agent Runtime]
    CORE --> PERSIST[(Local Persistence)]
    CORE --> VAULT[Credential Vault]
    CORE --> PROVIDER[Provider Adapter]
    PROVIDER --> CLOUD[Selected Model Provider]
    CORE --> SSH[SSH Adapter]
    SSH --> HOST[User-selected SSH Server]
    CORE --> BG[Android Background Execution]
    BG --> NOTICE[Visible Notification]
~~~

不存在：

- 产品自有的必需远端 orchestration server。
- 审批后在云端替用户执行的 hidden worker。
- Provider 直接调用 SSH。
- UI 直接调用 SSH。

### 4.2 信任区域

| 区域 | 信任级别 | 规则 |
|---|---|---|
| Domain / safety policy / canonicalizer | Trusted Core | 版本化、确定性、无网络 |
| Run Event Ledger / immutable facts | Trusted Local Facts | 先写后执行，可恢复 |
| Permission Review / byte renderer | System Trust Surface | 只读 authoritative Envelope |
| Provider Adapter | Untrusted-remote boundary | 只能得到 Provider-safe context |
| Model output / Tool Proposal | Untrusted input | 必须 normalize、validate、authorize |
| SSH stdout / stderr / server text | Untrusted Observation | 不作为 instruction，先过 Egress Guard |
| Persona / Character content | Expression layer | 不能改变 Skill、Permission 或 facts |
| Android lifecycle / notification | Fallible platform shell | 不能作为 Run 事实来源 |

### 4.3 Threats explicitly addressed

- 模型伪造 `READ` 或 Permission decision。
- 输出中嵌入 prompt injection、ANSI、控制字符或伪 UI。
- 审批展示与实际 command bytes 不一致。
- SSHHost 在审批后被修改造成 target swap。
- Permission 后 safety policy 更新造成 stale approval。
- Cancel 与 SSH send 并发造成盲目 retry。
- Projection 被删除或滞后后误以为没有 unknown obligation。
- Provider / Context Builder 误读 raw secret output。
- Activity / process death 造成副作用重放。
- Credential 被 Message、Event、log、backup 或 crash report 泄露。

---

## 5. Layer and Module Architecture

### 5.1 Logical modules

| Module | Responsibility | May depend on |
|---|---|---|
| `presentation` | Home、Agents、Conversation、Run Detail、Permission / Egress trusted surfaces 的 UI state 与 intents | `application-api`, `projection-models`, `TrustedPermissionReviewModel` / safe presentation tokens |
| `application` | Use cases、input routing、command validation、query orchestration；`TrustedPermissionReviewAssembler` | `domain`, `runtime-api`, repository ports, projection query ports, trusted authority read port, `secure-byte-renderer` |
| `domain` | Entities、value objects、invariants、state reducer、policy result types | standard language primitives only |
| `runtime-core` | Agent loop、RunCoordinator、Working Context、budgets、recovery orchestration | `domain` and narrow ports |
| `skill-api` | Skill descriptors、Tool Proposal schema、normalization contracts | `domain` |
| `skill-runtime` | 将 proposal 规范化为 Envelope；注册 Skill operation validators | `skill-api`, `domain`, safety ports |
| `permission-engine` | risk analysis、Safe Read、historical/current constraint merge | `domain`, versioned policy definitions |
| `egress-guard` | sensitivity classification、local transformation、egress decision | `domain`, raw observation input port, safe-view output port |
| `provider-api` | vendor-neutral request、stream event、capability 和 error contracts | `domain`-safe DTO only |
| `provider-adapters` | 各 Provider wire format、streaming、retry hints | `provider-api`, network client |
| `ssh-adapter` | capability-limited unauthenticated transport preparation；ticket-scoped auth、exec、stream、delivery facts | authorized-execution API（separate preparation / execution capabilities）, credential lease port, SSH transport |
| `persistence-api` | repositories、event append、transactions、blob references | `domain` |
| `persistence-adapter` | relational store、event ledger、blob stores、projection checkpoints | `persistence-api`, platform storage |
| `projection-engine` | 从 persisted facts 生成统一 UI materialized views | read-only fact ports, projection write port |
| `credential-vault` | Android Keystore trust root、secret encryption、scoped lease | vault API, Android security adapter |
| `secure-byte-renderer` | exact bytes → deterministic safe tokens | byte primitives only |
| `background-execution` | visible continuation、notification、platform lifecycle bridge | `runtime-control-api`, Android platform APIs |
| `platform-android` | clock、connectivity、process lifecycle、Keystore、notification 等 Adapter | interfaces owned by inner modules |

这些是逻辑 / build-time 边界，不要求 v0.1 把每个模块变成独立进程或发布包。

### 5.2 Dependency graph

~~~mermaid
flowchart TD
    UI[presentation] --> APPAPI[application-api]
    UI --> PM[projection-models]
    UI --> REVIEWMODEL[TrustedPermissionReviewModel]

    APP[application] --> DOMAIN[domain]
    APP --> RAPI[runtime-api]
    APP --> PQUERY[projection-query-api]
    APP --> REVIEW[TrustedPermissionReviewAssembler]
    REVIEW --> AUTHREAD[trusted-review-authority-read-api]
    REVIEW --> RENDER[secure-byte-renderer]
    REVIEW --> REVIEWMODEL

    RUNTIME[runtime-core] --> DOMAIN
    RUNTIME --> SKILLAPI[skill-api]
    RUNTIME --> PERMAPI[permission-api]
    RUNTIME --> EGRESSAPI[egress-api]
    RUNTIME --> PROVIDERAPI[provider-api]
    RUNTIME --> EXECAPI[authorized-execution-api]
    RUNTIME --> PERSISTAPI[persistence-api]
    RUNTIME --> VAULTAPI[vault-api]
    RUNTIME --> BGAPI[background-api]

    SKILL[skill-runtime] --> SKILLAPI
    SKILL --> DOMAIN
    PERM[permission-engine] --> PERMAPI
    PERM --> DOMAIN
    EGRESS[egress-guard] --> EGRESSAPI
    EGRESS --> DOMAIN

    PADAPTER[provider-adapters] --> PROVIDERAPI
    SSH[ssh-adapter] --> EXECAPI
    SSH --> VAULTAPI
    PERSIST[persistence-adapter] --> PERSISTAPI
    PERSIST --> AUTHREAD
    PROJ[projection-engine] --> PQUERY
    PROJ --> PERSISTAPI
    VAULT[credential-vault] --> VAULTAPI
    BG[background-execution] --> BGAPI
    BG --> RAPI

    ANDROID[platform-android] --> PADAPTER
    ANDROID --> SSH
    ANDROID --> PERSIST
    ANDROID --> VAULT
    ANDROID --> BG
~~~

箭头表示 source module 可以依赖 target module。Outer Adapter 实现 Core 所拥有的 port；Core 不反向依赖 Adapter。

### 5.3 Forbidden dependencies

以下依赖必须由 build boundary、architecture test 或 API visibility 阻止：

- `domain` → Android、database、HTTP、SSH、Provider SDK、UI。
- `runtime-core` → Activity、ViewModel、Composable、Notification、具体 Provider / SSH / database class。
- `presentation` → SSH、Provider Adapter、Credential Vault secret API、raw Observation store。
- `presentation` → authoritative Envelope reader、raw `exactCommandBytes`、mutable SSHHost assembly、`secure-byte-renderer` invocation 或 `TrustedPermissionReviewModel` constructor。
- `provider-adapters` → raw Observation store、Credential secret store、SSH executor、Run repository writer。
- `skill-runtime` → network client、SSH transport、Run repository writer。
- `ssh-adapter` → mutable SSHHost repository、Permission Engine public decision API、Provider context。
- `projection-engine` → state transition writer、SSH、Provider、Vault secret read。
- `background-execution` → Run repository writer或直接执行 ToolCall。
- Persona / Character package → Skill binding、Permission rule、ExecutionTicket。

只有 application 内的 `TrustedPermissionReviewAssembler` 可以同时持有 authoritative Envelope read capability 和 Permission Review renderer capability。Presentation 只接收已完成的 tokens；普通 Projection 不持有 exact command、trusted target values 或可自行 decode 的 raw bytes。

### 5.4 Android independence

Domain 时间使用 `InstantValue` / injected clock，字节使用通用 byte sequence，取消使用 Runtime-owned cancellation contract。Android lifecycle、Coroutine、Service、Keystore、Room 等具体类型不出现在 Domain public API。

Runtime 可以在 Android process 内运行，但它通过 ports 使用：

- durable transaction；
- provider stream；
- authorized execution；
- credential lease；
- background visibility；
- clock / entropy / connectivity。

因此平台 Adapter 可以替换，Approved Domain 语义不变。

---

## 6. Capability Isolation and No-Bypass Construction

### 6.1 Skill has proposal authority, not execution authority

Skill 的能力分成两部分：

1. `SkillDefinition`：向模型描述 operation 与输入 schema。
2. `SkillNormalizer`：把完整、已验证的 Tool Proposal 规范化为 canonical Envelope candidate。

Skill 不持有 socket、SSH client、Credential lease 或 `RunEventWriter`。它不能执行自己提出的 action。

### 6.2 Execution requires an opaque one-time capability

SSH Adapter 的公开执行入口只接受：

~~~text
AuthorizedExecutionTicket
→ admit exactly one ticket-scoped SSH attempt
→ exact CredentialLease resolution and authentication
→ final authority validation
→ consume one-time SendStartPermit
→ execute ticket-embedded immutable wire plan
~~~

它不提供 `execute(command: String)`、`execute(envelope)` 或 `connect(hostId)` 这类可被旁路调用的 API。

只有 Runtime execution gate 可以签发进程内、不可序列化、一次性的 `AuthorizedExecutionTicket`。Ticket 不能被 UI、Provider 或 Skill 构造。Ticket 是请求 exact `CredentialLease` 并开始 SSH authentication 的唯一 capability；authentication success 本身不是 exec authority。

实际 exec request 还必须在 authentication 后重新进入最终 send-start coordination，由 Runtime 对同一个 Ticket、Fence、prepared transport、authenticated session 和 Run attempt 做 latest-authority validation，并签发仅供该次同步 send-start 使用的 ephemeral `SendStartPermit`。该 permit 不暴露给 Ticket 调用者，不能序列化、缓存或复用；它的签发也不替代实际 `SEND_STARTED` linearization。

### 6.3 Pre-authorization transport capability is intentionally weaker

SSH Adapter 可以有一个与 execution surface 分离的内部 preparation port，但它只接受 Runtime 签发的 process-local、一次性、non-execution `TransportPreparationPermit`：

~~~text
TransportPreparationPermit
→ TCP/socket to frozen address + port
→ SSH protocol handshake and Host Key acquisition
→ pinned Host Key verification
→ PreparedSshTransport (unauthenticated)
~~~

`TransportPreparationPermit`至少绑定Run / ToolCall attempt、owner epoch、Envelope / target digest、frozen resolved address / port、pinned Host Key identity、preliminary permission / Safe Read proof、safety epoch和短expiry。它只允许一次preparation attempt；不能改写target，也不能作为Ticket。

该 port 不接受 mutable `SSHHost`、credential、username authentication proof、exec channel 或 command payload。它不能 import Credential Vault lease API 或 exec-request API。`PreparedSshTransport` 是 opaque、短时、不可序列化的 technical handle，不是 Domain Entity、Runtime fact 或可池化连接；调用者看不到 socket，也没有 `authenticate` / `openExecChannel` / `send` 方法。

只有同时提交仍有效的 `AuthorizedExecutionTicket`，SSH Execution Gateway 才能 claim 该 handle 并进入 ticket-scoped authentication。任何直接持有 preparation permit 或 prepared handle 的代码都不具备 SSH authentication 或 command execution authority。

### 6.4 Egress cannot be bypassed

Raw Observation 与 Provider-safe Observation 使用不同 store、不同 reference type 和不同 read capability：

~~~text
RawObservationRef
  only readable by Egress Guard / trusted local viewer

ProviderObservationRef
  readable by ProviderContextBuilder
~~~

两种 reference 在类型和 persistence namespace 上均不可互换。Provider Adapter 只接受已经构建好的 `ProviderRequest`; 它没有任何 observation repository dependency。

---

## 7. Runtime Components and Ownership

| Component | Owns | Does not own |
|---|---|---|
| `RunCoordinator` | Run command ordering、state transition intent、attempt lifecycle、single active slot | Provider / SSH internals、UI |
| `RunStateReducer` | 合法转换的纯函数判定 | persistence、I/O |
| `WorkingContextBuilder` | Snapshot + inputs + events + safe observations 的 provider context | raw observations、Run state |
| `ModelInvocationSupervisor` | 单次 Provider call、stream normalization、cancel | Run transition、Tool approval |
| `ExecutionEnvelopeFactory` | trusted normalization、canonicalization、digest、frozen target | permission、send |
| `PermissionEngine` | risk / policy evaluation result | user decision、Run state、execution |
| `ExecutionGate` | preparation permit、T6-A Ticket issuance、T6-B latest-authority check与SendStartPermit | SSH handshake / auth / wire I/O |
| `AuthorizedExecutionGateway` | capability-limited pre-auth preparation；Ticket-scoped lease / auth；consume final permit并开始exact send | policy decision、mutable host lookup、session reuse |
| `ObservationPipeline` | raw capture、classification、transformation、safe view | Run transition |
| `VerificationEngine` | plan version policy、evaluator verdict | 模型自然语言结论 |
| `ProjectionEngine` | rebuildable UI / notification views | authoritative facts、side effects |
| `CredentialVault` | encrypted secret 与 scoped lease | logical host / workspace semantics |
| `BackgroundController` | FGS / notification lifecycle | Run truth、automatic replay |

### 7.1 Single-writer invariant

Run 的以下字段或等价事实只能由 `RunCoordinator` 提交：

- current state / phase；
- attempt sequence；
- active ModelInvocation / ToolCall reference；
- waiting reason；
- budget consumption；
- cancellation acceptance；
- reconciliation requirement；
- terminal transition。

其他组件的结果在被 `RunCoordinator` 接受前只是 `ExternalResult`，不是 Run fact。

### 7.2 State transition protocol

~~~text
RuntimeCommand or ExternalResult
→ RunCoordinator mailbox
→ load/check expected Run version
→ RunStateReducer.validate(...)
→ append facts + update checkpoint atomically
→ publish committed event ordinal
→ schedule next side effect
~~~

所有异步结果携带：

- `runId`
- `attemptId`
- `operationId`
- `expectedRunVersion`
- result kind

旧 attempt 或旧 version 的结果不能推进当前状态；它会作为 late result 审计，必要时仍更新 external-side-effect truth，但不会覆盖新状态。

---

## 8. Agent Loop and Runtime Orchestration

### 8.1 Start Run

`StartRunUseCase` 提交 `StartRunCommand`，由 Runtime 依次执行：

1. 验证 Task、Conversation、Agent 和 Task Workspace。
2. 检查全局 active Run slot。
3. 从历史 events 做 cross-run obligation preflight。
4. 创建 immutable Run Snapshot。
5. 在同一事务创建 Run、Snapshot、`RunCreated` event 并 claim active slot。
6. 若存在相关 unresolved obligation，首个 phase 为 `RECONCILING`；否则进入正常 planning。
7. 启动或绑定用户可感知的 Runtime host。

创建 Run 不代表已获得任何 execution permission。

### 8.2 Working Context

每次 ModelInvocation 的 Working Context 由以下输入构建：

~~~text
immutable Run Snapshot
+ ordered post-snapshot runtime inputs
+ committed Runtime events
+ current plan / budget / state
+ Provider-safe Observation views only
+ current runtime interaction request
= ProviderRequest context
~~~

Current Mandatory Safety Floor 和 cross-run obligations 用于限制 Runtime，不作为可由模型覆盖的普通 prompt instruction。

### 8.3 ModelInvocation lifecycle

~~~text
ModelInvocationPlanned
→ request metadata persisted
→ Provider stream starts
→ normalized text / tool deltas
→ complete response or normalized failure
→ result delivered to RunCoordinator
~~~

只有完整且 schema-valid 的 Tool Proposal 才进入 normalization。部分 JSON、截断 arguments 或 vendor-specific opaque data 不能创建 Envelope。

### 8.4 Tool proposal handling

~~~text
Untrusted Tool Proposal
→ Skill schema validation
→ operation-specific deterministic normalization
→ frozen SSH target resolution
→ canonical ExecutionEnvelope
→ Envelope / target digest
→ persist ToolCall + Envelope
→ Permission analysis
~~~

如果 proposal 无法确定、包含不支持的 shell behavior 或不能 canonicalize：

- 不创建可执行 ticket；
- 最多允许一次结构化 repair ModelInvocation；
- 仍无效则 PAUSED / FAILED，按上游 Failure Model 处理。

### 8.5 Permission wait and resume

- `ALLOW`：只表示 Permission analysis 不要求用户批准；执行前仍进入 Execution Gate。
- `ASK`：持久化 `PermissionRequest` 后进入 `WAITING_PERMISSION`。
- `DENY`：持久化理由，返回 replanning 或停止。

用户决定作为 append-only input 提交。Resume 时重新验证 request、Envelope digest、Run、expiry、current safety floor 和 target revision；旧 UI 卡片不能直接触发执行。

### 8.6 Tool execution and Observation

Permission / preliminary authority成立后，Runtime先签发non-execution preparation permit；SSH Adapter只完成frozen-target handshake与Host Key verification。T6-A重新gate后才产生一次性Ticket，Ticket只授权exact lease / authentication；auth后T6-B再次检查latest authority并为exact session签发一次性send permit，Gateway才开始immutable wire plan。SSH Adapter随后返回delivery facts、stdout / stderr bytes、exit status或uncertainty。所有output先进入本地Raw Observation pipeline；Provider下一轮只能得到独立的Provider-safe view。

### 8.7 Verification and completion

修改完成后，`RunCoordinator` 进入 `VERIFYING`，由 `VerificationEngine` 执行 versioned criteria。Verification Check 仍使用正常 Skill → Envelope → Permission → Execution → Egress 链。

只有所有 REQUIRED criteria 得到符合 trust model 的 PASS，`RunStateReducer` 才接受 `COMPLETED`。随后生成 `RUN_RESULT` Message 和 FinalReport projection。

### 8.8 Golden path

~~~mermaid
sequenceDiagram
    participant U as User/UI
    participant A as Application
    participant R as RunCoordinator
    participant P as Provider
    participant E as EnvelopeFactory
    participant G as ExecutionGate
    participant S as SSH Adapter
    participant O as Egress Guard
    participant V as VerificationEngine

    U->>A: Start execution Task
    A->>R: StartRunCommand
    R->>R: Persist Snapshot + RunCreated
    R->>P: Provider-safe ModelInvocation
    P-->>R: Complete Tool Proposal
    R->>E: Normalize and freeze target
    E-->>R: Envelope + Digest
    R->>R: Persist ToolCall + Envelope
    R->>R: Permission result / wait if ASK
    R->>G: Preliminary authority + preparation intent
    G-->>R: TransportPreparationPermit
    R->>S: Frozen socket + handshake + Host Key only
    S-->>R: Unauthenticated PreparedSshTransport
    R->>G: T6-A latest authority
    G-->>R: One-time AuthorizedExecutionTicket
    R->>S: Exact lease + ticket-scoped authentication
    S-->>R: Opaque bound authentication result
    R->>G: T6-B final authority
    G->>S: Internal SendStartPermit + exact session binding
    S-->>R: SEND_STARTED / NOT_STARTED then delivery facts
    S-->>R: Raw output + exit / uncertainty
    R->>O: Process raw Observation locally
    O-->>R: Provider-safe view / ASK / BLOCK
    R->>V: Execute Verification Plan
    V-->>R: Evidence + evaluator verdicts
    R->>R: Persist COMPLETED + FinalReport
    R-->>U: Unified projections update
~~~

---

## 9. Concurrency Model

### 9.1 Logical model

v0.1 使用一个进程内 `RunCoordinator actor` 作为 serialized executor：

- mailbox 一次处理一个 Run-relevant command / result；
- long-running Provider / SSH I/O 在受控 child operation 中异步执行；
- child operation 不写 Run，只把结果投回 mailbox；
- 每个 result 带 attempt/version fencing token；
- durable transaction 是状态变化的最终 linearization point。

这里描述逻辑模型，不锁定具体 coroutine 或 actor library。

### 9.2 Global one-active-Run

数据库有一个 technical coordination record `ActiveRunSlot`：

- 最多指向一个 non-terminal execution Run。
- claim 与 Run creation 在同一事务。
- release 只发生在 committed terminal transition 后。
- process recreation 通过新的 owner epoch reclaim 同一个 slot，而不是创建第二个 owner。

普通 Conversation Message 可以独立持久化和调用不带 execution tools 的聊天 ModelInvocation；它不能写 active Run，也不能因为与 Run 并发而获得 Skill。所有会影响 Run、Permission、SSHHost target 或 Runtime safety 的命令仍经过 Runtime serialized gateway。

### 9.3 UI actions and Runtime events

以下命令进入同一 serialized gateway：

- Start / Resume / Pause / Cancel Run；
- Guide this Run；
- Reply to RUN_QUESTION；
- Permission / Egress decision；
- Budget extension；
- active target 的 SSHHost mutation request；
- Runtime safety floor activation；
- Provider / SSH / Observation / Verification result。

普通 navigation、scroll、draft edit 和不影响 Run 的 Message 不进入 Runtime actor。

### 9.4 Long I/O and stale results

Actor 不在 mailbox 内等待网络完成。它先持久化 operation start fact，再派发 I/O。若其间发生 Cancel、process recreation 或新 attempt：

- result 仍按 external truth 记录；
- 只有匹配当前 `attemptId + expectedRunVersion` 的结果可驱动正常下一步；
- 任何表明命令可能送达的 late result 仍可创建 / 保持 UNKNOWN_OUTCOME obligation；
- stale result 不能把 CANCELLED Run 改回 RUNNING。

### 9.5 Process ownership

`RuntimeOwnerLease` 是 technical coordination metadata，不是新 Domain entity。它包含 process epoch 和 heartbeat / lifecycle evidence，用于防止旧 callback 写入。v0.1 不支持两个 App process 同时运行 Runtime；若平台组件意外创建第二 owner，数据库 compare-and-set 只允许一个 epoch 成功。

---

## 10. Race Semantics

### 10.1 Cancel vs Provider invocation

1. `CancelRun` 先在 actor 中追加 `CancelRequested`。
2. Supervisor 收到 best-effort cancel。
3. Provider 的 late stream 只能作为 cancelled invocation 的审计数据，不能产生新 Envelope。
4. 若没有 active SSH send，Run 可以提交 `CANCELLED`。

### 10.2 Cancel vs SSH execution

| Linearized point | Cancel behavior |
|---|---|
| 仅有 `PreparedSshTransport`，Ticket 尚未签发 | preparation handle invalid并关闭；不得请求 CredentialLease，不得发送 authentication proof / exec request；Run可CANCELLED |
| Ticket 已签发、authentication 尚未开始 | Cancel 原子 invalidate Ticket / `HELD_PRE_SEND` Fence；不创建 lease，关闭 transport并返回`NOT_STARTED` |
| authentication 正在进行或已成功，但 `SEND_STARTED` 尚未建立 | Cancel 不等待network auth；先linearize即invalidate Ticket / Fence，revoke lease并关闭 / poison session。即使late auth success到达，最终authority validation也必须拒绝exec并返回`NOT_STARTED` |
| final send-start coordination 正在竞争 | Cancel与最终authority validation / actual request write进入同一ordering；Cancel先行则不能签发 / 消费`SendStartPermit`，actual `REQUEST_WRITE_STARTED`先行则按`SEND_STARTED`处理 |
| `SEND_STARTED` 已先 linearize | best-effort关闭channel；按delivery certainty记录结果，必要时mutation进入UNKNOWN_OUTCOME并先保存obligation |
| 已收到可信 exit status | 保存真实结果与已发生效果，再停止后续步骤并 CANCELLED |

Cancel 不是“撤销远端命令”，也不等待可能较慢的SSH authentication完成。authentication不是command send；但认证成功的session一旦其Ticket失效，就必须立即关闭且永不进入连接池或被后续Ticket复用。内部可以有 `cancellation in progress` phase，但不新增或重定义 Approved Run state。

### 10.3 Permission Approve vs safety-floor update

- Approve 只追加绑定 Envelope digest 的历史决定，不签发长期 capability。
- current safety floor activation 与 execution gate 进入同一 serialized safety lane。
- Execution Ticket 签发事务读取当前 safety epoch，并应用 historical/current 的最严格合并。
- v0.1 中任何 `safetyEpoch` 变化都使旧 `TransportPreparationPermit` / `PreparedSshTransport` 不能直接沿用；必须在新epoch下重新prepare并重新gate。至少，更严格或不兼容的update必须如此。
- 更严格 safety update 若在 authentication 开始前 linearize，Ticket不得签发或既有Ticket invalid；不得请求lease或认证。
- 更严格 safety update 若在 authentication 期间或成功后、`SEND_STARTED` 前 linearize，不等待network I/O；它原子invalidate Ticket / Fence，Gateway关闭 / poison session，late auth result不能越过最终authority validation。
- 更严格 safety update 若与final send-start竞争，两者按与Cancel相同的ordering：update先行则无exec，actual `SEND_STARTED`先行则当前send不被追溯撤回。
- `SEND_STARTED` 若先 linearize，后续 policy update不能撤回已经开始发送的 bytes，但会约束后续 ToolCall、Observation egress、Verification和reconciliation。

### 10.4 SSHHost mutation vs execution

- target-defining mutation 使用 compare-and-set `sshHostRevision`。
- Execution Gate 要求当前 record revision / target digest 与 Envelope frozen reference 一致。
- Gate 成功时建立 `HELD_PRE_SEND` 的短期 `ExecutionFence`，并原子记录 `ExecutionCommitted`。
- Fence从T6-A commit持续到Ticket明确 `SEND_STARTED` 或 `NOT_STARTED`；此前冲突host mutation不能先linearize后仍让旧Ticket发送。
- host mutation若先linearize，revision / target proof改变使Ticket invalid；若`SEND_STARTED`先linearize，mutation随后可以提交但不能改变已开始执行的frozen target。
- SSH Adapter 只使用 Ticket 中 frozen address / port / username / fingerprint，永不重新查询 SSHHost。
- `SEND_STARTED` 之后 host record 可以改变，但不能改变已提交执行的网络目标；后续 Envelope 必须使用新 revision 并重新判定。

### 10.5 Permission decision vs stale screen

Permission Review 页读取 immutable Envelope authority。提交时携带 `permissionRequestId + envelopeDigest + expectedRequestVersion`。Application 不信任页面上的按钮状态；若 request 已解决、过期、safety floor 已使其 invalid、Run 已取消或 Envelope 被替换，提交失败并刷新 trusted state。

### 10.6 Cross-run obligation vs mutation

单 active Run 防止两个 mutation Run 并发，但不能代替历史检查。每次 mutating send 的 gate 都必须验证：

~~~text
obligation projection watermark == latest relevant fact ordinal
AND no unresolved potentially-conflicting obligation
~~~

Projection 缺失、滞后、损坏或无法证明完整时 fail closed；直接扫描 / 重建 authoritative events 后才能继续。

如果新的冲突 obligation fact 在 `SEND_STARTED` 前 linearize，Ticket中的obligation watermark / proof立即失效并阻止send。若send-start critical section已开始，则该fact writer必须参与同一serialization / fence：它要么先提交并使compare-and-consume失败，要么在`SEND_STARTED`之后提交并约束后续行为。

### 10.7 Send-start ordering invariant

> Any safety-relevant fact that linearizes before external `SEND_STARTED` must invalidate or block the Ticket.

至少包括：更严格Mandatory Safety Floor、CancelRun、active Run / attempt invalidation、冲突target mutation、新提交的冲突unresolved side-effect obligation和owner epoch变化。任何这些fact writer不得绕过send-start coordination domain。

SSH authentication success、authenticated session existence或`SendStartPermit` creation都不是`SEND_STARTED`。它们不能使已经先linearize的Cancel / safety / owner / attempt / target / obligation fact失效，也不能把旧session变成后续Run或Ticket的execution authority。

---

## 11. Execution Gate and TOCTOU Boundary

### 11.1 Mandatory send preflight

每次 SSH send，包含 Verification 和 Reconciliation 的 read-only check，都经过一个 `ExecutionGate`。mutating operation 的 gate 至少按下列顺序求值：

~~~text
1. active Run owner / attempt validation
2. Run state and cancellation validation
3. canonical Envelope + Digest revalidation
4. frozen SSH target + current host revision validation
5. historical frozen constraints
6. current Runtime Mandatory Safety Floor
7. Safe Read / risk result
8. PermissionRequest / Decision binding and expiry
9. cross-run unresolved obligation preflight
10. credential reference / exact rotation eligibility (secret-free metadata only)
11. Verification Plan binding, when applicable
12. persist TransportPreparationStarted intent/context and issue a non-execution TransportPreparationPermit
13. prepare TCP + SSH handshake + verified Host Key; no user authentication
14. validate PreparedSshTransport and enter short serialized T6-A
15. refresh all authority; require preparation context exact match
16. persist safety evaluation + ExecutionCommitted + HELD_PRE_SEND fence
17. issue one-time AuthorizedExecutionTicket with exact rotation version
18. Ticket claims prepared handle, resolves exact CredentialLease and authenticates
    outside any actor turn / database lock; Fence remains invalidatable
19. on auth success, enter short final send-start coordination T6-B
20. refresh all authority and exact prepared/authenticated session binding
21. compare-and-consume Ticket / Fence; issue internal one-time SendStartPermit
22. synchronously begin immutable execution wire plan
23. first actual execution-wire REQUEST_WRITE_STARTED establishes SEND_STARTED
24. transition Fence, then yield final send-start coordination
~~~

顺序中的 5–9 最终组合为最严格有效结果。任何一项 invalid / unknown 都不能降为 ALLOW。步骤13和18可以yield并允许Cancel / safety / owner / target / obligation facts正常linearize；因此步骤15和20都是强制的latest-authority refresh，不是重复优化。任一refresh失败都关闭transport / lease / session并返回`NOT_STARTED`，不能沿用旧prepared state重新尝试。

### 11.2 Send-Start Critical Section

从最终authority evaluation到真实外部send attempt开始，Runtime建立的是一个由durable Fence连接的logical coordination interval，不是一把跨SSH authentication持续持有的thread / actor / database lock。它包含两个很短的serialized turn和中间可取消、可被安全事实invalidate的network-auth interval：

~~~text
preliminary authority validation
→ TransportPreparationPermit
→ unauthenticated PreparedSshTransport                    (yieldable)

T6-A short serialized authorization turn
→ refresh Run / attempt / owner / safety / target / permission / obligation
→ validate prepared-handle bindings
→ durable ExecutionCommitted + HELD_PRE_SEND
→ issue AuthorizedExecutionTicket

outside serialized turn
→ Ticket resolves exact CredentialLease
→ ticket-scoped SSH authentication                       (yieldable)
→ Cancel / safety / owner / target / obligation may invalidate Fence

T6-B short non-yielding send-start turn
→ refresh latest authority + exact authenticated-session binding
→ compare-and-consume Ticket + HELD_PRE_SEND
→ issue internal one-time SendStartPermit
→ SSH gateway synchronously starts exact execution wire plan
→ first actual execution-wire REQUEST_WRITE_STARTED = SEND_STARTED
→ Fence becomes CONSUMED_SEND_STARTED
→ yield conflicting commands
~~~

这里的 `SEND_STARTED` 指immutable execution wire plan的第一次实际外部request发送尝试已经开始；通常是exec request，如果协议必须先发Envelope内已冻结的environment request，则以该first execution-wire request为界。它不是只创建future、排入普通异步queue、准备socket、发送authentication proof、authentication成功或只签发`SendStartPermit`。T6-B从final compare开始到同步transport报告`REQUEST_WRITE_STARTED`之前不得yield给冲突fact writer；若process在这个不可原子化的local/network窄窗口死亡，按Section 11.3 / 23保守恢复。

Gateway只有在满足以下两种结果之一时才返回：

- `SEND_STARTED(sendAttemptId)`：Ticket已原子消费，外部发送尝试确已开始。
- `NOT_STARTED(reason)`：没有开始外部发送；Ticket被永久消费 / revoked，Fence释放，若要重试必须重新gate并签发新Ticket。

Ticket签发前合法的transport操作只有：连接Envelope冻结的`resolvedAddress + port`、SSH protocol handshake、remote Host Key acquisition、pinned Host Key verification和构造短时`PreparedSshTransport`。Ticket签发前明确禁止：

- 请求或构造`CredentialLease`，或读取secret-bearing credential material；
- 发送password / private-key / agent-signature等SSH user-auth proof；
- 打开authenticated exec channel；
- 发送exec request、environment / stdin request或任意command bytes。

preflight可以读取Vault的secret-free credential metadata、auth policy和rotation eligibility，但不能得到可执行authentication的lease / signing handle。只有`AuthorizedExecutionTicket`可以请求绑定exact rotation的lease并授权一次SSH authentication；`CredentialLease`只授权该Ticket在该prepared transport上的auth operation，不授权exec。若Vault在Ticket签发后返回`USER_AUTH_REQUIRED`、credential unavailable或lease expiry，Gateway必须invalidate此次Ticket / Fence、关闭prepared transport并返回`NOT_STARTED`；用户恢复后从新preparation / gate开始。

所有能够产生安全相关fact的writer必须参与同一send-start coordination domain：

- Run / attempt / Cancel / obligation由RunCoordinator serialized turn排序。
- safety activation、SSHHost target mutation和owner epoch变更必须通过同一serialization，或使用能原子invalidate `HELD_PRE_SEND` Fence / Ticket的compare-and-consume protocol；它们不等待authentication network I/O。
- 若某fact已先linearize，Ticket的runVersion、safetyEpoch、target proof、obligation watermark或owner epoch至少有一项不匹配，send必须返回`NOT_STARTED`。
- authentication期间或成功后发生的invalidation会关闭 / poison session；late success不能被T6-B接受。
- 一旦`SEND_STARTED`先linearize，Fence即可释放 / 转为active execution tracking；不锁住整个SSH network operation。

具体使用non-yielding actor、database compare-and-consume或显式send-start Fence由ADR / Implementation Plan决定，但不能把authentication塞进持锁事务，也不能改变上述ordering semantics。

### 11.3 Linearization points and fence lifecycle

`ExecutionCommitted` 是durable authorization linearization point，不代表bytes已经发送；`SEND_STARTED` 是外部副作用send-start linearization point。

~~~text
ExecutionFence lifecycle

NONE
→ HELD_PRE_SEND              (T6-A commit; covers ticket-scoped auth)
→ CONSUMED_SEND_STARTED      (actual send attempt began)
  or RELEASED_NOT_STARTED    (proved no send began)
  or STALE_OWNER_EPOCH       (process / owner died)
~~~

- Ticket不可序列化、不可复制、短时有效且只能admit一个Gateway attempt；不能被第二次调用或并行auth复用。
- Ticket只由刚完成T6-A的同一RunCoordinator attempt使用，并绑定一个exact `PreparedSshTransport`。
- `HELD_PRE_SEND` 阻止第二次send commitment，并协调Cancel、safety update、target mutation、new obligation和final send-start；它在auth期间可被原子invalidate，不阻塞这些writer。
- T6-B签发的`SendStartPermit`只绑定同一个ticketId、preparedTransportId、authenticatedSessionId、attempt和Fence generation；仅可在当前non-yielding turn内消费一次。
- `CONSUMED_SEND_STARTED` 后使用独立active execution attempt跟踪整个SSH operation；pre-send Fence不再锁住其他Runtime commands。
- `RELEASED_NOT_STARTED` 的Ticket、lease、prepared transport和authenticated session永不复用。
- `STALE_OWNER_EPOCH` 只能由recovery处理，不能重新生成原Ticket。

Authenticated SSH session严格ticket-scoped：Ticket因Cancel、safety update、attempt / owner / target变化、expiry或任何authority mismatch而invalid时，session立即close / poison，绝不进入connection pool，也不能被新Ticket重新绑定。v0.1不提供“保留已认证session再重新授权”的优化；新Ticket必须从新`PreparedSshTransport`和新auth开始。

如果进程在coordination interval中死亡，旧preparation handle、Ticket、CredentialLease、authenticated session和`SendStartPermit`永久丢失。恢复检查ExecutionCommitted、Fence state和transport delivery evidence；无法证明`NOT_STARTED`时保守进入UNKNOWN_OUTCOME / reconciliation。若executor能以本地、不可争议证据证明exec request从未开始发送，才可追加`CONFIRMED_NOT_SENT`。

这仍无法消除网络系统在send-start之后的delivery uncertainty，因此UNKNOWN_OUTCOME继续是必要安全语义。

### 11.4 Safety epoch

Mandatory Safety Policy 使用单调增加的 `safetyEpoch`：

- Run Snapshot 保存历史 version 与 resolved constraints。
- 当前安装 Runtime 暴露 current epoch / version。
- 每次 gate 保存两者、合并结果、validator versions 和理由。
- 历史 constraint 解析失败时 DENY / ASK / PAUSED。
- current rule 放宽时仍保留历史更严格约束。
- v0.1对任何safetyEpoch mismatch都保守invalidate pre-authorization preparation和pre-send Ticket；新epoch下必须重新prepare / gate。更严格update绝不能只替换Ticket字段后沿用旧transport / auth session。

Safety update 不回写 Snapshot，也不覆盖旧 gate fact。

### 11.5 ExecutionTicket contents

Ticket 至少绑定：

- `ticketId`（随机、一次性、仅内存）；
- `runId`, `toolCallId`, `attemptId`, `expectedRunVersion`, `ownerEpoch`；
- `executionEnvelopeId`, `envelopeDigest`；
- immutable SSH wire plan；
- `targetDigest`；
- exact `preparedTransportId` + preparation context digest；
- permission proof 或 Safe Read proof；
- historical policy version + exact current `safetyEpoch`；
- obligation preflight watermark / proof；
- credential reference 和 execution-time 已解析的 exact rotation version；
- allowed auth method / purpose，且只能为该Ticket请求一个scoped CredentialLease；
- issued monotonic time / short expiry；
- `ExecutionFence` ID / generation / expected `HELD_PRE_SEND` state。

Ticket 不是 PermissionDecision，不持久化 secret，也不能跨 process recovery 复用。它授权exact credential lease resolution和一次ticket-scoped authentication，但不单独授权exec send。

内部`SendStartPermit`至少绑定`ticketId + preparedTransportId + authenticatedSessionId + runId + attemptId + expectedRunVersion + ownerEpoch + envelopeDigest + targetDigest + sshHostRevision + permission proof version + safetyEpoch + obligation watermark + Fence generation`，具有立即失效的monotonic expiry。它不是Domain Entity或持久化fact，不能离开SSH gateway / final execution gate边界。

---

## 12. Persistence Architecture

### 12.1 Persistence model

v0.1 使用 hybrid persistence：

1. **Authoritative Domain tables**：身份、配置、关系和 immutable value objects。
2. **Append-only Runtime Event Ledger**：输入、状态变化、外部交付和安全判断的有序事实。
3. **Encrypted content / evidence stores**：大体积或敏感 bytes，只由 typed reference 指向。
4. **Rebuildable materialized projections**：为 Timeline、Task Card、Active Run、Needs Attention、Activity 和 Notification 服务。
5. **Technical coordination records**：ActiveRunSlot、owner epoch、ExecutionFence、projection checkpoint。

它不是“所有配置都 Event Sourcing”。但是凡是影响副作用、审批、恢复、安全或审计的 Runtime 事实，都必须 append-only。

`TransportPreparationPermit`、`PreparedSshTransport`、`AuthorizedExecutionTicket`、`CredentialLease`、authenticated session和`SendStartPermit`全部是process-local ephemeral capability / handle，不进入上述持久化模型。Persistence只记录其authority / delivery相关facts、IDs / digests / versions和Fence，不序列化可复用能力。

### 12.2 Sources of truth

| Fact category | Authoritative source | Never authoritative |
|---|---|---|
| Agent / Persona / Conversation / Workspace configuration | normalized Domain tables + revision history required by Snapshot refs | UI cache |
| Run creation configuration | immutable RunSnapshot | current Agent / SSHHost record |
| Run inputs and transitions | ordered Runtime Event Ledger + immutable typed records | Saved State / notification |
| exact approved action | ExecutionEnvelope bytes + digest + Permission records | rendered text / model proposal text |
| remote delivery certainty | SSH delivery events / ToolCall terminal facts | exit-code inference absent facts |
| unresolved side-effect obligation | UNKNOWN_OUTCOME source facts + reconciliation facts | deletable obligation projection alone |
| raw output | encrypted Raw Observation blob + digest | live terminal widget |
| Provider-visible output | separate Provider-safe blob + transformation facts | raw ref |
| Verification result | criterion/check/evidence/evaluator/verdict records | RUN_RESULT prose |

> Event/source facts 是安全事实来源。Projection 只能加速查询，不能创造、削弱或删除安全事实。

### 12.3 Authoritative Domain tables

至少包含：

- `agent`
- `persona`
- `provider_profile`
- `ssh_host`
- `workspace`
- `credential_reference`
- `conversation`
- `conversation_timeline_anchor`
- `message`
- `conversation_summary`
- `task`
- `run`
- `run_snapshot`
- `plan_version`
- `plan_step`
- `model_invocation`
- `tool_call`
- `execution_envelope`
- `permission_request`
- `permission_decision`
- `observation_metadata`
- `egress_decision`
- `verification_plan_version`
- `verification_criterion`
- `verification_check`
- `verification_record`
- `final_report`

这与 Approved Baseline 的实体保持一致。`ExecutionEnvelope` 仍是 ToolCall 内部 immutable value object；单独表只是 persistence layout，不把它提升为新的产品顶层实体。

### 12.4 Runtime Event Ledger

每个 event envelope 至少包含：

- globally monotonic `eventOrdinal`；
- `eventId`；
- optional `runId`, `taskId`, `conversationId`, `toolCallId`；
- per-Run `runSequence`；
- `eventType` 和 `schemaVersion`；
- causal command / attempt / prior event references；
- structured payload 或 typed record reference；
- wall-clock timestamp 和 monotonic ordering metadata；
- writer owner epoch；
- content digest，如适用。

事件只追加，不原地改写。需要纠正时追加 correction / superseding fact，并保留原事实。

### 12.5 Post-snapshot runtime inputs

本架构选择把 runtime input 作为 Event Ledger 中一类明确的 typed fact，而不是修改 Snapshot：

- `RunQuestionAnswered`
- `RunGuidanceSubmitted`
- `PermissionDecisionSubmitted`
- `EgressDecisionSubmitted`
- `CredentialRecoveryCompleted`
- `BudgetExtended`
- `ResumeRequested`
- `CancelRequested`

每个 input 保留 source surface、destination IDs、expected version、user-auth evidence（如需要）和接受 / 拒绝结果。Input 被消费不代表删除；通过 subsequent event 记录消费关系。

### 12.6 Blob separation

至少使用三个逻辑 namespace：

| Store | Content | Readers |
|---|---|---|
| `raw-observation-store` | stdout/stderr/evidence 原始 bytes | Egress Guard、受信任本地 viewer、reconciliation / evaluator when allowed |
| `provider-safe-store` | 已脱敏 / 提取 / 本地安全摘要 bytes | WorkingContextBuilder、Provider request audit viewer |
| `general-content-store` | Message、FinalReport、非秘密大对象 | Domain / Presentation authorized readers |

Raw 与 safe reference 具有不同 type tag、key space 和 API。Event payload 不内嵌 raw output、private key、token 或 password。

### 12.7 Current Run state checkpoint

`run.currentState`, `run.version` 和当前 references 是 transactionally maintained checkpoint，用于高效 claim 和恢复。Run Event Ledger 可以重演验证它；若 checkpoint 与 ledger 不一致：

- 停止执行；
- 从 committed facts 重建；
- 保存 recovery diagnosis；
- 未证明安全前不得发送 ToolCall。

UI 不把 checkpoint 单独当作完整审计来源。

### 12.8 Retention and erase

- Projection 可删除和重建。
- raw observations 按用户数据策略和容量限制清理，但若仍被 unresolved obligation 或 required Verification evidence 引用，不能让清理造成虚假 resolved / PASS；可以转为保留最小加密 evidence 或让状态保持 INCONCLUSIVE。
- Close Task、Archive Conversation / Agent 不删除 Runtime events。
- Erase 操作必须先检查 unresolved obligation；若用户仍要求本地擦除，UI 必须说明远端不确定性不会被“解决”，并按 Approved UX / Baseline 生命周期规则执行。

---

## 13. Transaction Boundaries

### 13.1 Transaction principles

1. **Persist before side effect**：任何外部调用前先持久化 intent / start fact。
2. **Fact and checkpoint together**：Run transition event 与 current checkpoint 同事务。
3. **Approval and action separate**：PermissionDecision 事务不执行 SSH。
4. **Raw and safe refs separate**：safe view 只能在 transformation fact 与 blob 完成后发布。
5. **Terminal outcome and obligation atomic**：UNKNOWN_OUTCOME 与 obligation source facts 同一事务。
6. **Projection after fact**：Projection 失败不回滚已经提交的安全事实。

### 13.2 Required transactions

| Tx | Atomic writes / checks | Side effect after commit |
|---|---|---|
| T1 Create Run | active slot empty、Run、Snapshot、RunCreated、owner epoch | schedule coordinator |
| T2 Start ModelInvocation | invocation row、provider-safe request ref、InvocationStarted | Provider HTTP stream |
| T3 Complete Tool Proposal | ToolCall PROPOSED、canonical Envelope、target/envelope digests、ProposalNormalized | Permission analysis |
| T4 Ask Permission | PermissionRequest、Run WAITING_PERMISSION event/checkpoint | trusted UI / notification projection |
| T5 Record Decision | append-only runtime input、PermissionDecision、request resolution | resume mailbox |
| T6-P Transport preparation | preliminary authority checks；append secret-free TransportPreparationStarted intent with run / attempt / owner / Envelope / target / safety / permission / obligation context digest | issue non-execution permit；TCP + handshake + Host Key verification only；result handle remains ephemeral |
| T6-A Execution authorization | 已有未认证PreparedSshTransport；短serialized turn内refresh versions、host revision、safety floor、permission、obligation watermark、credential metadata eligibility和preparation bindings；提交ExecutionCommitted + HELD_PRE_SEND Fence | issue Ticket；随后在无actor / DB lock下resolve exact lease并authentication；Fence可被invalidate |
| T6-B Final send start | authentication完成后短serialized turn内refresh全部authority并校验exact session / Ticket / Fence binding；compare-and-consume并创建ephemeral SendStartPermit | 同一non-yielding turn内同步开始exec request；返回actual SEND_STARTED或NOT_STARTED后yield |
| T7 Stream Observation | chunk metadata / sequence、encrypted raw blob commit | local UI projection and Guard |
| T8 Tool outcome | delivery facts、exit/UNKNOWN_OUTCOME、ToolCall terminal; if unknown also obligation source facts | next loop / reconciliation |
| T9 Egress | sensitivity、policy version、decision、transformation digest、Provider-safe ref | Context Builder may read safe ref |
| T10 Reconciliation | source obligation ref、check evidence、result event、resolved status fact when proven | retry / verification |
| T11 Verification | plan/criterion/check/evidence/evaluator/verdict versioned records | state evaluation |
| T12 Terminal Run | validated terminal event、Run checkpoint、FinalReport ref、active slot release | RUN_RESULT / projections |
| T13 Cancel | CancelRequested; after delivery outcome, RunCancelled and any obligation facts | stop host / notification update |

### 13.3 Crash points

每个 side-effect transaction 必须可区分：

- intent 未提交：视为从未开始。
- intent 已提交但没有外部 delivery fact：恢复时不得自动发送；根据 operation risk 和 local transport evidence，PAUSED 或 UNKNOWN_OUTCOME。
- 只有T6-P TransportPreparationStarted而没有ExecutionCommitted时，按定义不可能已发送credential / execution-wire request；ephemeral handle丢失并PAUSED，不建立command-side UNKNOWN_OUTCOME。
- `ExecutionCommitted + HELD_PRE_SEND` 在owner death后不会恢复原prepared handle、Ticket、lease或authenticated session；先reconcile stale Fence，只有可信`NOT_STARTED` evidence才按未发送处理，否则mutation fail closed为UNKNOWN_OUTCOME。
- delivery fact 已提交但 terminal 未提交：mutation 保守 UNKNOWN_OUTCOME。
- terminal fact 已提交但 Projection 未更新：重建 Projection，不重做 action。

### 13.4 Projection consistency watermark

Projection row 保存其消费的 `eventOrdinal`。安全 gate 需要 projection 时必须同时读取：

- latest committed relevant ordinal；
- projection checkpoint；
- source fact references / proof。

Watermark 不匹配时不能将“未查到 obligation”解释为“没有 obligation”。

### 13.5 Encrypted blob commit protocol

Database transaction 无法与文件系统 write形成真正的跨存储原子事务，因此 raw / safe / evidence blob采用 write-before-reference protocol：

1. 在受控临时位置流式加密并计算 plaintext / ciphertext所需digest与length。
2. flush并完成authenticated-encryption tag。
3. 以不可变content ID原子发布到final blob namespace；同一ID重复发布幂等。
4. 随后的database transaction写入metadata、typed reference和Runtime fact。
5. crash发生在第3步后、第4步前只产生unreferenced orphan，可安全垃圾回收。
6. database永不先提交一个尚未成功发布的blob reference。

删除使用反向顺序：先追加retention / tombstone fact并确认无obligation、Verification或audit引用，再删除blob。发现metadata引用缺失blob时fail closed：相关reconciliation / Verification保持INCONCLUSIVE，不能伪造空内容。

---

## 14. Projection Architecture

### 14.1 Unified flow

~~~text
Authoritative Domain rows + committed Runtime events
→ Projection Engine
→ versioned materialized views
→ Presentation query ports
→ Home / Timeline / Run Detail / Activity / Notification
~~~

各页面不自行组合 Run state、Permission 和 ToolCall 产生第二套逻辑。

### 14.2 Projection sets

| Projection | Consumers | Key facts |
|---|---|---|
| `ConversationTimelineProjection` | Conversation Timeline | Message anchors、stable Task anchors、Task current summary |
| `TaskCardProjection` | Timeline、Task detail | latest Run、Plan summary、Tool / Verification state、result |
| `ActiveRunProjection` | Active Run Strip、Home、header | sole active Run、phase、progress、waiting reason |
| `NeedsAttentionProjection` | Home、Activity、badge | permission、RUN_QUESTION、egress ASK、reconciliation、credential recovery |
| `ActivityProjection` | Activity | authoritative chronological execution/audit summaries |
| `PermissionReviewProjection` | Permission entry surface | request identity + authoritative Envelope reference only；exact bytes、frozen target和trusted parameters不复制 |
| `RunDetailProjection` | Run Detail | Snapshot refs、attempts、events、Plan、ToolCall、Verification |
| `NotificationProjection` | Android notification | user-safe state / CTA / deep link identifiers |
| `CrossRunObligationProjection` | safety preflight、Activity、Needs Attention | unresolved source facts、conflict scope、watermark |

### 14.3 Permission Review projection boundary

`PermissionReviewProjection` 只负责让 Needs Attention / Timeline / Activity 定位到一个待处理 request。打开审批页时使用：

~~~text
PermissionReviewProjection(requestId, envelopeAuthorityRef)
→ PermissionReviewUseCase
→ TrustedPermissionReviewAssembler
→ authoritative read + secure rendering
→ ephemeral TrustedPermissionReviewModel
~~~

Projection 被删除、滞后或重建不会复制 / 改写 command bytes。Projection 中的 label 不能成为可批准内容；Assembler 找不到、验证不了或发现 request 已 stale 时，只返回不可批准的 stale / invalid trusted model。

### 14.4 Stable Task Card

Task 创建时插入稳定 Timeline anchor。后续 Run / Plan / ToolCall / Verification events 更新其 projection 内容，不追加伪 Message，也不改变 anchor identity 或原 Timeline 位置。

### 14.5 Idempotency and rebuild

- Projection reducer 对 `eventId` 幂等。
- 每个 projection 记录 schema version 与 checkpoint。
- App / schema upgrade 可以删除 materialized rows 并从 authority 重建。
- Notification 和页面共享同一 projection contract；Notification 不自行推断“需要批准”。
- Permission action、Cancel、Retry 等提交仍回到 authoritative Application validation，不信任 projection freshness。

### 14.6 Rebuild safety

Cross-run obligation projection 是安全索引但不是安全事实源：

- 删除它不会写入任何 `ObligationResolved` fact。
- 重建从所有相关 UNKNOWN_OUTCOME 与 Reconciliation facts 开始。
- 重建未完成时 mutating gate fail closed。
- 只有明确的 `CONFIRMED_EXECUTED` / `CONFIRMED_NOT_EXECUTED` reconciliation fact 能使相应 obligation projection resolved。

### 14.7 Delivery model

Projection 可以 eventual-consistent，但必须满足：

- commit 后 UI 最终收敛；
-同一事实的多个 surface 使用同一 reducer 语义；
- security action always revalidates authority；
- active Run / Needs Attention 变化优先投影，避免用户看见矛盾 CTA；
- App 恢复先显示 persisted projection，再以 authority watermark 校验并修正。

---

## 15. Composer and Input Routing

### 15.1 Application destination types

三种输入不是 UI mode，而是明确的 application value object：

~~~text
ConversationMessageDestination {
  conversationId,
  expectedConversationRevision
}

RunGuidanceDestination {
  conversationId,
  taskId,
  runId,
  expectedRunVersion
}

RunQuestionReplyDestination {
  conversationId,
  taskId,
  runId,
  questionId,
  expectedQuestionVersion,
  oneTimeReplyNonce
}
~~~

UI chip 只是 destination 的 Projection。真正的提交 command 必须包含完整 destination，不从当前 screen 或“是否存在 active Run”临时猜测。

### 15.2 Normal Conversation Message

提交验证：

- Conversation 存在且可写；
- 当前 route 与 destination 不要求一致，但用户必须明确保存过该 destination；
- content 只创建普通 Message；
- 即使有 active Run，也不追加 Run input，不修改 Plan，不回答 RUN_QUESTION；
-普通聊天 ModelInvocation 不暴露 SSH execution tool。

### 15.3 Guide this Run

提交验证：

- destination Run 仍是当前匹配的 active attempt；
- Run 处于允许接受 guidance 的非终态；
- conversation/task/run 关系完全匹配；
- expected version 未被取消、Retry 或恢复替换。

成功后追加 `RunGuidanceSubmitted` runtime input，由后续 Working Context 消费。它不是普通 Message，也不能自动成为 permission。

### 15.4 Reply to RUN_QUESTION

提交验证：

- question 仍 open 且属于 destination Run；
- Run 正在等待该问题或仍明确接受该 reply；
- nonce 尚未消费；
- expected question version 匹配。

成功后原子追加 answer input 并标记 question consumed。旧页面重复提交被幂等拒绝，不会降级发送成普通 Message。

### 15.5 Draft persistence

Draft key 是 `destinationType + destinationIdentity`，不是只有 Conversation ID：

- 切换 Conversation 不改变 destination。
- process recreation 恢复 draft 时重新验证 destination。
- Run 终态、question 失效或 Retry 后，draft 进入 `stale destination`，保留文本但禁用直接 Send。
- 用户可以显式选择“作为普通消息发送”，这会创建新的 destination；系统不能静默 retarget。
-敏感 credential 输入不使用 Composer draft store。

### 15.6 State changes during editing

| Change | Composer result |
|---|---|
| active Run changes | Guide draft stale；不发送到新 Run |
| permission appears | 普通 composer 可见但不能代替 Permission action |
| RUN_QUESTION resolved elsewhere | reply draft stale；保留内容 |
| Conversation switch | 当前 destination draft 隔离保存 |
| process death | 只恢复 destination IDs 和非敏感 draft；提交再验证 |

---

## 16. Provider Architecture

### 16.1 Provider Adapter contract

Vendor-neutral `ProviderAdapter` 提供：

- `probeCapabilities(profile, model)`；
- `startInvocation(normalizedRequest, cancellation)`；
- ordered stream of normalized events；
- best-effort `cancel(invocationId)`；
- normalized usage / limits / errors。

Runtime 不依赖 OpenAI、Anthropic、Google、OpenRouter 或自定义 OpenAI-compatible message shape。

### 16.2 Normalized request

`ProviderRequest` 只包含：

- provider / model snapshot identity；
- system-owned policy instructions；
- ordered Conversation / Run context blocks；
- Skill schemas approved for this Run；
- Provider-safe Observation blocks；
- output contract / tool schema；
- token and time budgets；
- invocation correlation ID。

它不包含：

- Credential secret；
- RawObservationRef；
- mutable SSHHost lookup capability；
- Permission decision capability；
- execution ticket。

### 16.3 Normalized stream events

Adapters 把 vendor stream 转为：

- `TextDelta`
- `StructuredReasoningMetadata`，若 Provider 合法提供且产品允许保存
- `ToolProposalStart`
- `ToolProposalArgumentsDelta`
- `ToolProposalComplete`
- `UsageUpdate`
- `InvocationCompleted`
- `InvocationFailed`
- `InvocationCancelled`

只有 `ToolProposalComplete` 的完整 bytes 经过严格 parser 后才能进入 Skill normalization。展示中的 streaming text 不能执行。

### 16.4 Capability probe

Probe 输出版本化 capability：

- structured tool calls；
- streaming；
- max context / output；
- supported content forms；
- cancellation / idempotency hints；
- adapter-specific limitations。

结果按 `ProviderProfile revision + modelId + adapterVersion` 缓存。Probe 失败时不能假设支持 tools；执行型 Run WAITING_USER / configuration error，普通 chat 可按明确的 non-tool fallback 运行。

### 16.5 Context limit

`ContextBudgeter` 在发送前计算：

1. mandatory system / safety blocks；
2. Task goal 与 immutable Snapshot facts；
3. current runtime inputs / events；
4. relevant Provider-safe observations；
5. Conversation messages / summary cache。

安全与当前执行事实不能被 summary 淘汰。ConversationSummary 仍是可重建有损缓存。Overflow 先确定性裁剪低优先 context / 生成新 summary；最多按 Baseline 重试一次，不能删除 Permission、unknown obligation 或 success criteria 来“塞进上下文”。

### 16.6 Retries

| Failure | Adapter / Runtime behavior |
|---|---|
| auth / invalid key | normalized AUTH；不自动 retry，WAITING_USER |
| rate limit / transient 5xx | bounded backoff，记录 attempt |
| connection failure before response | bounded retry where safe |
| stream interrupted after partial text | partial invocation terminal；新 invocation，不拼接成可信 Tool Proposal |
| malformed Tool Proposal | one structured repair attempt |
| context overflow | bounded context reduction then one retry |

Provider retry 不会重放 SSH action，因为 Tool execution 是独立 boundary。

### 16.7 Cancellation

Cancel 关闭 Provider stream并使 invocation attempt 失效。Vendor 不支持 cancellation 时丢弃 late result。Adapter 不能因为收到 late Tool Proposal 而绕过 RunCoordinator。

### 16.8 Custom providers

自定义 Provider 使用同一 Adapter contract：

- Base URL、model 和 capability 属于 Run Snapshot。
- API credential 由 CredentialReference / Vault 提供 scoped HTTP auth material，不进入 request log。
- 默认要求 TLS；本地开发或私有网络的 cleartext exception 若未来提供，必须是显式高风险配置，不由模型打开。
- Adapter conformance tests 决定其能否用于 execution Run；“OpenAI-compatible”标签不等于行为完全兼容。

### 16.9 Normalized errors

至少包含：

- AUTH
- RATE_LIMIT
- TRANSIENT_NETWORK
- PROVIDER_UNAVAILABLE
- INVALID_REQUEST
- CONTEXT_LIMIT
- CAPABILITY_UNSUPPORTED
- MALFORMED_STREAM
- MALFORMED_TOOL_PROPOSAL
- CANCELLED
- TIMEOUT
- UNKNOWN_PROVIDER_ERROR

Error 包含可安全展示的 metadata，不内嵌 Authorization header 或 raw response secret。

---

## 17. Observation and Egress Architecture

### 17.1 Pipeline

~~~mermaid
flowchart LR
    SSH[SSH stdout/stderr bytes] --> RAW[(Encrypted Raw Observation Store)]
    RAW --> SRC[Source-aware Classification]
    SRC --> SCAN[Local Deterministic Scanner]
    SCAN --> DECIDE[Egress Policy]
    DECIDE -->|NORMAL| CLEAN[Baseline Cleaning]
    DECIDE -->|SENSITIVE| TRANSFORM[Local Redaction / Extraction]
    DECIDE -->|ASK| ASK[Metadata-only User Decision]
    DECIDE -->|SECRET / unsafe| BLOCK[Block]
    CLEAN --> SAFE[(Provider-safe Store)]
    TRANSFORM --> SAFE
    ASK -->|Scoped approval + safe transform| SAFE
    SAFE --> CTX[WorkingContextBuilder]
    CTX --> PROVIDER[Provider Adapter]
~~~

### 17.2 Classification stages

1. **Pre-content source classification**：operation、path、command shape、workspace label。例如 `.env`、private key、database dump 在读取前就至少 SECRET / high-risk。
2. **Byte-level local scan**：常见 token / key pattern、Authorization header、private-key marker、password fields、binary / encoding anomaly、控制字符。
3. **Structured parser**：仅对 Runtime 明确认识的 command output 使用版本化 parser。
4. **Uncertainty rule**：不能可靠判断时至少 SENSITIVE。

这些步骤全部在本地完成，raw bytes 不先发给 Provider。

### 17.3 v0.1 SUMMARIZE semantics

v0.1 不引入需要把 raw SENSITIVE 文本先发送到云端的 general LLM summarizer。`SUMMARIZE` 在系统架构中的安全含义限定为本地确定性 transformation，例如：

- 只保留已知结构化字段与计数；
- 允许列表式 extraction；
- 将匹配秘密替换为固定占位符并记录数量；
- 对 known log format 仅保留时间、severity、component 和经过清洗的 error code；
- 对长输出产生 byte / line count、exit status、bounded safe excerpts；
- 对无法安全转换的内容降级为 ASK 或 BLOCK。

若无法证明 safe transformation 不暴露 SECRET，就不能生成 Provider view。UX 中可以显示“已在本地提取安全摘要”；不能暗示云端已经读取原文。

### 17.4 Decision rules

| Sensitivity | Raw Provider egress | v0.1 safe actions |
|---|---|---|
| NORMAL | 基础清洗后可允许 | ALLOW / length bound |
| SENSITIVE | 默认不直接发送 | deterministic REDACT / EXTRACT / ASK / BLOCK |
| SECRET | hard BLOCK | metadata-only fact；用户不能用普通 ASK 绕过 known-secret block |

Execution permission 与 Egress decision 使用不同 request、不同 UI、不同 event type 和不同 digest binding。

### 17.5 ASK binding

用户可决定的 egress request 必须绑定：

- raw observation digest，而不是只绑定 ToolCall；
- sensitivity category；
- proposed transformation version；
- destination ProviderProfile / model；
- exact fields / byte ranges category，不显示或发送被 hard-block 的 secret；
- one-time / scoped expiry。

Provider 或 model 变化使决定失效。

### 17.6 Store isolation

- `RawObservationReader` interface 只授予 Egress Guard、trusted local evidence viewer 和明确 evaluator。
- `ProviderContextBuilder` 编译时只能依赖 `ProviderSafeObservationReader`。
- Provider Adapter 接收 materialized safe block，不接收任何 store reader。
- Projection 可以显示 raw output 的本地受控 rendering，但不能把其 ref 交给 Provider path。
- crash / analytics logger 只记录 observation ID、size、sensitivity 和 status，不记录 content。

### 17.7 Prompt injection handling

任何 server output 在 Provider context 中都被标为 untrusted data：

- 使用结构化 data block，不与 System instruction 拼接。
- 删除 ANSI / control effects；保留必要 byte evidence ref。
- Runtime 不执行 output 中的“请运行以下命令”；只有下一次完整模型 Tool Proposal 才能进入 Permission path。
- Prompt injection classifier 可以提高 sensitivity / warning，但不能单独替代 Permission Engine。

### 17.8 Evidence and local viewer

Verification Evidence 可以在本地引用 raw bytes；进入模型前仍经过同一 Guard。Provider-safe Evidence 不覆盖 raw evidence ref 或 digest。受信任本地 viewer 使用安全 byte / text renderer，不能直接交给 ANSI terminal widget。

---

## 18. Permission and ExecutionEnvelope Architecture

### 18.1 Trusted normalization boundary

模型只产生 `ToolProposal`。`ExecutionEnvelopeFactory` 是 trusted Core，负责：

1. 验证 Skill / operation 和 arguments schema。
2. 拒绝不支持、ambiguous 或无法安全编码的输入。
3. 从 Run Snapshot / Task target 解析 Workspace。
4. 读取当时的 SSHHost revision 并冻结实际 target value。
5. 解析单一 network address；不把未来 DNS lookup 留给 executor。
6. 生成最终 wire command bytes 和全部 execution parameters。
7. 使用 versioned canonical encoder 生成 target / envelope digest。
8. 持久化 immutable value object。

模型无法提交 `risk=READ` 来获得 ALLOW；risk 是 Permission Engine 对 canonical Envelope 的结果。

### 18.2 Canonical Envelope schema

Execution semantic content 至少包含：

~~~text
ExecutionEnvelope {
  envelopeSchemaVersion
  canonicalEncodingVersion
  runId
  toolCallId
  skillId
  skillVersion
  operation
  sshTarget {
    sshHostId
    sshHostRevision
    hostnameCanonical
    resolvedAddressBytes
    port
    usernameBytes
    hostKeyAlgorithm
    pinnedHostKeyFingerprintBytes
    targetDigest
  }
  credentialRef
  workingDirectoryBytes
  shellMode
  exactCommandBytes
  environmentEntries
  stdinMode
  fixedStdinDigestAndRef, if supported
  timeout
  wirePlanVersion
}
~~~

`createdAt`、UI label 和模型 explanation 可以与 Envelope 一起保存，但不参与执行语义或不得被用来替代上述字段。

### 18.3 v0.1 supported execution shape

为避免审批后出现动态输入：

- SSH execution 默认使用 non-interactive exec channel，不分配 PTY。
- `stdinMode` v0.1 只允许 `NONE`；如果后续支持 fixed stdin，完整 bytes、digest、length 和 encrypted ref 必须在 Envelope 中冻结并重新审批。
- 不允许 interactive stdin、审批后继续拼接输入或由 server output 动态生成 command tail。
- environment 只允许显式、非秘密、可 canonicalize 的固定 entries；Credential 不通过 environment 注入。
- working directory 若需要通过 shell wrapper 表达，该 wrapper 已经是 `exactCommandBytes` 的一部分；Skill 不在审批后追加 `cd`、`env`、newline 或 quoting。

这不新增产品功能，只收紧 v0.1 实际可执行形态。

### 18.4 Canonical encoding

Canonical encoder 必须满足：

- versioned deterministic binary encoding；
- fixed field order / canonical map order；
- integers 使用唯一 encoding；
- byte arrays 保持原值；
- environment 按 canonical key bytes 排序并拒绝重复 key；
- hostname 在进入 Envelope 前完成 IDNA / case normalization，结果与原始 display form 分开保存；
- address 使用 packed IPv4 / IPv6 bytes，不使用可多义的 display text；
- fingerprint 包含 algorithm ID 和 digest bytes；
- command bytes 不做 Unicode normalization、line-ending conversion、trim、shell formatting 或追加终止符；
- canonicalization 失败即 Envelope invalid。

Digest：

~~~text
targetDigest   = SHA-256(targetSchemaVersion || canonicalSshTargetBytes)
envelopeDigest = SHA-256(envelopeSchemaVersion || canonicalEnvelopeBytes)
~~~

具体 canonical binary format 在 ADR 中锁定；一旦某版本签发 Envelope，该 encoder 版本必须可用于恢复和 golden verification。无法解释旧版本时 fail closed。

### 18.5 Permission analysis

Permission Engine 输入：

- canonical Envelope；
- Skill operation semantics；
- frozen Workspace / environment；
- Historical Policy Snapshot；
- Current Runtime Mandatory Safety Floor；
- current Plan / Verification binding；
- known prior Run actions；
- relevant obligation facts。

输出是 versioned `PermissionAnalysis`：

- risk level；
- ALLOW / ASK / DENY；
- matched / failed Safe Read rules；
- uncertainty reasons；
- policy provenance and versions；
- required trusted UI disclosures；
- envelopeDigest / targetDigest。

Permission Engine 是纯判定组件，不读取用户 Persona，不写数据库，不执行 SSH。

### 18.6 PermissionRequest

`PermissionRequest` 至少绑定：

- request ID / version；
- Run / ToolCall；
- exact Envelope ID / digest；
- risk and reasons；
- current target / Workspace facts；
- Success Criteria / Verification Plan version；
- renderer contract version；
- creation and expiry；
- policy / safety versions at request time。

UI 打开 Review 时从 authority 读取 Envelope 和 request，不使用 Timeline card 中复制的 command text。

正式读取链为：

~~~text
Permission Review UI
→ PermissionReviewUseCase / TrustedPermissionReviewAssembler
→ TrustedReviewAuthorityReader
→ authoritative PermissionRequest + ExecutionEnvelope + current validity
→ secure-byte-renderer / safe-text renderer
→ TrustedPermissionReviewModel
→ Presentation
~~~

该链不读取 mutable SSHHost 来补全 target，也不从 Projection 取 command / parameters。Approve 提交仍只携带 `permissionRequestId + envelopeId + envelopeDigest + expectedRequestVersion`，随后由 Application / Execution Gate 重新验证 authority。

### 18.7 PermissionDecision

`APPROVE_ONCE` 绑定 request + Envelope digest + deciding user-auth evidence + timestamp。它不能：

- 对另一 Envelope、Retry Run 或新 ToolCall 生效；
- 变成 Agent / Host 永久 allow；
- 覆盖 current safety hard DENY；
- 批准 raw Observation egress；
- 在 process recreation 后自动恢复为可消费 ticket；
- 容忍 target、command、environment、stdin、timeout 或 Verification binding 发生语义变化。

### 18.8 Equality chain

系统通过同一 authoritative record 强制：

~~~text
analyzed Envelope ID / Digest
= PermissionReview authoritative Envelope ID / Digest
= PermissionDecision Envelope ID / Digest
= ExecutionGate validated Envelope ID / Digest
= ExecutionTicket embedded Envelope ID / Digest
= SSH wire plan source Envelope ID / Digest
~~~

任何环节 mismatch 立即阻止 send，生成 security diagnostic event。

---

## 19. Secure Byte Rendering Boundary

### 19.1 Security component status

`secure-byte-renderer` 是 Trusted Core 的纯组件，不是普通 syntax highlighting utility。它的唯一主要输入是 `exactCommandBytes`，主要输出是不可执行的 presentation tokens。

~~~text
exactCommandBytes
→ deterministic safe byte renderer
→ ordered PresentationToken[]
→ trusted native text primitives
~~~

### 19.2 Trusted Permission Review assembly path

`TrustedPermissionReviewAssembler` 是 application-level trusted read component。它在一个一致的 authoritative read snapshot 中：

1. 读取并验证 PermissionRequest identity、version和当前resolution状态。
2. 读取 request直接引用的 immutable ExecutionEnvelope；不接受 caller传入command text。
3. 重新计算 / 验证 Envelope digest和targetDigest。
4. 读取current safety epoch、Run / attempt validity、host revision staleness和Verification Plan reference。
5. 把 `exactCommandBytes` 交给 `secure-byte-renderer`。
6. 把 frozen hostname、address、port、username、working directory、environment和fingerprint交给同一safe-text / byte boundary。
7. 组装一次性的 `TrustedPermissionReviewModel`。

如staleness判断需要读取current SSHHost，它只能比较revision / targetDigest并产生valid / stale状态；任何mutable hostname、address、port、username或fingerprint都不得进入Review Model或替代Envelope中的frozen values。

~~~text
TrustedPermissionReviewModel
├── PermissionRequest identity / expected version
├── Envelope ID / Digest / schema version
├── risk analysis + current validity
├── frozen target safe tokens
├── exact command PresentationToken[]
├── execution parameter safe tokens
├── Verification Plan reference
└── stale / safety-floor / target-validation status
~~~

该 model：

- 不是新 Domain Entity；
- 不写入 Runtime Event Ledger；
- 不保存为普通 materialized Projection；
- 不暴露 raw command `String` / bytes给Presentation；
- 只在Assembler读取的authority versions仍匹配时显示Approve affordance；
- 即使显示为valid，Approve提交仍再次revalidate，防止read后状态变化。

`secure-byte-renderer` 可以提供其他受信任本地byte viewer所需的底层纯token能力，但只有 `TrustedPermissionReviewAssembler` 可以把 authoritative Envelope bytes转换成**可与Permission Approve同屏出现**的Permission Review tokens。

### 19.3 Token contract

每个 token 至少包含：

- byte offset；
- byte length；
- token class；
- deterministic visible label；
- accessibility label；
- optional warning category。

Token classes 至少包括：

- safe printable ASCII；
- SPACE / TAB / LF / CR；
- NUL / C0 / DEL / C1 control；
- ESC / ANSI introducer；
- valid UTF-8 scalar；
- bidi / zero-width / confusable-sensitive Unicode；
- invalid UTF-8 byte；
- truncated-view marker（不属于 command bytes，必须明显分层）。

### 19.4 Rendering rules

- LF、CR、TAB、ESC、NUL 与所有控制字符显示为可见、不可解释的 token。
- ANSI sequence 永不执行，逐 bytes 显示或按确定性 token grouping 显示。
- bidi override、zero-width、line separator、paragraph separator 显式标记。
- invalid UTF-8 不替换为丢失原值的 replacement character；显示 hex bytes。
- viewer 的视觉换行只是 layout，不产生或隐藏 command byte。
- leading / trailing spaces 有明确 token /背景。
- authoritative byte sequence只直接显示安全可打印ASCII；所有非ASCII bytes使用offset + `\xNN`等确定性escape。可以提供隔离的decoded aid，但byte tokens始终是authority，decoded aid明确标为非执行视图。
- 不做 shell pretty-print、quote rewrite、Unicode normalization、tab expansion 后冒充原值。
- command 过长时 summary 可以折叠，但 Approve action 所在 full-screen Review 必须能访问完整 byte-faithful sequence、length 和 digest。

### 19.5 Forbidden renderer reuse

Exact Command Viewer 不得复用：

- Markdown renderer；
- WebView HTML renderer；
- ANSI terminal emulator；
- shell syntax formatter / parser 的 pretty output；
- rich-text component that interprets bidi / control characters without isolation；
- model-generated command explanation。

普通 Live Output viewer 也不能直接复用 ANSI execution path；若未来支持 terminal emulation，它必须与 trusted Permission renderer 完全隔离。

### 19.6 Round-trip invariant

测试 helper 必须能从 semantic byte tokens 重建输入 bytes，并保证：

~~~text
decode(render(bytes).semanticTokens) == bytes
~~~

Layout / warning tokens 不参与重建且具有独立 namespace，避免被误认为 command content。

### 19.7 Other trusted fields

hostname、username、working directory、environment key/value 和 fingerprint 使用相同的 safe-text principles：控制字符不可改变布局，mixed-direction text 被隔离，长值不覆盖 risk / action 区域。它们仍来自 Envelope，不从 mutable SSHHost display model 读取。

### 19.8 Adversarial fixture boundary

Golden fixtures 至少覆盖：

- `LF` vs `CRLF` vs isolated `CR`；
- backspace、form feed、NUL、BEL、ESC、CSI、OSC title sequence；
- tabs、leading / trailing whitespace、hundreds of blank lines；
- invalid / overlong UTF-8；
- composed / decomposed Unicode；
- bidi override / isolate；
- zero-width joiner / non-joiner / space；
- homoglyphs；
- emoji / surrogate boundary in platform conversion；
- embedded fake approval labels；
- extremely long unbroken bytes；
- byte sequences that resemble Markdown / HTML / ANSI；
- empty command and maximum allowed command length。

Renderer version和 golden output 是 Approval compatibility 的测试边界。

---

## 20. SSH Architecture

### 20.1 Components

~~~mermaid
flowchart LR
    PP[TransportPreparationPermit] --> C[FrozenTargetConnector]
    C --> HS[SSH Protocol Handshake]
    HS --> H[HostKeyVerifier]
    H --> P[PreparedSshTransport: unauthenticated]
    P --> X[SSH Execution Gateway]
    T[AuthorizedExecutionTicket] --> X
    X --> L[Exact CredentialLease]
    L --> A[Ticket-scoped Authenticator]
    A --> S[Ticket-scoped Authenticated Session]
    S --> F[Final Authority Validation]
    T --> F
    F --> SP[One-time SendStartPermit]
    SP --> E[Exact ExecChannel / Wire Plan]
    E --> M[DeliveryCertaintyMonitor]
    E --> ST[stdout/stderr Spool]
    ST --> O[Raw Observation Store]
    M --> R[ExternalResult to RunCoordinator]
~~~

### 20.2 Frozen target connection

Connector 必须：

- 只通过Runtime签发的`TransportPreparationPermit`，使用Envelope冻结的 `resolvedAddressBytes + port` 建立 socket；
- 保留 hostname 仅用于审计和 target display，不重新 DNS resolve；
- 将Envelope username纳入context binding，但preparation阶段不发送SSH user-auth request / username proof；
- 只接受Envelope冻结的pinned fingerprint；
- 不注入 current SSHHost 的新 proxy、port、username 或 host key；
- address 不可达时失败，不能静默 fallback 到另一个 DNS address。
- 不把socket放入generic connection pool，也不向Runtime暴露socket API。

若用户希望使用新 address，创建新 SSHHost revision / Envelope，并重新分析和必要审批。

### 20.3 Host Key verification

- Host onboarding 把用户确认或可信 out-of-band 获得的 key fingerprint 保存于 SSHHost revision。
- Envelope 冻结 algorithm + fingerprint bytes。
- 连接后、认证前验证实际 host key。
- missing / changed / unsupported key 立即关闭连接，不发送 credential 或 exec request。
- 首次未知 key 进入独立 Host Key Review；确认后产生新 host revision 和新 Envelope，不能修改旧 Envelope。
- Reconciliation 仍使用 source target fingerprint；如果 key 已变而无法建立可信身份，obligation 保持 unresolved。

Host Key验证成功后只产生短时、未认证的`PreparedSshTransport`。它至少绑定：

- `preparedTransportId`；
- `runId + toolCallId + attemptId + ownerEpoch`；
- `executionEnvelopeId + envelopeDigest + targetDigest + sshHostRevision`；
- exact `resolvedAddressBytes + port`；
- observed / verified Host Key algorithm + fingerprint bytes；
- preparation时的`runVersion + safetyEpoch + permission / SafeRead proof version + obligation watermark`组成的context digest；
- created monotonic time + short expiry。

该handle不可序列化、不可复制、不可跨process恢复，并保持unauthenticated。以下任一情况使其立即unusable并关闭socket：

- owner epoch或process改变；
- Run / ToolCall attempt改变；
- Envelope、target digest、address、port、host revision或username改变；
- observed / pinned Host Key不匹配或server identity在handshake中改变；
- v0.1中的任何safetyEpoch变化，或permission / obligation context不再匹配；
- expiry、Cancel、Run失效或process death。

Handle失效后不能“更新字段”继续使用；必须签发新preparation permit并从新socket / handshake / Host Key verification开始。Host Key change在发送任何credential proof前失败，并走已有Host Key Review / new Envelope流程。

### 20.4 Credential resolution

SSH Adapter 不接收 password / private key String。Ticket签发前不得创建、预取或缓存`CredentialLease`；preflight只能读取secret-free credential metadata和rotation eligibility。

`AuthorizedExecutionTicket` 是Vault签发SSH auth lease的唯一authority。SSH Execution Gateway以Ticket中的logical CredentialReference和exact rotation向Vault请求scoped `CredentialLease`：

- lease 绑定 `ticketId + preparedTransportId + runId + attemptId + ownerEpoch + targetDigest + exact rotation + purpose=SSH_AUTH + expiry`；
- 返回最小 auth operation 或短生命周期 secret bytes；
- 只请求 Ticket 在T6-A已解析并冻结的 exact credential rotation version，同时记录该version而不记录 secret；
- lease只允许在该prepared transport上执行一次ticket-scoped authentication，不提供open-channel / exec authority；
- lease 在 auth 后立即关闭并 best-effort 清理内存；
- auth-per-use credential 在设备认证不可用时返回 `USER_AUTH_REQUIRED`；此次Ticket / Fence以`NOT_STARTED`结束并关闭transport，Run再进入WAITING_USER，不能跨用户等待保留Ticket或socket。

Credential rotation 不改变 frozen target；只允许 Approved Baseline 定义的同一 logical credential rotation 语义。

如果 rotation 在 PermissionDecision 后、Execution Gate 前发生，Gate可以在同一logical reference下重新验证并把新version写入新Ticket；如果 rotation 在Ticket签发后发生，既有Ticket仍只允许旧的exact version。旧version不可用时此次send失败并重新进入gate / credential recovery，不能静默改用新version。

Ticket invalidation同时revoke / close lease并poison authentication result。即使remote已接受authentication，所得session也只属于原Ticket / attempt；final authority validation失败时必须关闭，后续Ticket不能复用。

### 20.5 Exact exec wire plan

SSH Adapter 不从 workingDirectory、environment 和 command fields重新拼装 shell text。`ExecutionEnvelopeFactory` 已生成 immutable `SshWirePlan`，至少定义：

- environment request entries及其 exact bytes，如支持；
- PTY disabled；
- exec request payload = `exactCommandBytes`；
- stdin mode / bytes；
- timeout behavior；
- output channel limits。

Adapter 执行 wire plan，不能添加 newline、quote、`cd`、`sudo`、shell wrapper 或 locale setup。Transport library 如果不能发送原始 exec payload bytes，不能用于 v0.1，除非 conformance test 证明转换与 Envelope encoding 完全一致。

只有T6-B签发并在同一non-yielding turn消费的`SendStartPermit`可以打开exec channel并发送wire plan。authentication success、channel object存在或旧session可达都不能调用该路径。environment / exec / stdin等属于wire plan的外部request不得在final permit前发送；transport开始第一个execution-wire request时必须建立delivery-stage evidence，不得在普通异步queue中延迟成未受协调的send。

### 20.6 Stream handling

- stdout 和 stderr 保持不同 channel ID。
- bytes 以 per-channel sequence number 和 global receive ordinal 写入加密 spool。
- UI 更新允许批处理 / backpressure，但 raw audit bytes 的截断必须显式记录 cutoff、total-known length 和 digest status。
- 对 Provider 的截断 / extraction 是独立 safe view，不修改 raw record。
- binary output 不强制 decode 成 text。
- live viewer 不解释 ANSI 作为 trusted UI。

### 20.7 Timeout and cancellation

- timeout 来自 Envelope。
- 本地 timeout 先提交 `CancellationRequested(reason=TIMEOUT)`，再关闭 exec channel / session。
- timeout / Cancel / safety invalidation发生在authentication期间时，不等待auth完成；立即invalidate Ticket / Fence、关闭transport并丢弃late auth result。
- 关闭 socket 不证明 remote process 未运行。
- 若 command request 可能送达但没有可信 exit / reconciliation evidence，mutating ToolCall 为 UNKNOWN_OUTCOME。
- Server-side kill 只在新 Envelope、正常 Permission 和 unknown obligation 允许后才能执行，不能作为隐式 cancel cleanup。

### 20.8 Delivery certainty

Transport 返回 versioned delivery facts，而不是单一 Boolean：

| Stage | Known fact | Mutation failure semantics |
|---|---|---|
| `PREPARATION_NOT_STARTED` | 未建立socket | 可确认未执行 |
| `TRANSPORT_PREPARED_UNAUTHENTICATED` | handshake + pinned Host Key完成；未开始user auth | 可确认未执行 |
| `AUTH_STARTED` | authentication proof可能已发送；exec capability尚不存在 | command可确认未执行；session不得复用 |
| `AUTHENTICATED_NO_EXEC` | remote auth成功；未打开 / 未写exec request | command可确认未执行；session仍不得复用 |
| `EXEC_CHANNEL_OPEN` | authenticated channel开启，未写execution-wire request | 可确认未执行；final authority仍必须有效 |
| `REQUEST_WRITE_STARTED` | request bytes 可能部分进入 transport | 保守 UNKNOWN_OUTCOME |
| `REQUEST_SENT` | 完整 request 交给 transport | 可能已执行 |
| `SERVER_ACCEPTED` | server 接受 exec request | 可能 / 很可能已执行 |
| `EXIT_STATUS_RECEIVED` | 收到可信 exit status | execution terminal，但 Task success 仍需 Verification |
| `CHANNEL_CLOSED_WITHOUT_EXIT` | 未获可信终态 | UNKNOWN_OUTCOME for mutation |

不同 SSH library 对“sent / accepted”的可观测性不同；Adapter conformance 必须映射真实能力，不能虚构更高 certainty。

仅当library / adapter能不可争议地区分authentication、channel open与`REQUEST_WRITE_STARTED`时，pre-request stage才可证明command `NOT_STARTED`；否则在crash / ambiguous callback处仍保守UNKNOWN_OUTCOME。

SSH authentication本身是可被server日志、rate limit或security monitor观察到的network side effect，但它不是获批command的remote mutation。架构仍审计auth stage、在invalidation时关闭session，并绝不把auth success解释为ToolCall execution或exec reuse authority。

### 20.9 UNKNOWN_OUTCOME

当 mutation 在 `REQUEST_WRITE_STARTED` 之后失去可信终态：

1. SSH Adapter 返回 uncertainty 和所有 delivery facts。
2. RunCoordinator 在同一事务保存 ToolCall UNKNOWN_OUTCOME、source Envelope / target / conflict facts。
3. 创建可投影的 unresolved external side-effect obligation。
4. 当前 Run 进入 RECONCILING；若用户同时 Cancel，先保存 obligation，再 Run CANCELLED。
5. 所有潜在冲突 mutation gate 被阻止。

### 20.10 Reconnect and reconciliation

- Reconnect 是新 SSH connection，不是重发原 exec request。
- 使用 source Envelope frozen address / port / username / fingerprint。
- 仅提出建立原效果真相所需的 read-only ToolCall。
- 每个 read check 仍过 Safe Read / Permission 和 Egress Guard。
- `CONFIRMED_EXECUTED` / `CONFIRMED_NOT_EXECUTED` 必须引用 evidence 和 evaluator。
- PARTIAL / inconsistent / unreachable 保持 unresolved。

### 20.11 SSHHost mutation acceptance test

~~~text
Create Envelope E for host revision 7 / address A / port P
→ approve E
→ mutate SSHHost to revision 8 / address B or port Q
→ attempt E
→ ExecutionGate rejects stale target revision
→ no connection to B:Q
→ new Envelope required
~~~

即使实现策略未来允许已 committed send 继续，它也只能连接 E 中 A:P，不得连接 B:Q。

同一测试还必须覆盖：若A:P已经产生`PreparedSshTransport`，随后host revision / target digest / pinned Host Key改变，该handle被关闭且不能由旧Ticket或新Ticketclaim；不得连接或认证到B:Q。

---

## 21. Credential Vault

### 21.1 Trust root and envelope encryption

v0.1 使用 Android Keystore 中生成的 app-private master key / key-encryption key 作为 trust root：

~~~text
Android Keystore non-exportable key
→ wraps local data-encryption keys
→ authenticated encryption of Provider / SSH secrets
→ encrypted secret records outside Domain DB content fields
~~~

Android Keystore 支持把 key material 保持为 non-exportable，并可限制用途与用户认证；硬件支持时可选择 hardware-backed / StrongBox，但 StrongBox 不是最低兼容要求。[Android Keystore 官方文档](https://developer.android.com/privacy-and-security/keystore)

### 21.2 Data model

Domain 只保存 `CredentialReference`：

- logical credential ID；
- kind（Provider API key、SSH password、SSH private key 等）；
- metadata / created / rotated timestamps；
- current rotation version；
- availability / auth policy；
- secret store opaque handle。

secret bytes、private key content、passphrase 和 API key 不进入 Run Snapshot、Message、Runtime Event payload 或 analytics。

### 21.3 Scoped lease

所有使用通过 `CredentialLease`：

- caller identity / purpose checked；
- target / Provider binding；
- short expiry；
- one operation or active connection scope；
-不可序列化；
-不能被 Provider context / UI普通字段读取；
- close 后清理 secret buffers，承认 managed runtime 内只能 best-effort zeroization。

SSH专用收紧：

- `TransportPreparationPermit` / `PreparedSshTransport`没有请求lease的authority；pre-ticket阶段不能创建secret-bearing lease、private-key signing handle或password view。
- 只有仍处于`HELD_PRE_SEND`且通过Ticket / Fence compare的`AuthorizedExecutionTicket`可以请求exact rotation lease。
- SSH lease绑定ticket、prepared transport、Run attempt、owner epoch、target和`SSH_AUTH` purpose；只授权一次authentication，不授权打开exec channel或发送command。
- Ticket / Fence invalidation、Cancel、safety / owner / attempt / target变化、expiry或process death都会使lease和对应authenticated session不可继续用于execution。
- authentication完成不延长lease成为可池化connection authority；session始终ticket-scoped。

### 21.4 Device-auth boundary

Credential policy 至少支持：

- `AUTH_PER_USE`：每次新 Provider / SSH auth 前要求 biometric / device credential；后台无法认证则 WAITING_USER。
- `AUTH_VALIDITY_WINDOW`：用户在 app 可见时认证，产生短时 Keystore-authorized window；只允许已明确启动的 Run 使用。
- `DEVICE_UNLOCK_REQUIRED`：设备重启后首次解锁前不可使用。

默认安全规则：

- 查看、复制、导出、替换 secret 始终要求显式 secure surface 和 device auth；private key 导出默认关闭。
- Permission approval 不等于解锁 Credential；两者是独立事实。
- process death 使内存 lease 失效；恢复只能重新 resolve，并在需要时 WAITING_USER。
- device lock / auth expiry 时不静默放宽；下一次需要 credential 的连接暂停。
- SSH auth-per-use若在Ticket后才发现需要用户交互，不在`HELD_PRE_SEND`下长期等待：当前attempt以`NOT_STARTED`释放Fence并关闭transport，完成用户认证后重新prepare / gate / issue Ticket。

### 21.5 Rotation

- rotation 创建新 secret version，不覆盖旧 audit metadata。
- Run Snapshot 仍引用 logical CredentialReference。
- 每次实际连接追加所用 rotation version。
- credential recovery / user selection 是 post-snapshot runtime input。
- logical credential identity 变化、credential kind 变化或 target identity 变化不属于普通 rotation，必须新 Envelope / 必要审批。

### 21.6 Backup policy

以下内容从 cloud backup、device-to-device transfer 和 cross-platform transfer 中显式排除：

- encrypted credential records；
- wrapped data keys；
- raw observations / sensitive evidence；
- auth leases / biometric state；
- provider-safe blobs 中仍被标为敏感且不应迁移的内容。

Android Auto Backup 默认会包含多数 app-private files，因此不能依赖“放在 internal storage 就不会备份”；必须使用 explicit extraction rules / no-backup storage。[Auto Backup 官方文档](https://developer.android.com/identity/data/autobackup)

可选备份的 Domain metadata 恢复后如果没有对应 local secret，CredentialReference 标记 `MISSING` 并要求重新录入；绝不能产生空 secret fallback。

### 21.7 Logging prohibition

- secret-bearing types 禁止 `toString` / generic serialization。
- logger 使用 allowlist fields，不做“记录后再 regex 脱敏”的单一防线。
- HTTP / SSH wire logging 在 release 构建关闭 Authorization、headers、key exchange 和 payload。
- crash report 不附 raw observation、Envelope environment value 或 credential error detail。
- clipboard、screenshot 和 recent-app preview 的安全控制由 trusted credential UI 承担。

### 21.8 Key invalidation and loss

Keystore key invalidated、设备安全设置改变、App 数据恢复到新设备或 secret record corruption 时：

- Vault 返回 typed `KEY_INVALIDATED / SECRET_UNAVAILABLE / USER_AUTH_REQUIRED`；
- Run WAITING_USER 或 PAUSED；
- 不清空 CredentialReference 后继续匿名 / passwordless fallback；
- 不删除历史 audit；
- 用户重新录入形成 rotation / replacement fact。

---

## 22. Android Background Execution

### 22.1 Product semantics preserved

系统只承诺：

> user-initiated + user-visible + best-effort continuation；进程被终止后安全 PAUSED 或 RECONCILING。

后台组件是 Runtime 的 host，不是第二个 Runtime。UI 可见和 FGS 期间绑定同一个 `RunCoordinator` owner / ActiveRunSlot。

### 22.2 Lifecycle strategy

1. 用户在可见 Activity 中 Start / Resume Run。
2. Application 在仍满足 foreground-start 条件时启动 user-visible continuation host。
3. Run 正在进行 Provider / SSH / Verification I/O 且 App 退到后台时，Foreground Service 显示准确持续通知。
4. Run 进入 `WAITING_PERMISSION`、`WAITING_USER`、长期 PAUSED 或 terminal 后，停止 FGS；可保留普通 attention notification。
5. 用户点击通知进入相应 trusted surface，再从可见状态 Resume。
6. Service 不使用 sticky automatic replay；device reboot 不自动重启 Run。

Android 12+ 通常禁止 App 已在后台时随意启动 FGS，因此启动点必须来自可见 Activity 或用户对 notification/widget 的明确动作。[Android 后台启动 FGS 限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

### 22.3 FGS type decision

v0.1 Play candidate 选择：

> `specialUse`，subtype 明确描述为“user-initiated, user-visible personal AI remote-server administration Run that must preserve an in-progress SSH / Provider operation long enough to reach a safe pause or terminal boundary”。

原因：

- Agent Run 是交互式 orchestration、远端管理、等待网络与验证的组合，不只是文件传输。
- `dataSync` 会误述主要用途；Android 15+ 对 `dataSync` 有总时长限制，官方也建议纯 user-requested network transfer 使用 user-initiated data transfer job。
- `remoteMessaging` 只适用于设备间消息连续性。
- `shortService` 约三分钟，不适用于诊断 / 修复 Run。
- `connectedDevice` 对普通 SSH server 是否属于该政策定义的 external device 存在解释风险；不得为了满足 runtime prerequisite 申报不需要的 network-state permission。
- 官方允许未被其他 type 覆盖的有效场景使用 `specialUse`，但 manifest subtype 和 Play use case 会被审核。[Foreground Service types](https://developer.android.com/develop/background-work/services/fgs/service-types)

这是技术设计选择，不等于 Google Play 已预先批准。发布前必须完成 policy validation gate：

- manifest type / permission / subtype；
- Play Console declaration；
- 核心功能、用户发起、持续可感知、可停止、只运行必要时长的说明；
- 用户触发与持续通知的演示视频；
- 当前 target SDK / Play policy re-check。

Google Play 要求 FGS 是核心、用户发起或可感知、可由用户停止、不可合理延迟且只运行必要时长。[Google Play FGS Policy](https://support.google.com/googleplay/android-developer/answer/16559646)

如果 `specialUse` 未获接受，v0.1 Play build 必须降级为 foreground-only execution + safe PAUSED，而不是偷偷改报错误 type、滥用 UIDT 或承诺后台成功。这不会改变 Approved Baseline 的 best-effort 语义。

### 22.4 Why not WorkManager as Run owner

WorkManager / JobScheduler 不适合作为 interactive Agent Loop 的 authority：

- 调度可能延迟；
- process / worker 可重建；
-不适合持有实时 Permission wait 与 SSH channel；
- worker retry 容易与 mutating replay 语义冲突。

它们未来可用于非权威 housekeeping，例如 projection rebuild、过期 blob cleanup 或用户明确的数据导出，但不能自动 resume / replay active mutation。Android 16 对从 FGS 启动的 background jobs 也应用 quota，不能用 job 套 FGS 绕过限制。[Foreground Service changes](https://developer.android.com/develop/background-work/services/fgs/changes)

### 22.5 Notification lifecycle

FGS notification 来自统一 `NotificationProjection`，至少显示：

- Agent / Task identity；
- current Run phase；
- frozen target 的安全、非敏感摘要；
- elapsed / last event；
- `Open` 与 `Cancel Run` action。

规则：

- Notification 不能直接 Approve Permission、批准 egress 或回答自由文本问题。
-这些 action 只 deep-link 到 trusted full-screen surface。
- Cancel action 发送带 runId / expected version 的 Runtime command，而不是 `stopService()` 后假设 Run 已取消。
- Service 只有在 committed Run state 允许后才停止；若 cancel 产生 UNKNOWN_OUTCOME，obligation 已先持久化。
-用户拒绝 notification permission 或 OEM 隐藏通知时，不扩大后台承诺；App 明确告知 continuation limitation。

### 22.6 Process death

Foreground Service、Activity、ViewModel 和内存 actor 均可能消失。Service callback / Saved State 不是恢复 authority。系统恢复依赖 Persistence；Saved State 只保存 route、ID、scroll 和 draft destination 等小型 UI state。Android 官方也建议 Saved State 只保存恢复 UI 所需的少量数据，而持久状态使用本地 storage。[Saved State 官方文档](https://developer.android.com/topic/libraries/architecture/saving-states)

### 22.7 Platform matrix

| Platform constraint | Architecture response |
|---|---|
| Android 12+ background FGS start restriction | Start / resume while visible or after explicit notification action |
| Android 14+ mandatory FGS type / permission | Declare reviewed type and specific permission |
| Android 15 `dataSync` timeout | Do not model Agent Run as dataSync |
| Android 16 job quota changes | Jobs never own interactive Run |
| OEM battery restriction / user force-stop | best-effort only；recover PAUSED / RECONCILING |
| BOOT_COMPLETED restrictions | no automatic Run restart |
| process / service kill | durable fact recovery；no replay |

截至 2026-08-30，Google Play 官方要求从 **2026-08-31** 起，新 App 和 App Update 必须 target Android 16 / API 36+（Wear OS、Automotive、TV、XR 等类别存在各自例外）。因此如果下一阶段开始实现，API 36应作为主要Play release baseline candidate；Implementation Plan开始前和实际release前仍必须按发布渠道重新读取官方要求，不把该数值当作永久常量。[Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)

---

## 23. Recovery Architecture

### 23.1 Recovery equation

~~~text
Initial immutable Run Snapshot
+ ordered append-only runtime inputs
+ ordered Runtime events and external delivery facts
+ current Runtime Mandatory Safety Floor
+ cross-run obligation projection verified against source facts
= execution-safe recovered current context
~~~

Recovery 不是“反序列化内存中的 Agent loop”。它是从持久化事实重新建立一个安全的 current state。

Ephemeral transport / authority state不属于恢复等式：`TransportPreparationPermit`、`PreparedSshTransport`、Ticket、CredentialLease、authenticated session和`SendStartPermit`在process / owner epoch结束时全部视为永久失效。恢复只解释其durable authority / Fence / delivery evidence，绝不反序列化、重连或复用这些handle。

### 23.2 Bootstrap sequence

App / Runtime process 启动时：

1. 打开 persistence 并验证 schema / migrations / integrity。
2. 加载 current Mandatory Safety Policy version。
3. 读取 `ActiveRunSlot` 和最后 owner epoch。
4. 从 authoritative event ordinal 校验 Run checkpoint。
5. 重建或验证 cross-run obligation projection watermark。
6. 评估 crash 时是否存在未终结 ModelInvocation / ToolCall / ExecutionCommitted / `HELD_PRE_SEND` Fence，以及最后可信transport stage。
7. 使用新 owner epoch claim coordinator ownership；旧进程内prepared handle、Ticket、lease、authenticated session和send permit由定义保证已永久丢失。
8. 将旧owner留下的`HELD_PRE_SEND` Fence标为`STALE_OWNER_EPOCH`，检查authentication / channel / SEND_STARTED / NOT_STARTED delivery evidence；绝不重建或复用旧transport authority。
9. 追加 `RuntimeRecovered`、Fence reconciliation和current safety evaluation facts。
10. 选择 `PAUSED`、`RECONCILING`、existing waiting state 或 terminal projection。
11. 等用户明确 Resume；不自动发起 mutating ToolCall。

### 23.3 Recovery state rules

| Last durable facts | Recovered behavior |
|---|---|
| planning / provider invocation active，无外部副作用 | invocation marked interrupted；Run PAUSED，可由用户 Resume 后新 invocation |
| WAITING_PERMISSION | 保留 request 历史；未执行 approval 在 process recreation 后视为不可直接消费，重新验证 / 必要时重新 ASK |
| WAITING_USER / egress ASK | 恢复明确 waiting context；旧 answer destination 仍需 version validation |
| 只有TransportPreparationStarted / pre-auth result，未有ExecutionCommitted | ephemeral handle已丢失；无credential / exec side effect；PAUSED，Resume后从新prepare / gate |
| ToolCall PROPOSED / APPROVED，未有 ExecutionCommitted | 不发送；PAUSED / 重新 gate |
| ExecutionCommitted + `HELD_PRE_SEND` + owner death；只有可信pre-request auth / channel stage | old Ticket / lease / session lost；若adapter conformance可证明无`REQUEST_WRITE_STARTED`，追加confirmed-not-sent并PAUSED；否则保守UNKNOWN_OUTCOME / RECONCILING |
| authentication成功但`SEND_STARTED`未建立，owner仍存活且Cancel / safety已invalidate | close / poison session；`RELEASED_NOT_STARTED`；不得用新Ticket复用session |
| ExecutionCommitted，能证明 request `NOT_SENT` | 追加 confirmed-not-sent recovery fact；不自动发送，PAUSED |
| 已建立SEND_STARTED但delivery terminal未提交 | mutation UNKNOWN_OUTCOME，建立 / 恢复obligation，RECONCILING |
| request write 可能开始，无 exit terminal | ToolCall UNKNOWN_OUTCOME，建立 obligation，RECONCILING |
| ToolCall terminal + output committed | 不重做 ToolCall；从 Observation / next loop 恢复 |
| VERIFYING 中 Check terminal | 保留 evidence / verdict；只执行尚未完成的新 Check，不重做 mutation |
| Run terminal | 只重建 projections；不重新 claim active execution |

### 23.4 Permission recovery

- PermissionDecision 是历史事实，但进程内 ExecutionTicket 永不恢复。
- transport preparation、CredentialLease和authenticated session同样永不恢复；旧approval即使仍可能有效，也只能进入fresh preparation + fresh gate。
- App restart 后未发送的 approval 必须检查上游定义的有效期、process-recreation invalidation、current safety floor、host revision 和 plan binding。
-需要再次 ASK 时创建新 PermissionRequest，旧 decision 保留审计。
- UI 不把旧 Approve button state 恢复为 active action。

### 23.5 Cross-run obligations

Recovery 扫描：

- 所有 UNKNOWN_OUTCOME source facts；
-所有 Reconciliation result facts；
- source Task / Workspace / frozen target；
- conflict scope；
- projection checkpoint。

Cancel、terminal、Close Task、Archive、App restart、new Run 和 changed SSHHost 均不被视为 resolution event。只有可审计 reconciliation result 能解决 obligation。

### 23.6 Recovery of a new Retry Run

~~~text
Retry command
→ create new Run identity / Snapshot
→ authoritative obligation preflight
→ if relevant unresolved:
     new Run starts RECONCILING precondition
     use source frozen target
     read-only checks only
→ after resolution:
     CONFIRMED_NOT_EXECUTED → normal re-plan with new Envelope
     CONFIRMED_EXECUTED → Verification / plan based on remote truth
~~~

旧 PermissionDecision 和旧 ExecutionTicket 不迁移到 Retry。

### 23.7 Recovery failure

以下情况 fail closed：

- event schema 无法解释；
- Snapshot digest / Envelope digest 不匹配；
- projection authority watermark 不可建立；
- Credential ref / target identity 不可验证；
- current safety floor 不认识历史 constraint；
- evidence blob 缺失导致 reconciliation / Verification 无法判断；
-同时出现两个 active owner / inconsistent sequence。
- recovery路径试图解析、重绑或复用旧prepared / authenticated transport handle。

结果是 PAUSED / WAITING_USER / RECONCILING 或显式 FAILED，不是默认继续。

---

## 24. Verification Architecture

### 24.1 Components

- `VerificationPlanManager`：保存 criteria 与 immutable versions。
- `PlanWeakeningDetector`：比较已展示 / permission-bound plan 的语义变化。
- `VerificationCheckScheduler`：把 Check 转成正常 ToolCall / Envelope。
- `EvidenceStore`：保存 raw evidence ref、digest 和 provider-safe ref。
- `EvaluatorRegistry`：按 evaluator type / version 执行确定性判定。
- `CompletionGate`：判断所有 REQUIRED criteria 是否满足可信 PASS。

这些组件返回 facts；只有 RunCoordinator 写 Run state。

### 24.2 Criterion model

每个 criterion 至少包含：

- stable criterion ID；
- plan version；
- REQUIRED / ADVISORY；
- expected condition / typed comparator；
- proposed Check operation；
- minimum evaluator trust；
- permission-bound status；
- provenance（user、agent proposal、runtime required）。

### 24.3 Check execution

Verification Check 不是隐藏 shortcut：

~~~text
Criterion
→ Check proposal
→ canonical Envelope
→ Permission / Safe Read
→ Execution Gate
→ SSH result
→ Observation / Egress split
→ immutable Evidence
→ Evaluator
→ Verdict
~~~

Check 可以使用本地 raw evidence 进行 deterministic evaluation，而无需先把 raw bytes 发送给 Provider。

### 24.4 Evaluator registry

| Evaluator | Input | Trusted PASS capability |
|---|---|---|
| `DETERMINISTIC_EQUALITY` | typed expected / actual | yes |
| `DETERMINISTIC_RANGE` | numeric / time / size bounds | yes |
| `STRUCTURED_RULE` | versioned parsed output | yes, if rule approved for criterion |
| `USER_CONFIRMED` | local evidence + explicit user decision | yes, with provenance |
| `MODEL_ASSISTED` | Provider-safe evidence | no standalone high-trust PASS for safety-critical REQUIRED |

Evaluator output 保存 version、input evidence digests、actual value、verdict、reason 和 time。

### 24.5 Plan weakening detection

当新 plan version 相对一个已经显示且被 mutating PermissionRequest 引用的版本发生：

- 删除 REQUIRED；
- REQUIRED → ADVISORY；
- 放宽 expected value / threshold；
- 减少 target scope；
- 使用更弱 evaluator；
- 把 FAIL / INCONCLUSIVE reinterpret 为 PASS；

`PlanWeakeningDetector` 标记 `MATERIAL_WEAKENING`。Runtime 不能采用它获得 COMPLETED；必须保存 diff、重新规划并按上游规则要求用户确认，且发送前使旧 PermissionRequest invalid。

增加 criterion、收紧 threshold 或使用更强 check 可以创建新版本，但仍保留旧版本和 diff。

### 24.6 Completion gate

`CompletionGate` 只接受 persisted Verification records：

- 每个 REQUIRED criterion 都有 current applicable version；
-每个 REQUIRED verdict 为可信 PASS；
- evidence digest 可读取 / 验证；
- evaluator version受支持；
-没有 unresolved related UNKNOWN_OUTCOME；
-没有 material weakening 未确认；
-current mandatory safety facts不要求额外 check。

exitCode 0、模型的“已修复”或 RUN_RESULT 文案不能调用 CompletionGate shortcut。

### 24.7 Inconclusive and failed verification

- FAIL：重新诊断 / 新 modification proposal 或 Run FAILED。
- INCONCLUSIVE：进一步 Check、WAITING_USER 或 FAILED。
- evidence 被 Egress BLOCK：本地 evaluator仍可工作；模型只收到结构化 blocked fact。
- evaluator无法解释旧 record：保持 INCONCLUSIVE，不能在升级后猜为 PASS。

---

## 25. Failure Handling

### 25.1 Failure categories

| Category | Examples | Default architecture response |
|---|---|---|
| Configuration | missing Provider / Workspace / credential | WAITING_USER，不创建可执行 action |
| Policy | hard DENY、stale approval、invalid Envelope | block、persist reason、replan / pause |
| Provider transient | rate limit、5xx、network | bounded retry then PAUSED |
| Provider permanent | auth、unsupported capability | WAITING_USER / FAILED |
| SSH before send | preparation expiry、host key mismatch、Ticket invalidation、auth fail | no command side effect；close / poison transport and lease；NOT_STARTED then WAITING_USER / PAUSED |
| SSH after possible send | timeout、disconnect、process death | UNKNOWN_OUTCOME + obligation |
| Observation | output too large、binary、SENSITIVE / SECRET | bounded local storage、REDACT / ASK / BLOCK |
| Persistence | disk full、transaction failure、integrity mismatch | stop side effects、PAUSED / FAILED |
| Android lifecycle | FGS start denied、process killed、force stop | persist / recover；foreground-only fallback |
| Verification | FAIL / INCONCLUSIVE / missing evidence | not COMPLETED |

### 25.2 Persistence unavailable

Runtime 在无法先持久化 safety fact 时不得：

- 开始 Provider invocation that may create Tool Proposal state；
- 签发 ExecutionTicket；
- 发送 SSH command；
-接受 Permission / Egress decision as complete；
-提交 VERIFIED COMPLETED。

Live output storage 满时，先停止 / backpressure；若因此丢失可信 terminal information，mutation 按 UNKNOWN_OUTCOME 处理。

### 25.3 Projection failure

Projection failure：

- 不回滚 Runtime facts；
- 不重放外部 action；
- UI 显示“正在同步本地状态”而不是猜测；
- security action直接访问 authority进行 validation；
-后台重建直到 checkpoint 追平。

### 25.4 Safety floor update failure

- current safety bundle 无法加载 / 验证：禁止新的 execution，PAUSED。
-旧 Run history无法解释：fail closed。
- update 只部分写入：activation 使用单一 committed epoch pointer；未切换 pointer 的 bundle不生效。
- activation 后保存每次 gate 的实际 current version。
- 更严格update在auth前 / auth中 / auth成功后但SEND_STARTED前linearize时，原子invalidate preparation或Ticket / Fence；不等待network auth，且旧authenticated session不能用于execution。

### 25.5 Host and credential failure

- Host Key mismatch 永不自动 accept。
- frozen address unreachable 不 fallback。
- credential auth failure 不把 command改成 passwordless / different user。
- credential rotation发生时记录 actual version；若 logical identity不匹配则新 Envelope。
- pre-ticket CredentialLease请求属于architecture violation；必须fail closed并产生secret-free security audit。
- auth成功后的Cancel / safety / owner / attempt / target invalidation仍按`NOT_STARTED`关闭session，不能视为“已经授权执行”。

### 25.6 User refusal

用户 Deny / Reject 后：

- request terminal；
-模型可以提出 materially different safer plan；
-相同 Envelope 不循环弹窗；
-Persona 文案不能施压或伪造 necessity。

### 25.7 Unknown exception

捕获边界保存：

- component / operation IDs；
- last committed event ordinal；
- run / attempt version；
- secret-free error code / stack fingerprint；
- transport delivery stage；
- recovery recommendation。

如果异常发生在可能 send 之后，优先 UNKNOWN_OUTCOME，不以 generic FAILED 掩盖不确定性。

---

## 26. Security, Privacy and Observability

### 26.1 Structured audit

Activity / audit 读取 Runtime facts，至少能回答：

- 谁 / 什么触发了 Run；
- 使用哪个 Agent / Provider / Skill / Workspace Snapshot；
-模型提出什么 operation；
-哪个 Envelope digest被分析 / 显示 / 批准 / 执行；
-当时 historical / current safety version；
-连接了哪个 frozen target和 host key；
-使用哪个 logical credential rotation version；
-收到什么 delivery certainty；
-Observation如何分类 / transformation / egress；
-Verification如何得出 verdict；
-是否存在 unresolved obligation。

### 26.2 Logging levels

| Log class | Allowed content |
|---|---|
| operational log | IDs、state、duration、sizes、safe error codes |
| security audit | digests、versions、decision reasons、auth method metadata |
| local content store | Message / output bytes under appropriate protection |
| analytics / crash | aggregate metadata only；no host command/output/secret |

`exactCommandBytes` 默认不进入 generic log。Activity 需要查看时通过 authoritative Envelope + safe renderer 按用户动作读取。

### 26.3 Data minimization

- Provider request只含完成当前 invocation 所需 context。
- raw output本地保留受限，safe view独立。
- credential从不进入 prompt。
- hostname / topology可能是 SENSITIVE；Provider context按 policy 处理。
- Notification只显示用户允许的非秘密摘要；lock screen visibility遵循隐私设置。

### 26.4 Network boundaries

- Provider endpoint和 SSH target属于不同 network clients / trust configs。
- Provider client无 SSH socket capability。
- SSH client无 Provider API credential。
- TLS / host key failures为硬边界，不由模型 override。
-自定义 Provider响应仍是不可信 remote input。

### 26.5 Architecture enforcement

CI / build-time architecture tests检查 forbidden dependencies，尤其：

- provider package不能 import raw observation API；
- skill package不能 import SSH transport；
- presentation不能 import vault secret / execution gateway；
-只有Runtime transport-preparation gate能创建`TransportPreparationPermit`；preparation implementation不能importCredentialLease或exec-channel request capability；
-只有 runtime execution gate package能创建 Ticket；
-只有final execution gate能创建ephemeral `SendStartPermit`，且stale authenticated session不能进入该API；
-只有 RunCoordinator persistence capability能 append Run transitions。

---

## 27. Testing Architecture

### 27.1 Test pyramid

~~~text
Many pure deterministic unit / property tests
        ↓
module contract and persistence tests
        ↓
fake Provider / fake SSH integration tests
        ↓
Android process-death / FGS instrumentation tests
        ↓
small set of end-to-end safety scenarios
~~~

### 27.2 Domain and state machine tests

- every allowed / forbidden Run transition；
- one active Run invariant；
- Cancel Run vs Close Task；
- terminal Run immutability；
- Snapshot + inputs + events reducer determinism；
- historical/current policy merge lattice `DENY > ASK > ALLOW`；
- REQUIRED Verification CompletionGate；
- Plan weakening detection；
- property-based random event sequences never produce illegal mutation after unresolved unknown。

### 27.3 Permission and Safe Read tests

- allowed built-in Safe Read shapes；
- redirection、substitution、eval、interpreter、sudo、unknown executable、network egress、compound command all fail auto-ALLOW；
- uncertainty can only raise privilege；
- production Workspace overrides；
- stale / expired / wrong digest PermissionDecision；
- current safety tightens old Run；
- later relaxed safety never loosens Snapshot。

### 27.4 Envelope canonicalization golden tests

- same semantic input → identical canonical bytes / digest；
- field order / map order variations normalize identically where semantics allow；
- any semantic field change → different digest；
- command whitespace / CRLF / NUL difference → different digest；
- target hostname / address / port / username / fingerprint / revision changes → different target and envelope digest；
- duplicate environment key / invalid address / unsupported encoding rejected；
-旧 encoder version remains verifiable；unknown version fail closed。

### 27.5 Exact byte renderer adversarial tests

使用第 19.8 节 fixture，并验证：

- round-trip bytes；
- no ANSI / Markdown / bidi execution；
- stable golden tokens across locale / theme；
- accessibility labels reveal controls；
- line wrapping never inserts bytes；
- truncated preview cannot host Approve without full-view path；
- source Envelope digest displayed matches authority。

### 27.6 Provider adapter conformance

每个 Adapter 使用相同 contract suite：

- capability probe truthfulness；
- stream ordering；
- split ToolCall arguments；
- malformed / duplicated finish events；
- cancellation / late events；
- auth / rate limit / context error mapping；
- no RawObservationRef type visibility；
- custom endpoint TLS and redacted logging；
- complete proposal only rule。

### 27.7 Egress Guard tests

- source-path preclassification；
- `.env`、private key、token、Authorization、shadow、database dump fixtures；
- unknown / binary → at least SENSITIVE；
- deterministic redaction / extraction golden output；
- raw ref cannot compile / route into Provider Context Builder；
- ASK bound to digest + Provider；
- known SECRET cannot be user-overridden via ordinary ASK；
- current hard block applies to recovered old observation；
- prompt injection strings remain untrusted data。

### 27.8 Fake SSH server and integration tests

Test server must control：

- host key and key rotation；
- pause / inspect unauthenticated handshake、user-auth request、auth success和exec-channel boundaries；
- record connection / authenticated-session identity，证明stale session没有被new Ticket复用；
- auth success / failure；
- exec request accepted / rejected；
- disconnect before request, during write, after accept, before exit, after exit；
- stdout / stderr interleaving and arbitrary bytes；
- timeout / slow output / huge output；
- command payload echo to prove exact bytes；
- fixed address vs mutated DNS / SSHHost；
- reconnect and read-only reconciliation scenarios。

### 27.9 Persistence crash tests

Fault injection at every T1–T13 boundary，包括T6-P / T6-A / authentication interval / T6-B：

- before transaction；
- after commit / before side effect；
- during external I/O；
- after external result / before commit；
- after fact / before projection；
- disk full / blob write partial / checkpoint mismatch。

Assertions：no silent replay、no lost obligation、no false COMPLETED、Projection rebuild converges。

### 27.10 Concurrency tests

Deterministic scheduler tests interleave：

- Cancel vs Ticket issue / send；
- Approve vs safety update；
- SSHHost mutation vs gate / send；
- `prepare transport → Cancel before Ticket → no CredentialLease / authentication / exec`；
- `prepare transport → safety floor tightens → old preparation closed → fresh preparation + re-gate required`；
- `prepare transport → owner epoch / Run attempt changes → old preparation unusable`；
- `Ticket → authentication in progress → Cancel / stricter safety linearizes → late auth success discarded → no exec request`；
- `authentication succeeds → Cancel / stricter safety before SEND_STARTED → session closed / poisoned → no exec request`；
- `authenticated connection from stale Ticket / attempt → new Ticket → connection identity differs and stale session cannot be claimed`；
- `Gate → stricter safety update before SEND_STARTED → old Ticket NOT_STARTED`；
- `Gate → CancelRun before SEND_STARTED → old Ticket NOT_STARTED`；
- `Gate → new conflicting obligation before SEND_STARTED → old Ticket NOT_STARTED`；
- `SEND_STARTED → stricter safety update → current send不重写；subsequent action / egress / verification使用新floor`；
- `Gate → target mutation attempts before SEND_STARTED → mutation先行则Ticket invalid，send先行则只使用frozen target`；
- process owner recreation vs late callback；
- Permission double tap；
- RUN_QUESTION double reply；
- new Retry vs old UNKNOWN_OUTCOME projection rebuild；
- projection lag vs mutating preflight。

### 27.11 Process-death and Android tests

- kill Activity only；
- kill process in planning / waiting / Provider stream；
- kill immediately before / after ExecutionCommitted；
- kill with only PreparedSshTransport → handle cannot recover；no auth / exec replay；
- kill during authentication or after auth success before T6-B → old Ticket / lease / session never recover；only conclusive pre-request evidence may yield NOT_SENT；
- kill while Fence is `HELD_PRE_SEND`；old Ticket lost，new owner reconciles Fence and never regenerates it；
- kill after actual SEND_STARTED but before durable delivery event；mutation recovers UNKNOWN_OUTCOME unless NOT_SENT is proven；
- kill after request possible send but before exit；
- FGS start denied / notification permission denied；
- user stops FGS / force-stops App；
- device reboot；
- Keystore auth expiry / key invalidation；
- recovery claims exactly one active owner；
- no automatic mutating replay。

### 27.12 Projection consistency tests

- same event stream rebuilds identical Timeline / Task Card / Active Strip / Needs Attention / Activity / Notification state；
- delete each projection and rebuild；
- random duplicate event delivery is idempotent；
- out-of-date Permission CTA rejected by authority；
- delete obligation projection does not permit mutation；
- watermark gap fails closed。

### 27.13 Cross-run UNKNOWN_OUTCOME tests

Required acceptance sequences：

~~~text
mutation UNKNOWN_OUTCOME
→ Cancel Run #1
→ Retry Run #2
→ conflicting mutation blocked
~~~

~~~text
UNKNOWN_OUTCOME
→ Cancel Run
→ reconcile CONFIRMED_NOT_EXECUTED
→ new Envelope / normal permission flow allowed
~~~

~~~text
UNKNOWN_OUTCOME
→ Cancel Run
→ reconcile CONFIRMED_EXECUTED
→ Verification first
→ normal subsequent planning only after truth established
~~~

另覆盖 same Task、same Workspace / frozen host、overlapping resource scope 和 uncertainty-as-conflict。

### 27.14 Verification tests

- deterministic HTTP status / service state evaluators；
- structured parser version mismatch；
- MODEL_ASSISTED cannot standalone PASS required critical criterion；
- evidence digest mismatch；
- required FAIL / INCONCLUSIVE blocks COMPLETED；
- Plan weakening after permission invalidates old request；
- stronger Plan version preserves prior facts；
- egress BLOCK does not prevent eligible local deterministic evaluation。

### 27.15 Architecture tests

Automated dependency rules verify Section 5.3. These are release-blocking security tests，不是 style lint。至少额外强制：

- `presentation`不能依赖authoritative Envelope reader、raw `exactCommandBytes` type或mutable SSHHost assembler。
- `presentation`不能用raw `String`构造authoritative command / target review，也不能直接调用Permission renderer。
-只有`TrustedPermissionReviewAssembler`可以同时依赖trusted authority reader与Permission Review rendering capability。
-只有该Assembler可以把authoritative Envelope bytes转换成与Approve同屏的`PresentationToken[]`。
- `PermissionReviewProjection` schema不包含exact command、environment values或可替代frozen target的mutable display fields。
- Approve command只含authority IDs / digest / expected version，并由Application再次revalidate。
- transport-preparation implementation不能依赖CredentialLease / secret API或exec-channel request API。
- Skill、UI、Provider和generic application code不能构造`TransportPreparationPermit`，也不能看到 / 调用`PreparedSshTransport`的socket或auth方法。
-只有Runtime execution gate可以创建AuthorizedExecutionTicket；只有Ticket-scoped Gateway path可以请求SSH CredentialLease / authentication。
-只有final authority gate可以创建`SendStartPermit`，且只有exact bound authenticated session可以在同一send-start turn消费。
- stale Ticket / attempt的authenticated session类型不能转换、缓存或注入到new Ticket execution path。

---

## 28. Performance and Resource Boundaries

### 28.1 Bounded queues

- Provider stream、SSH output、projection events 和 UI live output均使用 bounded buffers。
- backpressure 不能让 actor mailbox无限增长。
- raw output先 spool到受控 storage；UI只消费 sampling / batched projection。
-超限时保存 truncation fact，不把截断伪装成完整 evidence。

### 28.2 Runtime budgets

Approved Baseline 的 model/tool/time budgets由 RunCoordinator计数，并以 facts记录。Budget extension是 runtime input。后台 Service不会通过重启规避 budget。

### 28.3 Storage pressure

清理优先级：

1. rebuildable projections / caches；
2. provider stream transient chunks after final materialization；
3. unreferenced safe views；
4.按 retention policy 的 terminal raw observations。

不得优先删除 unresolved obligation source facts、current Envelope、Permission audit 或 required Verification evidence。

### 28.4 Offline behavior

-普通已持久化内容可读。
-不能访问 Provider / SSH 时 active Run PAUSED。
-离线时 Permission可以查看，但执行前仍重新 gate；是否允许记录 decision由 request expiry policy决定。
-恢复网络不自动 replay mutation；需要 current owner / state evaluation。

---

## 29. Module API Summary

### 29.1 Commands into Runtime

- `StartRunCommand`
- `ResumeRunCommand`
- `PauseRunCommand`
- `CancelRunCommand`
- `SubmitRunGuidanceCommand`
- `SubmitRunQuestionReplyCommand`
- `SubmitPermissionDecisionCommand`
- `SubmitEgressDecisionCommand`
- `SubmitCredentialRecoveryCommand`
- `ExtendBudgetCommand`
- `RequestRetryCommand`
- `RequestHostMutationCommand`
- `ActivateSafetyFloorCommand`

### 29.2 External results into Runtime

- `ProviderInvocationResult`
- `ToolProposalNormalizationResult`
- `AuthorizedExecutionResult`
- `ObservationProcessingResult`
- `VerificationEvaluationResult`
- `BackgroundHostResult`
- `PersistenceRecoveryResult`

### 29.3 Read models out of Runtime

UI 通过 projection query ports读取：

- Home / Agents / Agent Detail；
- Conversation Timeline；
- Task Card / Run Detail；
- Active Run；
- Needs Attention；
- Activity；
- Notification；
- trusted Permission / Egress review references。

Permission Review使用独立`PermissionReviewUseCase`：UI只提交projection提供的request / authority reference，`TrustedPermissionReviewAssembler`经authorized query读取Envelope并返回`TrustedPermissionReviewModel`。Presentation不直接获得Exact Envelope bytes。

Raw local output和Credential surfaces使用各自单独authorized query，不嵌入通用projection payload。任何其他需要只读展示Envelope bytes的本地viewer也必须返回safe tokens，并且不能产生与Approve同屏的authoritative Permission Review model。

---

## 30. ADR Candidates

| ADR | Decision to record | Current recommendation |
|---|---|---|
| ADR-001 | Overall shape | Modular monolith + Ports / Adapters |
| ADR-002 | Run state ownership | One serialized RunCoordinator actor |
| ADR-003 | Persistence model | Authoritative relational facts + append-only Runtime ledger + rebuildable projections |
| ADR-004 | Event ordering | global ordinal + per-Run sequence + expected version |
| ADR-005 | Envelope encoding | versioned deterministic binary encoding + SHA-256 digest |
| ADR-006 | Execution authorization | unauthenticated PreparedSshTransport + Ticket-authorized auth + invalidatable Fence across auth + final one-time SendStartPermit in two short coordination turns |
| ADR-007 | Observation isolation | physically / typologically separate raw and Provider-safe stores |
| ADR-008 | Exact command display | TrustedPermissionReviewAssembler + dedicated pure safe-byte renderer + token contract |
| ADR-009 | SSH transport | library must separate pre-auth preparation / ticket-scoped auth / exec, forbid stale-session reuse, and prove raw payload + delivery-stage conformance |
| ADR-010 | Target resolution | freeze one resolved address; no execution-time DNS fallback |
| ADR-011 | Credential storage | Keystore-wrapped envelope encryption + scoped lease |
| ADR-012 | Android background host | user-started `specialUse` FGS candidate; policy-gated fallback to foreground-only |
| ADR-013 | Composer drafts | destination-keyed drafts with stale quarantine |
| ADR-014 | Verification | versioned evaluator registry + CompletionGate |
| ADR-015 | Safety update | atomic current safety epoch with most-restrictive merge |

Approved System Architecture 后，这些 ADR 应在 Implementation Plan 之前或相应 implementation slice 开始前记录；本轮不创建 ADR files。

---

## 31. Unresolved Technical Risks

### RISK-01 — Google Play FGS classification

`specialUse` 需要 Play review，远端 AI server administration 没有官方预批准类别。缓解：在 Implementation Plan 前进行 policy validation / Play declaration rehearsal；若不通过，Play build降级 foreground-only。不是 Baseline contradiction。

### RISK-02 — SSH library byte fidelity

部分移动 SSH library把 exec command暴露为 `String` 并可能编码 / normalize，无法可靠阻止pre-ticket user authentication、内部自动pool authenticated session，或无法精确区分auth / channel / request write stage。缓解：library spike + fake server conformance是 implementation前置 gate；不合格就更换 transport或写窄 adapter，不放宽Envelope、Ticket或no-bypass invariant。

### RISK-03 — Narrow distributed uncertainty window

本地 ExecutionCommitted 与远端实际接收之间无法形成跨网络原子事务。缓解：delivery facts、UNKNOWN_OUTCOME、read-only reconciliation和cross-run obligation；不能“实现掉”这种不确定性。

### RISK-04 — Local DLP limitations

确定性规则可能漏掉业务秘密或过度阻断。缓解：source-aware classification、unknown-as-sensitive、known-secret hard block、本地 extraction、clear user disclosure；v0.1不声称完整 DLP。

### RISK-05 — Custom Provider variance

“兼容”endpoint 对 streaming / ToolCall / errors差异很大。缓解：capability probe、adapter conformance、execution eligibility gate、普通 chat fallback。

### RISK-06 — OEM background termination

即使 FGS 合规，OEM / user restriction仍可能终止进程。缓解：durable facts、准确通知、best-effort wording、PAUSED / RECONCILING recovery。

### RISK-07 — Keystore fragmentation and invalidation

硬件支持、biometric policy和key invalidation跨设备不同。缓解：capability-aware Vault、typed recovery、StrongBox optional、secret backup exclusion、重新录入流程。

### RISK-08 — Projection scale and rebuild latency

Event Ledger增长后全量重建可能影响移动端启动。缓解：versioned checkpoint、incremental rebuild、bounded retention和security projection fail-closed；不以删除 facts换性能。

### RISK-09 — Conflict-scope precision

模型 proposal未必能可靠指出受影响资源。缓解：operation-specific extractor + conservative Workspace / target scope；不能证明不冲突就阻断。误报优于绕过 unknown obligation。

### RISK-10 — Accessible exact-byte review

Byte-faithful display 与小屏 / screen reader可理解性存在张力。缓解：双层 presentation（byte authority + nonauthoritative decoded aid）、adversarial accessibility tests；不提供可批准 pretty command。

### RISK-11 — Encrypted raw output volume

日志可能快速消耗本地 storage。缓解：bounded spool、explicit truncation、retention、用户容量提示；truncation导致 evidence不足时保持 INCONCLUSIVE。

### RISK-12 — Current safety policy compatibility

Runtime升级可能无法解释旧 schema。缓解：versioned interpreters / migration；无法解释时 fail closed，允许用户结束旧 Run但不执行。

上述风险均没有要求改变 Approved Baseline 或 UX Contract。

---

## 32. System Architecture Acceptance Criteria

### 32.1 Modules and dependencies

- Domain 无 Android / UI / Provider / SSH / persistence implementation dependency。
- Runtime 不依赖 UI。
- Skill 无 execution transport。
- Provider 无 raw observation access。
- SSH 无 mutable SSHHost repository。
- SSH preparation surface无CredentialLease / secret / exec-channel capability；prepared handle不离开Runtime / Gateway internal boundary。
- architecture tests阻止 forbidden imports。

### 32.2 Run ownership and concurrency

- RunCoordinator 是唯一 writer。
-全局最多一个 active Run由 durable slot + owner epoch强制。
- UI/runtime events串行化且异步 result有 attempt/version fence。
- stale result不能复活 terminal Run。
- Cancel / send race按 delivery fact决定，未知时 obligation保留。
- authentication不会阻塞Cancel / safety fact linearization；这些fact先行时late auth success不能发送exec。

### 32.3 Execution chain

- canonical Envelope一次生成、immutable保存。
- analyzed = shown = approved = gate-validated = ticket = executed digest。
-实际 send 前检查 current safety floor、permission、target revision、obligation和credential eligibility。
- Ticket一次性、不可恢复、不可由 Skill / UI创建。
- Ticket签发前只允许frozen-target socket、SSH handshake、Host Key acquisition / verification；不得创建CredentialLease、发送user-auth proof、打开authenticated exec channel或发送command bytes。
- `PreparedSshTransport`绑定target、Host Key、Run / attempt / owner、safety / authority context和短expiry；任一不匹配即关闭并重新prepare。
- Ticket是exact CredentialLease resolution和一次SSH authentication的唯一authority；authentication success不等于exec authority。
- authentication期间不持有actor turn / database lock；`HELD_PRE_SEND`可被Cancel / safety / owner / target / obligation writer原子invalidate。
- auth后必须执行latest-authority T6-B并为exact session签发一次性`SendStartPermit`；只有actual request write建立`SEND_STARTED`。
- stale / invalid Ticket的authenticated session关闭 / poison，不能被new Ticket、new attempt或new Run复用。
- ExecutionCommitted到实际SEND_STARTED之间属于同一send-start coordination domain；任何先linearize的更严格safety、Cancel、attempt invalidation、target mutation或冲突obligation都会阻止旧Ticket。
- `HELD_PRE_SEND` Fence只在SEND_STARTED / NOT_STARTED或owner-death reconciliation后转换；不会锁住整个SSH operation。
- Safety / host mutation无法穿过明显 TOCTOU window。

### 32.4 Persistence and recovery

-所有安全 / side-effect facts append-only。
- Snapshot不被 runtime input修改。
- Projection可删 / 可重建。
-删除 obligation projection不清除 obligation。
- process death不自动 replay mutation。
- process death / owner change不恢复PreparedSshTransport、Ticket、lease、authenticated session或SendStartPermit。
-可能已 send的操作恢复为 UNKNOWN_OUTCOME / RECONCILING。
- current stricter safety适用于旧 Run，later relaxed rule不放宽历史约束。

### 32.5 Projection and UX sources

- Task Card、Active Strip、Needs Attention、Activity和Notification来自统一 Projection Engine。
- stable Task anchor不因 Run更新移动。
- security actions总是 authoritative revalidation。
-普通 Message、Guide、RUN_QUESTION reply具有不同 destination并防止错投。

### 32.6 Provider

- Runtime只处理 normalized Provider contract。
- capability probe决定 execution eligibility。
-部分 / malformed ToolCall不能生成 Envelope。
- retry / cancellation / context overflow有界且不会重放 SSH。
- custom Provider credential不进入 log / Message。

### 32.7 Observation and Egress

- raw output先本地保存 / 分类，永不先发云端做摘要。
- v0.1 SUMMARIZE是本地确定性 extraction / redaction；否则 ASK / BLOCK。
- Provider Context Builder无法读取 raw store。
- SECRET raw egress hard block。
- execution approval与egress approval分离。

### 32.8 Permission and byte rendering

- PermissionReviewProjection只提供request / Envelope authority reference，不复制exact command或trusted target。
- TrustedPermissionReviewAssembler直接读取 / 验证authoritative Envelope并调用secure-byte-renderer，产生ephemeral TrustedPermissionReviewModel。
- Presentation拿不到raw command bytes / String，不从mutable SSHHost组装target，也不能自行decode / normalize。
- Exact renderer不使用 Markdown / ANSI / shell formatter。
- adversarial bytes可见且round-trip。
- Host / Workspace / username / target来自 frozen Envelope并安全展示。
-不存在可批准 pretty command。

### 32.9 SSH

- connector只连接 frozen resolved address / port。
- Host Key在auth和send前匹配 pinned fingerprint。
- pre-authorization preparation保持unauthenticated；Host Key change在credential proof前失败。
- owner epoch、Run attempt、target、Host Key、safety context、expiry或process变化使prepared handle unusable。
- final send只接受当前Ticket / Fence / prepared transport / authenticated session精确绑定。
- mutable SSHHost修改使未 committed Envelope失效，不会重定向。
- stdout / stderr保持byte和channel身份。
- timeout / disconnect按 delivery certainty产生 UNKNOWN_OUTCOME。
- reconciliation只读且使用 source frozen target。

### 32.10 Credential

- Keystore是trust root，Domain只持CredentialReference。
- secret不进入 Snapshot、Message、Event payload、Provider或generic log。
-使用scoped lease和auth policy。
- SSH CredentialLease不能在Ticket前prepare；它只绑定exact Ticket / prepared transport / auth purpose，不授予exec capability。
- rotation version被审计。
- secret / key / raw evidence显式排除backup。

### 32.11 Background

- Run只从用户可见动作启动 / resume后台 host。
- active I/O有持续通知且用户可Cancel。
- waiting / terminal不无限持有FGS。
- process kill安全恢复。
- Play type / declaration是release gate；失败时foreground-only而非错误申报。

### 32.12 Verification

- Checks经过完整 execution chain。
- Evidence / evaluator / verdict结构化持久化。
- MODEL_ASSISTED不能独立高可信PASS安全关键REQUIRED criterion。
- material plan weakening不能静默生效。
-所有REQUIRED可信PASS后才能COMPLETED。

### 32.13 Required test evidence

- Domain / state machine / policy tests通过。
- Envelope / renderer golden tests通过。
- transport-preparation / auth / final-send deterministic race tests和trusted Permission Review architecture tests通过。
- required SA-R3 sequences通过：Cancel-before-ticket无auth、stricter-safety使preparation失效、owner / attempt变化使handle失效、post-auth pre-send Cancel阻止exec、stale authenticated session不可复用。
- fake Provider / SSH conformance通过。
- persistence crash / process death tests通过。
- Projection delete/rebuild tests通过。
- cross-run UNKNOWN_OUTCOME tests通过。
- Verification evaluator tests通过。

---

## 33. Consistency Pass

已针对 SA-R1 / SA-R2 / SA-R3 完成 targeted consistency pass，并重新检查以下事项：

1. 没有创建 Server Mode / Character Mode 或第二套 Runtime。
2. Agent / Persona / Conversation / Message / Task / Run关系未改变。
3. Timeline仍只有Message和stable Task Card一级projection。
4.普通chat不获得SSH shortcut。
5. Run Snapshot保持immutable；post-snapshot input使用append-only facts。
6. Historical Policy与Current Safety Floor保持分离且只取更严格结果。
7. ExecutionEnvelope仍是ToolCall内部value object；独立table不改变Domain层级。
8. SSH target包含host revision、address、port、username和pinned key，executor不查mutable host。
9. Permission与Egress完全分离。
10. Exact Command Viewer只读同一Envelope bytes，不创建pretty command。
11. UNKNOWN_OUTCOME在Cancel、Retry、Archive、process death和new Run后持续存在。
12. Projection可重建但不能清除obligation。
13. Verification仍由evidence / evaluator决定，模型文案不产生可信PASS。
14. Composer三种输入通过destination validation实现，UI chip不是事实。
15. Background design保持user-visible / best-effort，不承诺无限执行。
16. Character capability isolation未被Adapter或UI styling绕过。
17.没有新增Marketplace、Memory、Tavern、Skill类型或远端server。
18.没有选择UI framework、创建Implementation Plan或编写代码。
19.没有发现需要`Architecture Baseline Change Request`的矛盾。
20.没有发现需要`UX Specification Change Request`的矛盾。
21. ExecutionCommitted仍然只是durable authorization；只有actual SEND_STARTED才是send-start linearization，两个点没有被混为一谈。
22. `HELD_PRE_SEND`从T6-A覆盖ticket-scoped authentication到actual send-start，但它是可被安全writer原子invalidate的coordination Fence，不是跨authentication持有的actor / database lock，也不锁住SEND_STARTED后的SSH operation。
23. safety update、Cancel、host mutation和new obligation都参与同一pre-send ordering semantics。
24. TrustedPermissionReviewModel是ephemeral application read model，不是Domain Entity、Runtime fact或Projection。
25. PermissionReviewProjection、Presentation和mutable SSHHost都不能提供可批准command / target authority。
26. SA-R1 / SA-R2没有增加产品功能、持久化顶层实体或MVP scope。
27. Ticket签发前的transport preparation保持unauthenticated，不创建CredentialLease、authenticated channel或exec capability。
28. `PreparedSshTransport`、`TransportPreparationPermit`和`SendStartPermit`只是ephemeral technical value / capability，不是Domain Entity、Runtime fact或持久化顶层实体。
29. `AuthorizedExecutionTicket`是exact SSH CredentialLease和一次authentication的唯一authority；authentication success仍需T6-B latest-authority validation。
30. authentication期间不持有actor / database lock，但`HELD_PRE_SEND`可被Cancel、safety、owner、attempt、target和obligation变化invalidate。
31. invalid / stale Ticket产生的authenticated session永不复用；新Ticket从新preparation / auth开始。
32. actual `SEND_STARTED`仍是外部request write开始；ExecutionCommitted、auth success或SendStartPermit签发均没有被错误提升为send-start linearization。
33. SA-R3没有改变Approved Baseline / UX Contract，没有新增产品功能或扩大MVP scope。

---

## 34. System Architecture Approval and Freeze

本文件当前状态：

> `System Architecture v0.1 — Approved / Frozen`

本阶段已明确：

- modules和dependency graph；
- Run state single-writer ownership；
- Agent loop与Runtime orchestration；
- concurrency strategy与TOCTOU linearization；
- 由T6-A / T6-B两个短atomic coordination turn和中间invalidatable ExecutionFence组成的Send-Start Critical Section；
- unauthenticated transport preparation、Ticket-authorized authentication与final SendStartPermit boundary；
- authoritative facts、Event Ledger、projections和transaction boundaries；
- Composer destination routing；
- Provider abstraction；
- Observation / Egress isolation与安全SUMMARIZE降级；
- Permission / canonical Envelope / ExecutionTicket；
- Trusted Permission Review rendering path与secure byte renderer；
- frozen-target SSH、delivery certainty和reconciliation；
- Credential Vault；
- Android background execution；
- recovery和testing strategy；
- unresolved risks与ADR candidates。

本阶段没有：

-改变Approved / Frozen Architecture Baseline；
-改变Approved / Frozen UX Contract；
-创建工程或编写代码。

本文件现作为Approved Architecture Baseline与Approved UX Contract的v0.1 authoritative implementation architecture。System Architecture阶段至此结束；下一阶段可以编写Implementation Plan，但任何下游文档或代码若需要改变这里冻结的核心contract，必须先提出明确的`System Architecture Change Request`，不得静默偏离。
