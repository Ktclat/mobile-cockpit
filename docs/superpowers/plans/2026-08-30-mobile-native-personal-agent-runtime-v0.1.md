# Mobile-Native Personal Agent Runtime v0.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- Status: Implementation Plan v0.1 — Approved / Frozen
- Date: 2026-08-30
- Review state: Approved / Frozen — authoritative implementation sequence and task contract

**Goal:** 交付一个 Android 原生、Agent-first 的个人 Agent Runtime MVP：用户可在手机上与多个 Agent 的多个 Conversation 对话，并在同一 Conversation 中，以可审计、可审批、可恢复的方式让 Server Agent 通过 SSH 读取、修改和验证一台服务器。

**Architecture:** 以持久化事实与单写者 Runtime Coordinator 为核心；UI 只消费 Projection 并提交 typed command；Provider、Permission、Egress、Vault、SSH 均通过窄 Port 接入。先用五个技术/安全 Gate 证明不可妥协的边界，再按用户可见的垂直切片逐步接入真实能力。任何下游实现不得重定义 Approved/Frozen 的领域、UX 或系统架构语义。

**Tech Stack:** Kotlin、Android SDK 36、Java 17 toolchain、Jetpack Compose、Navigation Compose、Kotlin Coroutines/Flow、Room、kotlinx.serialization、OkHttp/MockWebServer、Android Keystore/BiometricPrompt、JUnit、AndroidX Test、Compose UI Test。SSH client 由 Gate A 选择并锁定，计划不预选未被证明满足 exact-byte contract 的库。

**Spec:**

- [Product / Domain / Runtime Baseline v0.1](../specs/2026-08-29-mobile-native-personal-agent-runtime-v0.1-baseline.md)
- [Conversation & Execution UX Specification v0.1](../specs/2026-08-29-conversation-execution-ux-specification-v0.1.md)
- [System Architecture v0.1](../specs/2026-08-30-system-architecture-v0.1.md)

## Global Constraints

1. 三份上游文档与本 Implementation Plan 均为 Approved/Frozen authoritative source of truth，只能落实，不能静默改写。若实现证据要求改变 plan-level contract，立即停止当前 Execution Task 并提出 `Implementation Plan Change Request`；若证据显示上游矛盾，则提出对应的 `Architecture Baseline Change Request`、`UX Specification Change Request` 或 `System Architecture Change Request`。
2. 本文档批准前不写业务代码。批准后按 dependency DAG 实施；Task number 只提供稳定 Review ID，不会强制无关 prerequisite。Task 1 必须首先完成；同一时间只能有一个 Reviewer Gate 尚未通过的 slice 处于 active implementation，已暂停等待依赖的 slice 不算 active。
3. 每个任务遵循 Red → Green → Refactor：先提交能表达行为的失败测试，再写最小实现，再运行该任务规定的完整验证命令。
4. 禁止 layer-first 交付。每个产品切片必须形成手机端可见闭环或独立 Runtime 可验证闭环，并保留一个可演示入口。
5. `Message`、`Task`、`Run`、`ToolCall`、`Permission`、`Observation` 和 `Verification` 不得扁平化成聊天消息。Timeline 一级 item 仅为 `Message` 与稳定的 `TaskCardProjection`。
6. UI 不读取权威表、不自行推导安全结论、不持有 Credential、不直接调用 Runtime/Provider/SSH。Presentation 只读取 safe read models，并通过 Application API 提交 intent；Application 再路由 typed Runtime command。
7. 任何 mutation 都必须经 Canonical Envelope、Permission/Safety、T6-P/T6-A/T6-B authorization pipeline；不得提供调试后门、隐藏 terminal 或绕过 Ticket 的 adapter API。
8. `RawObservationRef` 与 `ProviderSafeObservationRef` 在类型和物理存储上分离。原始输出默认只在本地，未经 Egress Guard 不得进入 Provider request、日志、analytics、crash report 或普通 materialized Projection 文本；本地查看只能通过受控、bounded、不可执行的 trusted viewer tokens。
9. Credential 只能通过 Vault 的短期 purpose-bound lease/handle 使用：SSH 必须绑定 exact execution Ticket，Provider 必须绑定 exact invocation/profile/model authority；secret 不得成为 Run Snapshot、Envelope、event payload 或通用 DTO 字段。
10. Runtime update 只能收紧旧 Run 的 effective safety；历史 Snapshot 不修改，新的 safety version/decision 作为 append-only fact/event 记录。
11. `UNKNOWN_OUTCOME` obligation 跨 Run、Cancel、Retry、Archive、重启保留，并阻断潜在冲突 mutation，直至只读 reconciliation 建立远端事实。
12. Android 后台执行是 user-initiated、perceptible、stoppable、best-effort。Gate E 未得到 GO 或发布审查未接受时，产品必须使用 foreground-only fallback；不得承诺无限后台执行。
13. 每个 schema 变更都必须导出 Room schema、写 migration test，并从上一已发布的 slice schema 升级。禁止 destructive migration。
14. 每个外部依赖必须锁定精确版本并启用 Gradle dependency verification。SSH 候选只在 Gate A 记录证据后进入正式 version catalog。
15. 所有时间、ID、随机数、网络、SSH、Vault、Safety Policy 和进程生命周期均通过可替换 Port 注入；测试不得依赖真实云模型或真实个人服务器。
16. `CRITICAL` execution、Marketplace/Registry、任意 Skill 安装、通用 terminal、文件管理器、长期 Memory、完整 Character 创建 UI、Conversation Branch/Swipe 均为 v0.1 non-goal。
17. 任何 mutating `PermissionRequest` 在执行 authority 签发前都必须引用一个已权威持久化、不可变、可解码且 digest 匹配的 `VerificationPlanVersion`。缺失、损坏、不支持的 plan/version、digest mismatch 或 REQUIRED criterion 定义丢失均 fail closed，并在任何 mutating request bytes 发送前停止；Runtime 不得在 mutation 后要求模型重新生成“等价”成功标准。

## Fixed Implementation Decisions for v0.1

这些是实现级决定，不改变上游架构；若 Review 改动，应在 Task 1 前完成。

| Item | v0.1 decision | Reason / guardrail |
|---|---|---|
| App name | `Cockpit` | 用户可见名，不绑定具体 Agent 工具品牌 |
| Namespace | `dev.cockpit` | 模块统一命名空间 |
| Application ID | `dev.cockpit.mobile` | 在首个可分发 artifact 前冻结；之后变更按发布迁移处理 |
| minSdk | 28 | 避免为过旧平台扩大安全与生命周期分支，同时覆盖 Android 9+ |
| compileSdk / targetSdk | 36 / 36 | 计划执行/发布跨过 2026-08-31 Play deadline；Release Gate 必须再次核对当前规则 |
| UI | Jetpack Compose + Navigation Compose | 单向数据流，与 Projection/command 边界一致 |
| DI | 显式 composition root | v0.1 保持依赖图可见；若后来引入 DI framework，需 ADR 而非隐式替换 |
| Persistence | Room + encrypted blob files | 关系事实、event ledger 与大体积敏感 bytes 分离 |
| Serialization | kotlinx.serialization with explicit schema version | canonical/domain payload 可版本化；canonical envelope encoder 不直接复用普通 JSON encoder |
| Authority bytes | domain-owned `ImmutableBytes` with defensive copies/content equality | Domain 只依赖语言 primitives；禁止可变 `ByteArray` 穿过 Envelope/authority 边界 |
| Provider transport | OkHttp + SSE parser | 支持 streaming、取消、MockWebServer contract test |
| SSH transport | Gate A outcome | exact command bytes 和阶段语义先于库偏好 |

Task 1 必须从官方仓库解析并锁定当时最新稳定、相互兼容的 AndroidX/Kotlin/Room/OkHttp 版本，在 `gradle/libs.versions.toml` 与 dependency verification metadata 中留下精确值。这里不提前伪造 2026-08-30 的依赖版本；“解析、验证、锁定”本身是 Task 1 的可审计输出，不是运行时动态版本策略。

## Current Platform Evidence to Recheck at Gate E and Release

- Android target API 官方页面说明：自 2026-08-31 起，新应用和更新须面向 Android 16 / API 36 或更高版本。本计划按 API 36 起步，但发布当天仍须复核。[Android target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- Android 14+ 的 Foreground Service 必须声明合适 type 与 permission；`specialUse` 需要 subtype property，且适用性会被审核。[Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- Play Console 的 FGS 声明需要功能、用户影响、演示视频和用例说明；FGS 必须是核心、用户发起/可感知、可停止且不能合理延后。[Play Console declaration](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en) · [FGS policy](https://support.google.com/googleplay/android-developer/answer/16559646)
- Gate A 首轮候选只采用 primary source：Apache MINA SSHD（存在 byte-array exec surface）、SSHJ、mwiede/JSch。候选列表不等于选型结论。[Apache MINA SSHD](https://github.com/apache/mina-sshd) · [SSHJ](https://github.com/hierynomus/sshj) · [mwiede/JSch](https://github.com/mwiede/jsch)

## Module and Dependency Map

```text
:app
  -> :presentation
  -> :platform:android

:presentation
  -> :core:application-api
  -> :data:projection-models
  -> :security:byte-renderer-api

:core:application-api
  -> :core:domain
  -> :integration:provider-api
  -> :data:projection-models
  -> :security:byte-renderer-api

:core:application
  -> :core:application-api
  -> :core:domain
  -> :core:runtime-api
  -> :security:byte-renderer
  -> :security:vault-api
  -> :data:persistence-api
  -> :data:projection-models

:core:runtime
  -> :core:domain
  -> :core:runtime-api
  -> :agent:skill-api
  -> :security:permission-api
  -> :security:egress-api
  -> :security:vault-api
  -> :integration:provider-api
  -> :integration:execution-api
  -> :data:persistence-api
  -> :platform:background-api

:core:runtime-api
  -> :core:domain

:agent:skill-api
  -> :core:domain

:agent:skill-runtime
  -> :agent:skill-api
  -> :core:domain
  -> :security:permission-api

:integration:ssh
  -> :core:domain
  -> :integration:execution-api
  -> :security:vault-api
  -> :data:persistence-api

:data:persistence-room
  -> :data:persistence-api
  -> :core:domain

:data:projection
  -> :core:domain
  -> :data:persistence-api
  -> :data:projection-models

:security:byte-renderer
  -> :security:byte-renderer-api
  -> :core:domain

:security:byte-renderer-api
  -> standard byte-token DTO primitives only

:security:vault
  -> :core:domain
  -> :security:vault-api

:security:vault-api
  -> :core:domain
  -> :integration:execution-api
  -> :integration:provider-api

:security:permission
  -> :security:permission-api
  -> :core:domain

:security:permission-api
  -> :core:domain

:security:egress
  -> :security:egress-api
  -> :core:domain
  -> :data:persistence-api

:security:egress-api
  -> :core:domain

:integration:provider-api
  -> :core:domain

:integration:provider
  -> :integration:provider-api

:integration:execution-api
  -> :core:domain

:data:persistence-api
  -> :core:domain

:data:projection-models
  -> :core:domain

:platform:background
  -> :platform:background-api
  -> :core:runtime-api
  -> :data:projection-models

:platform:background-api
  -> :core:domain

:platform:android
  -> :core:application
  -> :core:runtime
  -> :agent:skill-runtime
  -> :security:permission
  -> :security:egress
  -> :security:vault
  -> :integration:provider
  -> :integration:ssh
  -> :data:persistence-room
  -> :data:projection
  -> :platform:background

:test-support
  -> only public test contracts and fakes
```

`:app` contains Android packaging/entry points only; `:platform:android` is the sole production composition root. Forbidden dependencies are enforced in Task 1: Presentation → SSH/Provider/Room/platform composition, Provider → raw observation store, SSH → mutable host repository at send time, Skill → Credential value/execution authority/adapter, Projection → Vault, and any module → `:spikes:*` from production source sets.

`:integration:execution-api` intentionally exposes three non-coalesced capability surfaces: `TransportPreparationPort` may only establish an unauthenticated prepared transport, `AuthorizedExecutionPort` may only consume an exact authorized Ticket, and `ExecutionControlPort` may only request cancellation. Production composition supplies them as separate dependencies; a consumer that receives preparation authority does not thereby receive authentication/exec authority. Architecture tests reject a concrete binding, façade, locator or injected field that reunifies preparation and execution into a broader callable surface.

## Repository File Map

```text
app/
  src/main/kotlin/dev/cockpit/mobile/
    CockpitApplication.kt
    MainActivity.kt

presentation/src/main/kotlin/dev/cockpit/presentation/
    CockpitRoot.kt
    ui/home/
    ui/agents/
    ui/conversation/
    ui/task/
    ui/permission/
    ui/activity/
    ui/settings/

core/domain/src/main/kotlin/dev/cockpit/domain/
  agent/ conversation/ task/ run/ workspace/
  execution/ observation/ verification/ safety/

core/application-api/src/main/kotlin/dev/cockpit/application/api/
core/application/src/main/kotlin/dev/cockpit/application/
  agent/ conversation/ settings/ runtime/

core/runtime-api/src/main/kotlin/dev/cockpit/runtime/api/
core/runtime/src/main/kotlin/dev/cockpit/runtime/
  coordinator/ reducer/ gate/ recovery/ verification/

agent/skill-api/src/main/kotlin/dev/cockpit/skill/api/
agent/skill-runtime/src/main/kotlin/dev/cockpit/skill/runtime/

security/permission/src/main/kotlin/dev/cockpit/security/permission/
security/permission-api/src/main/kotlin/dev/cockpit/security/permission/api/
security/egress/src/main/kotlin/dev/cockpit/security/egress/
security/egress-api/src/main/kotlin/dev/cockpit/security/egress/api/
security/byte-renderer-api/src/main/kotlin/dev/cockpit/security/render/api/
security/byte-renderer/src/main/kotlin/dev/cockpit/security/render/
security/vault-api/src/main/kotlin/dev/cockpit/security/vault/api/
security/vault/src/main/kotlin/dev/cockpit/security/vault/

integration/provider-api/src/main/kotlin/dev/cockpit/provider/api/
integration/provider/src/main/kotlin/dev/cockpit/provider/
integration/execution-api/src/main/kotlin/dev/cockpit/execution/api/
integration/ssh/src/main/kotlin/dev/cockpit/ssh/

data/persistence-api/src/main/kotlin/dev/cockpit/persistence/api/
data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/
data/persistence-room/schemas/
data/projection-models/src/main/kotlin/dev/cockpit/projection/model/
data/projection/src/main/kotlin/dev/cockpit/projection/

platform/background-api/src/main/kotlin/dev/cockpit/background/api/
platform/background/src/main/kotlin/dev/cockpit/background/
platform/android/src/main/kotlin/dev/cockpit/platform/android/
  CockpitProcessComponent.kt
test-support/src/main/kotlin/dev/cockpit/testing/
architecture-tests/src/test/kotlin/dev/cockpit/architecture/
security-tests/src/test/kotlin/dev/cockpit/security/
spikes/ssh-transport/

docs/adr/
docs/evidence/
```

Production code uses the mapped paths. Test files mirror the same relative package path under `src/test`, `src/androidTest`, or dedicated `integrationTest` source sets.

## Stable Interface Registry

The introducing task owns each root name/signature. A later slice may add only the command/read-model variants explicitly assigned to it below; ad hoc renaming or authority-broadening requires plan review, and architecture-semantic changes require the appropriate Change Request.

```kotlin
@JvmInline value class AgentId(val value: String)
@JvmInline value class ConversationId(val value: String)
@JvmInline value class TaskId(val value: String)
@JvmInline value class RunId(val value: String)
@JvmInline value class ToolCallId(val value: String)
@JvmInline value class PermissionAuthorityRef(val value: String)
@JvmInline value class EgressAuthorityRef(val value: String)
@JvmInline value class QuestionId(val value: String)
@JvmInline value class ConversationRevision(val value: Long)
@JvmInline value class RunVersion(val value: Long)
@JvmInline value class QuestionVersion(val value: Long)
@JvmInline value class OneTimeReplyNonce(val value: String)
class EnvelopeDigest private constructor(val bytes: ImmutableBytes) // factory enforces exactly 32 SHA-256 bytes
@JvmInline value class SafetyEpoch(val value: Long)
@JvmInline value class VerificationPlanId(val value: String)
@JvmInline value class VerificationCriterionId(val value: String)
class VerificationPlanDigest private constructor(val bytes: ImmutableBytes) // exactly 32 SHA-256 bytes

data class VerificationPlanBinding(
    val planId: VerificationPlanId,
    val version: UInt,
    val digest: VerificationPlanDigest,
)

data class VerificationPlanVersion internal constructor(
    val planId: VerificationPlanId,
    val version: UInt,
    val schemaVersion: UInt,
    val criteria: List<VerificationCriterion>,
    val provenance: VerificationPlanProvenance,
    val digest: VerificationPlanDigest,
)

data class VerificationCriterion internal constructor(
    val criterionId: VerificationCriterionId,
    val criterionVersion: UInt,
    val requirement: CriterionRequirement, // REQUIRED or ADVISORY
    val description: String,
    val expectedCondition: ExpectedCondition, // closed typed v1 algebra
    val minimumEvaluatorTrust: EvaluatorTrustRequirement,
    val provenance: VerificationCriterionProvenance,
)

data class ConversationMessageDestination(
    val conversationId: ConversationId,
    val expectedConversationRevision: ConversationRevision,
)

data class RunGuidanceDestination(
    val conversationId: ConversationId,
    val taskId: TaskId,
    val runId: RunId,
    val expectedRunVersion: RunVersion,
)

data class RunQuestionReplyDestination(
    val conversationId: ConversationId,
    val taskId: TaskId,
    val runId: RunId,
    val questionId: QuestionId,
    val expectedQuestionVersion: QuestionVersion,
    val oneTimeReplyNonce: OneTimeReplyNonce,
)

// Frozen lifecycle vocabulary; Task 9 introduces these exact enums.
enum class TaskStatus { OPEN, RESOLVED, CLOSED }
enum class RunState {
    CREATED, PLANNING, RUNNING, WAITING_PERMISSION, WAITING_USER,
    RECONCILING, PAUSED, VERIFYING, COMPLETED, FAILED, CANCELLED,
}
enum class ToolCallState {
    PROPOSED, AWAITING_PERMISSION, APPROVED, EXECUTING,
    SUCCEEDED, FAILED, CANCELLED, UNKNOWN_OUTCOME,
}

sealed interface RuntimeCommand {
    data class StartRun(val taskId: TaskId) : RuntimeCommand
    data class GuideRun(val destination: RunGuidanceDestination, val text: String) : RuntimeCommand
    data class AnswerRunQuestion(val destination: RunQuestionReplyDestination, val text: String) : RuntimeCommand
    data class DecidePermission(val authority: PermissionAuthorityRef, val decision: PermissionDecision) : RuntimeCommand // Task 10
    data class DecideEgress(val authority: EgressAuthorityRef, val decision: EgressDecision) : RuntimeCommand // Task 13
    data class CancelRun(val runId: RunId) : RuntimeCommand
    data class RetryTask(val taskId: TaskId) : RuntimeCommand
}

interface RuntimeCommandPort {
    suspend fun submit(command: RuntimeCommand): CommandReceipt
}

interface AgentConversationQueryPort {
    fun home(): Flow<HomeProjection>
    fun conversation(id: ConversationId): Flow<ConversationProjection>
}

interface RunProjectionQueryPort { // Task 9
    fun run(id: RunId): Flow<RunDetailProjection>
    fun activity(): Flow<ActivityProjection>
}

interface PermissionReviewQueryPort { // Task 10; assembled live, never a materialized Projection
    suspend fun permissionReview(authority: PermissionAuthorityRef): TrustedPermissionReviewModel
}

interface ProviderAdapter {
    suspend fun probe(profile: ProviderProfile): ProviderCapabilities
    suspend fun invoke(
        request: NormalizedProviderRequest,
        authorization: ProviderAuthorizationHandle,
        sink: ProviderEventSink,
    ): ProviderInvocationResult
}

interface RuntimeEventStore {
    suspend fun append(expectedSequence: Long, facts: List<RuntimeFact>): AppendResult
    suspend fun factsFor(runId: RunId): List<RuntimeFact>
}

interface VerificationPlanAuthorityPort { // Task 10; read-only authority view
    suspend fun resolve(binding: VerificationPlanBinding): VerificationPlanResolution
}

interface TransportPreparationPort {
    suspend fun prepare(permit: TransportPreparationPermit): PreparedSshTransport
}

interface AuthorizedExecutionPort {
    suspend fun execute(ticket: AuthorizedExecutionTicket): AuthorizedExecutionResult
}

interface ExecutionControlPort {
    suspend fun cancel(toolCallId: ToolCallId): CancelAcknowledgement
}

interface SshCredentialLeasePort {
    suspend fun acquire(ticket: AuthorizedExecutionTicket): CredentialLease
}

interface ProviderCredentialLeasePort {
    suspend fun acquire(authority: ProviderInvocationAuthority): ProviderAuthorizationHandle
}

interface ObservationEgressPort {
    suspend fun guard(raw: RawObservationRef, context: EgressContext): EgressDisposition
}

interface VerificationEvaluator {
    val kind: VerificationEvaluatorKind
    suspend fun evaluate(check: VerificationCheck, evidence: EvidenceRef): VerificationVerdict
}
```

`CredentialLease`, prepared transport, Ticket and `SendStartPermit` are opaque process-local capabilities. Their only production issuer/implementation is a Runtime-owned nonce registry; SSH/Vault receive a narrow verifier and cannot mint capabilities. Canonical raw bytes never expose mutable arrays. `AuthorizedExecutionTicket` is the only production capability accepted by the execution entry point. Preparation cannot authenticate or send, execution cannot prepare from a host ID, and cancellation is a separate control request rather than an alternate execution surface. `TrustedPermissionReviewModel` can only be assembled from trusted persisted Envelope bytes plus authority facts.

Task 10 owns the immutable Verification definition vocabulary and its deterministic, versioned encoding. A `VerificationPlanBinding` becomes authority only after the complete plan version and every criterion definition commit atomically; the digest covers schema/version, stable IDs, REQUIRED/ADVISORY classification, typed expected conditions, minimum evaluator trust and provenance. `VerificationPlanAuthorityPort` cannot write or regenerate plans. Permission analysis and T6-P/T6-A/T6-B re-resolve the exact binding from authoritative persistence; any missing, corrupt, unsupported or incomplete definition rejects authority. Task 14 consumes those definitions to create checks/evidence/results but never replaces them with a post-mutation model reconstruction.

## Acceptance Traceability IDs

Tasks cite subsection IDs rather than duplicating upstream text:

- Baseline: `B36.1` … `B36.10` map to Baseline §36.1 … §36.10.
- UX: `UX34.1` … `UX34.14` map to UX §34.1 … §34.14; validation scenarios are `UX35`.
- System Architecture: `SA32.1` … `SA32.13` map to System Architecture §32.1 … §32.13.

The final release matrix must show every ID with at least one automated evidence link and, where interaction or accessibility is involved, one recorded device-test artifact.

## Gate Protocol

Each Gate produces `docs/evidence/gate-<letter>-<name>.md` with environment, dependency coordinates and hashes, commands, raw result links, limitations, reviewer, and one of:

- `GO`: every mandatory criterion is demonstrated; dependent tasks may start.
- `NO-GO / TRY NEXT CANDIDATE`: evidence disproves the current candidate; no production dependency is added.
- `BLOCKED — CHANGE REQUEST REQUIRED`: no candidate can honor an Approved/Frozen invariant; stop and raise the appropriate Change Request.
- Gate E alone may yield `GO — FOREGROUND_ONLY FALLBACK`; this is already allowed by the Approved System Architecture and does not weaken Runtime safety.

Gate evidence and ADR are separate: evidence says what happened; ADR says what is selected and why. An ADR is accepted only after its Gate evidence exists.

Gate D is one Reviewer Slice with two dependency checkpoints in the same evidence family. `D1 — Vault Core / Provider Credential` may issue GO after Task 1 and unlock Task 8. The Task 5 slice then becomes inactive while it waits for Gate C; it is not a second active Reviewer Slice. `D2 — SSH Ticket-scoped Credential` runs only after Gate C and unlocks Task 11. `docs/evidence/gate-d-credential-vault.md` indexes both `gate-d1-vault-provider.md` and `gate-d2-ssh-ticket-credential.md`; only D2 completion closes Reviewer Gate 5 and accepts ADR-011. D1/D2 are prerequisite checkpoints, not additional Reviewer Gates.

## Common Verification Commands

All commands run from repository root. PowerShell uses `./gradlew.bat` if `./gradlew` does not resolve.

```bash
./gradlew test
./gradlew connectedCheck
./gradlew lint
./gradlew dependencyCheckAnalyze
./gradlew verifyArchitecture
./gradlew verifyReleaseEvidence
```

CI runs JVM/unit/property tests on every change; emulator/integration/security suites run on every slice Reviewer Gate; real-device/OEM and Play-policy rehearsals run at Gate E and release hardening.

## Reviewer Slice vs. Execution Task Protocol

- A top-level `Task 1…17` is a **Reviewer Slice**: one user/runtime-verifiable vertical outcome, one accumulated evidence bundle, and exactly one `Reviewer Gate`. A Reviewer Slice is not assigned as one coding job or one commit.
- A numbered row such as `E11.4` under `#### Execution Tasks` is the only **Execution Task**: one focused failing test, its exact expected RED reason, the smallest implementation that can satisfy it, one focused verification command, and one commit. An agent takes one unchecked row at a time.
- The broader checklist already present in each Reviewer Slice is **Slice Acceptance Evidence**. It describes what the slice must ultimately prove and is not an assignable coding unit; checking it cannot replace the RED/Green evidence of the numbered rows.
- Within an Execution Task, commit only after the stated focused command is green. At the Reviewer Gate, run the slice's full command and review accumulated row commits/evidence. If a row reveals a Frozen-spec contradiction, stop that row and raise the appropriate Change Request; do not weaken its expected failure.
- Only one Reviewer Slice with an unpassed Reviewer Gate may be actively implemented. A slice may record a dependency checkpoint and become inactive (Task 5 after D1); another ready slice may then be active. There is still only one Reviewer Gate for Task 5 after D2.
- “Exact expected RED” names the missing symbol/assertion/behavior that must fail. A compile failure unrelated to that expectation, flaky infrastructure, or a test that starts green is not valid RED evidence.

---

## Phase 0 — Technical and Security Gates

### Task 1: Bootstrap the Build, Module Boundaries, and Test Harness

**Depends on:** Approved/Frozen specs only  
**Reviewer result:** A launchable empty Cockpit shell plus enforceable module graph; no product behavior  
**Traceability:** `SA32.1`, `SA32.13`

**Files:**

- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `.gitignore`
- Create: module `build.gradle.kts` files for every production module in the Module Map plus `architecture-tests`, `security-tests`, and `spikes/ssh-transport`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/dev/cockpit/mobile/CockpitApplication.kt`
- Create: `app/src/main/kotlin/dev/cockpit/mobile/MainActivity.kt`
- Create: `presentation/src/main/kotlin/dev/cockpit/presentation/CockpitRoot.kt`
- Create: `platform/android/src/main/kotlin/dev/cockpit/platform/android/CockpitProcessComponent.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/Identifiers.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/bytes/ImmutableBytes.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/credential/CredentialReference.kt`
- Create: `architecture-tests/src/test/kotlin/dev/cockpit/architecture/ModuleGraphTest.kt`
- Create: `test-support/src/main/kotlin/dev/cockpit/testing/{FakeClock,DeterministicIds,TestDispatcherProvider}.kt`
- Create: `.github/workflows/android.yml`, `config/detekt/detekt.yml`, `gradle/verification-metadata.xml`
- Create: `docs/adr/ADR-001-module-boundaries.md`, `docs/adr/ADR-002-single-writer-runtime.md`, `docs/adr/ADR-003-persistence-event-ledger.md`, `docs/adr/ADR-004-projection-boundary.md`

**Interfaces introduced:** opaque typed IDs (including the non-secret logical `CredentialReference`) plus injected `AppClock`, `IdGenerator`, and `DispatcherProvider`. Runtime/read ports are introduced by the first slice that can compile and test their complete initial behavior.

- [ ] If the workspace is still not a Git repository when implementation is approved, initialize it with `main` as the initial branch; never overwrite existing history if repository state changed. Add Android/Gradle/IDE/secret/output exclusions to `.gitignore`.
- [ ] Create only enough Gradle wrapper/root build infrastructure to execute a JVM test; resolve current stable official dependency versions, pin them in the catalog, generate dependency verification metadata, and reject dynamic/range versions.
- [ ] Write `ModuleGraphTest` first. It parses Gradle project dependencies and fails on every forbidden edge in the Module Map, including production dependency on `:spikes:*`.

```kotlin
@Test fun `presentation cannot depend on transport or database adapters`() {
    assertThat(graph.transitiveDependencies(":presentation"))
        .containsNoneOf(":integration:ssh", ":integration:provider", ":data:persistence-room")
}
```

- [ ] Run `./gradlew :architecture-tests:test`; expect RED because module declarations/allowed-edge manifest are incomplete.
- [ ] Add the empty modules and explicit allowed dependency edges; keep `:app` packaging-only, `:presentation` adapter-blind and `:platform:android` the only concrete production composition root; add `verifyArchitecture` as a CI-required task.
- [ ] Add the minimal Compose presentation that renders `Cockpit`, host it from the packaging Activity through the process component, and build an API 36 debug APK; do not add domain screens yet.
- [ ] Test `ImmutableBytes` before implementation for defensive input/output copies, content equality/hash and no platform/library types in its public signature; then implement the smallest language-primitive value object.
- [ ] Add fake clock/IDs/dispatchers and enforce a lint rule against direct wall-clock/random/default-dispatcher use in domain/runtime packages.
- [ ] Write ADR-001…004 as accepted implementation records referencing the Approved System Architecture, without redefining it.
- [ ] Run `./gradlew :architecture-tests:test :app:assembleDebug lint`; expect all PASS and an installable shell APK.
- **Slice integration label:** `build: bootstrap cockpit module boundaries and test harness`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E1.1 | Repository + pinned build | Add `VersionCatalogPolicyTest.rejectsDynamicAndUnverifiedDependencies`; run `./gradlew :architecture-tests:test --tests '*VersionCatalogPolicyTest'`; RED = test task/module or verification metadata is absent. | Create wrapper/root catalog, exact compatible versions and verification metadata only; rerun the same command → PASS; commit `build: pin verified android toolchain`. |
| E1.2 | Module graph | Add `ModuleGraphTest.presentationCannotReachAdapters`; run `./gradlew :architecture-tests:test --tests '*ModuleGraphTest*'`; RED = forbidden/undeclared graph is reported. | Declare empty modules and allowed-edge manifest only; same command → PASS; commit `build: enforce cockpit module graph`. |
| E1.3 | Domain primitives | Add `ImmutableBytesTest.defensivelyCopiesInputAndOutput`; run `./gradlew :core:domain:test --tests '*ImmutableBytesTest'`; RED = typed bytes/IDs are missing or mutable. | Implement typed IDs, logical credential ref and `ImmutableBytes` only; rerun the exact command → PASS; commit `build: add immutable domain primitives`. |
| E1.4 | Deterministic test ports | Add `InjectedSourcePolicyTest.domainRuntimeUseOnlyInjectedClockIdsAndDispatchers`; run `./gradlew :architecture-tests:test --tests '*InjectedSourcePolicyTest'`; RED = ports/lint rule absent or direct source detected. | Add clock/ID/dispatcher test ports and narrow source rule only; rerun the exact command → PASS; commit `test: enforce deterministic runtime sources`. |
| E1.5 | Android shell/composition | Add `CockpitShellTest.displaysAppNameThroughCompositionRoot`; run `./gradlew :presentation:testDebugUnitTest --tests '*CockpitShellTest'`; RED = shell/process binding is missing. | Add packaging-only app, explicit process component and minimal Compose root; run `./gradlew :presentation:testDebugUnitTest :app:assembleDebug` → PASS; commit `build: launch empty cockpit shell`. |
| E1.6 | CI + records | Add `ArchitectureEvidenceTest.requiresAcceptedFoundationAdrsAndCiTasks`; run `./gradlew :architecture-tests:test --tests '*ArchitectureEvidenceTest'`; RED = ADR/CI task evidence missing. | Add ADR-001…004 and required CI commands without behavior; run `./gradlew :architecture-tests:test :app:assembleDebug lint` → PASS; commit `docs: record foundation architecture evidence`. |

**Reviewer Gate 1:** Reject if the app can import a concrete adapter, any dynamic dependency remains, or CI cannot produce the shell APK from a clean checkout.

### Task 2: Gate A — Prove the SSH Transport Contract

**Depends on:** Task 1  
**Runtime-verifiable result:** A disposable spike can prove exact request bytes, separated SSH stages, delivery facts, cancellation and no session reuse against controlled servers  
**Traceability:** `B36.5`, `B36.8`, `B36.10`, `SA32.3`, `SA32.9`, `SA32.13`

**Files:**

- Create: `spikes/ssh-transport/src/main/kotlin/dev/cockpit/spike/ssh/SshCandidate.kt`
- Create: `spikes/ssh-transport/src/test/kotlin/dev/cockpit/spike/ssh/ExactExecPayloadContractTest.kt`
- Create: `spikes/ssh-transport/src/test/kotlin/dev/cockpit/spike/ssh/StageAndCancellationContractTest.kt`
- Create: `spikes/ssh-transport/src/test/kotlin/dev/cockpit/spike/ssh/SessionIsolationContractTest.kt`
- Create: `spikes/ssh-transport/src/testFixtures/kotlin/dev/cockpit/spike/ssh/RecordingSshServer.kt`
- Create: `spikes/ssh-transport/open-ssh/{Dockerfile,sshd_config,record-command.sh}`
- Create: `docs/evidence/gate-a-ssh-transport.md`
- Create after GO: `docs/adr/ADR-009-ssh-transport.md`, `docs/adr/ADR-010-frozen-target-resolution.md`

**Spike contract:**

```kotlin
interface SshCandidate : AutoCloseable {
    suspend fun prepare(target: FrozenSshTarget): CandidatePreparedTransport
    suspend fun authenticate(prepared: CandidatePreparedTransport, credential: TestCredential): CandidateSession
    suspend fun exec(session: CandidateSession, exactCommandBytes: ByteArray): Flow<CandidateFact>
}

sealed interface CandidateFact {
    data class Stage(val value: DeliveryStage) : CandidateFact
    data class Stdout(val sequence: Long, val bytes: ByteArray) : CandidateFact
    data class Stderr(val sequence: Long, val bytes: ByteArray) : CandidateFact
    data class Exit(val code: Int?) : CandidateFact
}
```

- [ ] Add Apache MINA SSHD, SSHJ and mwiede/JSch only to the isolated spike module. Record exact resolved coordinate, version, checksum, Android method count and minSdk compatibility for each; no production module may depend on them.
- [ ] Write a parameterized RED contract suite before adapter wrappers. Fixtures include ASCII, UTF-8, leading/trailing spaces, tabs, LF vs CRLF, quotes, backslashes, ANSI bytes, bidi UTF-8, and embedded zero byte. A candidate may explicitly reject an unsupported byte sequence before Envelope creation, but may never transform it.

```kotlin
@ParameterizedTest
@MethodSource("commandFixtures")
fun `accepted exec payload reaches protocol server byte for byte`(bytes: ByteArray) = runTest {
    val result = candidate.execAuthenticated(bytes)
    if (result is Accepted) assertArrayEquals(bytes, server.recordedExecPayload.single())
    else assertEquals(RejectionPoint.BEFORE_ENVELOPE, result.point)
}
```

- [ ] Make `RecordingSshServer` pause independently at socket connected, key exchange, host-key observed, user-auth requested, auth accepted, channel open, first exec-request write, request accepted and exit status. Capture stdout/stderr as independent arbitrary byte streams.
- [ ] Prove that `prepare` performs network connection/handshake/host-key verification but sends no username proof, Credential, authentication request, environment request or exec request.
- [ ] Prove a changed Host Key fails before auth; prove connecting uses packed frozen address + port and never re-resolves/mutates target fields.
- [ ] Prove no connection or authenticated session pooling/reuse across ticket/attempt equivalents. A second execution must have a distinct socket/session identity even when host and Credential are the same.
- [ ] Instrument the adapter at the actual transport-write boundary and show which stages it can truthfully emit: `PREPARATION_NOT_STARTED`, `TRANSPORT_PREPARED_UNAUTHENTICATED`, `AUTH_STARTED`, `AUTHENTICATED_NO_EXEC`, `EXEC_CHANNEL_OPEN`, `REQUEST_WRITE_STARTED`, `REQUEST_SENT`, `SERVER_ACCEPTED`, `EXIT_STATUS_RECEIVED`, `CHANNEL_CLOSED_WITHOUT_EXIT`. Unsupported certainty must collapse conservatively, never be invented.
- [ ] Test cancel/timeout before auth, during auth, after auth/before request, during request write, after server accept and before exit. Assert sockets close, late callbacks are discarded, and post-write ambiguity is reported.
- [ ] Test binary stdout/stderr ordering, huge/slow output and bounded backpressure without decoding or merging channels.
- [ ] Run the same semantically safe fixtures against a separately configured OpenSSH container to detect behavior hidden by the in-process test server. The in-process server proves protocol payload bytes; OpenSSH proves interoperability. Neither alone is sufficient.
- [ ] Build the selected candidate adapter in an API 28 and API 36 Android instrumentation harness; connect to the controlled LAN/loopback test server and repeat exact-byte/stage/cancel tests.
- [ ] Run `./gradlew :spikes:ssh-transport:test :spikes:ssh-transport:connectedCheck`; expect PASS for exactly one selected candidate or an explicit NO-GO report.
- [ ] Write Gate A evidence. On GO, accept ADR-009/010, promote only the chosen pinned dependency to the version catalog, and record an adapter limitation matrix. The spike wrapper itself is never promoted. Once Gate C exists, any production promotion must bind the candidate behind separate `TransportPreparationPort`, `AuthorizedExecutionPort` and `ExecutionControlPort`, with an architecture test proving the preparation binding has no auth/exec API. On failure, try the next candidate; if none can meet the contract, stop with `System Architecture Change Request` rather than adding String normalization.
- **Slice integration label:** `spike: prove ssh exact-byte and stage contract`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E2.1 | Recording server | Add `StageAndCancellationContractTest.pausesAtEveryProtocolBoundary`; run `./gradlew :spikes:ssh-transport:test --tests '*pausesAtEveryProtocolBoundary'`; RED = fixture/capture stages absent. | Implement only the controllable recording fixture; same command → PASS; commit `test: add recording ssh protocol fixture`. |
| E2.2 | Exact command bytes | Add `ExactExecPayloadContractTest.acceptedPayloadIsByteIdentical`; run `./gradlew :spikes:ssh-transport:test --tests '*ExactExecPayloadContractTest'`; RED = no candidate preserves/rejects fixtures at the allowed point. | Wrap the first candidate without normalization; rerun the exact command → PASS or recorded candidate NO-GO; commit `spike: test ssh exact exec bytes`. |
| E2.3 | Stage/cancel truth | Add `StageAndCancellationContractTest.cancelWindowsAreConservative`; run `./gradlew :spikes:ssh-transport:test --tests '*StageAndCancellationContractTest'`; RED = a cancel window reports optimistic delivery or leaks a socket. | Add boundary instrumentation/close handling only; rerun the exact command → PASS; commit `spike: prove ssh delivery and cancel stages`. |
| E2.4 | Host identity/session isolation | Add `SessionIsolationContractTest.changedKeyFailsBeforeAuthAndSessionsAreUnique`; run `./gradlew :spikes:ssh-transport:test --tests '*SessionIsolationContractTest'`; RED = auth begins on key mismatch or socket/session ID is reused. | Add frozen endpoint/key verification and per-attempt session lifetime; rerun the exact command → PASS; commit `spike: prove ssh target and session isolation`. |
| E2.5 | OpenSSH + Android | Add `SshCandidateAndroidContractTest.exactBytesPassOnApi28And36`; run `./gradlew :spikes:ssh-transport:connectedCheck`; RED = chosen candidate lacks Android/OpenSSH proof. | Add only the controlled OpenSSH lane and instrumentation wrapper; same command → PASS; commit `spike: verify ssh candidate on android`. |
| E2.6 | Gate A evidence/promotion rule | Add `GateAEvidenceTest.selectedCoordinateMatchesPassingCandidate`; run `./gradlew :architecture-tests:test --tests '*GateAEvidenceTest'`; RED = GO evidence/coordinate/hash absent or mismatched. | Write limitation matrix/evidence, pin only the passing candidate and accept ADR-009/010; run `./gradlew :spikes:ssh-transport:test :spikes:ssh-transport:connectedCheck :architecture-tests:test` → PASS; commit `docs: record gate a ssh decision`. |

**Reviewer Gate 2 / Gate A GO criteria:** Accepted command bytes are identical at the protocol server; pre-auth/auth/exec are independently controllable; host key is checked before Credential use; stale sessions cannot be reused; stdout/stderr remain bytes; delivery stages are evidence-based; all cancel windows fail conservatively; Android compatibility is demonstrated.

### Task 3: Gate B — Canonical ExecutionEnvelope and ADR-005

**Depends on:** Task 1; Task 11 requires both Gate A and Gate B  
**Runtime-verifiable result:** A versioned encoder produces deterministic bytes/digests and rejects every ambiguous or unsupported execution input  
**Traceability:** `B36.4`, `B36.5`, `B36.6`, `B36.10`, `UX34.8`, `UX34.9`, `SA32.3`, `SA32.8`, `SA32.13`

**Files:**

- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/execution/{ExecutionEnvelope,FrozenSshTarget,SshWirePlan}.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/execution/ExecutionIdentifiers.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/execution/{CanonicalEnvelopeEncoder,CanonicalEnvelopeDecoder}.kt`
- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/gate/ExecutionEnvelopeFactory.kt`
- Create: `core/domain/src/test/kotlin/dev/cockpit/domain/execution/CanonicalEnvelopeGoldenTest.kt`
- Create: `core/domain/src/test/kotlin/dev/cockpit/domain/execution/ExecutionEnvelopePropertyTest.kt`
- Create: `core/domain/src/test/resources/envelope-v1/*.bin`
- Create: `docs/evidence/gate-b-canonical-envelope.md`
- Create after GO: `docs/adr/ADR-005-canonical-envelope-encoding.md`

**Canonical types:**

```kotlin
data class ExecutionEnvelope internal constructor(
    val envelopeSchemaVersion: UInt,
    val canonicalEncodingVersion: UInt,
    val runId: RunId,
    val toolCallId: ToolCallId,
    val skill: VersionedSkillId,
    val operation: OperationId,
    val sshTarget: FrozenSshTarget,
    val credentialRef: CredentialReference,
    val workingDirectoryBytes: ImmutableBytes,
    val shellMode: ShellMode,
    val exactCommandBytes: ImmutableBytes,
    val environmentEntries: List<CanonicalEnvironmentEntry>,
    val stdinMode: StdinMode,
    val timeoutMillis: ULong,
    val wirePlanVersion: UInt,
)

data class CanonicalEnvelope(
    val bytes: ImmutableBytes,
    val targetDigest: TargetDigest,
    val envelopeDigest: EnvelopeDigest,
)
```

`ExecutionIdentifiers.kt` is introduced by E3.1 and owns the execution-only IDs used above, including `SshHostId`, `VersionedSkillId`, `OperationId`, `TargetDigest` and wire-plan/version identifiers. It does not depend on the later Skill implementation. The logical, non-secret `CredentialReference` already exists from E1.3, so Gate B and Gate D1 remain independent branches after Task 1.

- [ ] Write golden tests first for the complete Approved schema: frozen `sshHostId/revision`, canonical hostname, packed address, port, username bytes, host-key algorithm/fingerprint, Credential reference, working directory, shell mode, exact command, sorted environment, `stdinMode=NONE`, timeout and wire-plan version.
- [ ] Add property tests proving semantic equality → identical bytes/digest and every semantic byte change → different digest. Specifically vary whitespace, LF/CRLF, zero byte, target address/port/username/fingerprint/revision and environment order/duplicates.
- [ ] Add decoder tests proving known v1 fixtures remain verifiable and an unknown schema/encoder/wire-plan version fails closed.
- [ ] Run `./gradlew :core:domain:test --tests '*CanonicalEnvelope*'`; expect RED before implementing the encoder.
- [ ] Implement a dedicated deterministic binary encoder with fixed field order, unique unsigned integer representation, length-prefixed bytes and canonical map ordering. Do not use JSON, locale, platform charset, Unicode normalization or general-purpose UI serialization.
- [ ] Implement `ExecutionEnvelopeFactory` validation: require exactly one already-resolved packed address from the trusted target resolver (never defer DNS to execution), allow non-interactive/no-PTY/`stdinMode=NONE` only, reject duplicate env keys, secrets in env, unsupported shell shape and any value it cannot encode unambiguously.
- [ ] Make every byte-bearing field deeply immutable: `ImmutableBytes` copies mutable inputs, returns copies on export and implements content equality/hash; Environment factory stores a defensively copied canonical list. Add a mutation-after-construction test proving canonical bytes/digests cannot change.
- [ ] Persist the first v1 golden fixtures under source control, with a fixture manifest containing human-readable input and expected SHA-256. Run tests under at least two JVM locales/time zones.
- [ ] Run `./gradlew :core:domain:test`; expect PASS and bit-identical golden artifacts.
- [ ] Write Gate B evidence, then accept ADR-005 including compatibility/fail-closed policy. No later task may edit v1 bytes; changes require a new encoder version plus old fixture support.
- **Slice integration label:** `security: define canonical immutable execution envelope`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E3.1 | Full immutable schema | Add `ExecutionEnvelopeFactoryTest.requiresCompleteFrozenTargetAndWireFields`; run `./gradlew :core:domain:test --tests '*ExecutionEnvelopeFactoryTest'`; RED = types/factory or mandatory rejection is absent. | Add immutable value objects/factory validation only; same command → PASS; commit `security: define execution envelope values`. |
| E3.2 | Canonical v1 bytes | Add `CanonicalEnvelopeGoldenTest.v1FixtureIsBitExact`; run `./gradlew :core:domain:test --tests '*CanonicalEnvelopeGoldenTest'`; RED = encoder/fixture digest missing. | Implement fixed-order binary v1 encoder and first fixture; rerun the exact command → PASS; commit `security: encode canonical envelope v1`. |
| E3.3 | Canonical properties | Add `ExecutionEnvelopePropertyTest.semanticByteChangeChangesDigest`; run `./gradlew :core:domain:test --tests '*ExecutionEnvelopePropertyTest'`; RED = collision/non-determinism/mutable input case fails. | Add canonical environment ordering, defensive copies and digest factory only; rerun the exact command → PASS; commit `test: prove envelope canonical properties`. |
| E3.4 | Decoder compatibility/fail closed | Add `CanonicalEnvelopeGoldenTest.unknownVersionFailsClosed`; run `./gradlew :core:domain:test --tests '*CanonicalEnvelopeGoldenTest'`; RED = decoder accepts/ignores unknown version. | Add versioned decoder for checked-in v1 only; rerun the exact command → PASS; commit `security: verify envelope versions fail closed`. |
| E3.5 | Gate B evidence | Add `GateBEvidenceTest.fixtureManifestMatchesGoldenDigests`; run `./gradlew :architecture-tests:test --tests '*GateBEvidenceTest'`; RED = evidence/manifest/ADR absent. | Record locale/time-zone runs and accept ADR-005; run `./gradlew :core:domain:test :architecture-tests:test` → PASS; commit `docs: record gate b canonical envelope`. |

**Reviewer Gate 3 / Gate B GO criteria:** deterministic, versioned, byte-preserving encoding; complete frozen target; all ambiguity rejected before authority analysis; old versions remain verifiable; unknown versions fail closed.

### Task 4: Gate C — Execution Authorization Pipeline and ADR-006

**Depends on:** Tasks 1 and 3; uses fakes, not real SSH  
**Runtime-verifiable result:** Deterministic race tests prove T6-P → T6-A → ticket-scoped auth → T6-B → same-turn `SEND_STARTED`, with no lock held across external I/O  
**Traceability:** `B36.6`, `B36.8`, `B36.9`, `B36.10`, `SA32.2`, `SA32.3`, `SA32.4`, `SA32.13`

**Files:**

- Create: `integration/execution-api/src/main/kotlin/dev/cockpit/execution/api/{TransportPreparationPermit,PreparedSshTransport,AuthorizedExecutionTicket,ExecutionFence,SendStartPermit,ExecutionCapabilityVerifier,TransportPreparationPort,AuthorizedExecutionPort,ExecutionControlPort}.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/safety/{PermissionDecision,RuntimeRisk,PermissionProofRef}.kt`
- Create: `security/permission-api/src/main/kotlin/dev/cockpit/security/permission/api/CurrentSafetyPolicyPort.kt`
- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/gate/{ExecutionAuthorizationGate,ProcessLocalExecutionCapabilityIssuer,CurrentSafetyFloor,ConflictObligationPort}.kt`
- Create: `core/runtime/src/test/kotlin/dev/cockpit/runtime/gate/ExecutionAuthorizationRaceTest.kt`
- Create: `test-support/src/main/kotlin/dev/cockpit/testing/execution/{FakeTransportPreparationPort,FakeAuthorizedExecutionPort,FakeExecutionControlPort}.kt`
- Create: `docs/evidence/gate-c-execution-authorization.md`
- Create after GO: `docs/adr/ADR-006-execution-authorization.md`, `docs/adr/ADR-015-current-safety-floor.md`

**Authority boundary:**

```kotlin
interface ExecutionAuthorizationGate {
    suspend fun prepare(command: PrepareTransport): PreparationDecision
    suspend fun authorizeAuth(command: AuthorizeAuthentication): AuthorizedExecutionTicket
    suspend fun authorizeSend(command: AuthorizeSend): SendStartDecision
}

sealed interface SendStartDecision {
    data class StartNow internal constructor(internal val permit: SendStartPermit) : SendStartDecision
    data class Rejected(val reason: AuthorityRejection) : SendStartDecision
}
```

- [ ] Create compile-time tests first: capability API types are opaque; only `ProcessLocalExecutionCapabilityIssuer` can mint registered nonces; fake Skill/UI/Provider modules cannot import issuer or transport/auth/session internals; production `execute` has no overload accepting Envelope, command String or raw session. Assert `TransportPreparationPort.prepare(permit)`, `AuthorizedExecutionPort.execute(ticket)` and `ExecutionControlPort.cancel(toolCallId)` are separate injected surfaces and no production façade reunifies them.
- [ ] Write a deterministic scheduler model for owner epoch, Run version, safety epoch, permission proof, obligation watermark, prepared transport, credential rotation, fence and delivery stage.
- [ ] Write RED interleaving tests for every System Architecture §27.10 case: Cancel/safety/host mutation/obligation before Ticket, during auth, after auth before send, at send linearization, and after `SEND_STARTED`.

```kotlin
@Test fun `cancel after auth before final permit closes session and sends no exec`() = schedulerTest {
    val prepared = fixture.prepareUnauthenticated()
    val ticket = fixture.issueTicket(prepared)
    fixture.authenticate(ticket)
    fixture.cancelRunLinearized()
    fixture.tryFinalSend(ticket)
    assertThat(transport.execRequests).isEmpty()
    assertThat(transport.closedSessionIds).contains(ticket.sessionId)
}
```

- [ ] Implement T6-P as a short transaction that validates owner/run/envelope/frozen target/safety/permission/obligation and issues an expiring preparation permit; release transaction before handshake.
- [ ] Implement T6-A as a short transaction after verified unauthenticated preparation: revalidate all authority, freeze exact Credential rotation, create `HELD_PRE_SEND` fence and one-use ticket; release before Vault/auth I/O.
- [ ] Implement T6-B as a final short transaction after ticket-scoped auth: revalidate latest authority and issue a non-copyable one-use `SendStartPermit` bound to exact session/ticket/attempt. Consume it at the actual write boundary in the same non-yielding turn and atomically mark `SEND_STARTED`.
- [ ] Add an explicit RED/GREEN distinction test: `ExecutionCommitted`, authentication success and `SendStartPermit` issuance each remain pre-send facts; only the adapter-observed first execution-wire `REQUEST_WRITE_STARTED` establishes `SEND_STARTED`.
- [ ] Prove no database/actor/global lock is held during connect, handshake, Vault auth, SSH auth, exec, output or Provider I/O using an instrumented lock detector.
- [ ] Prove prepared handles, ticket, lease, authenticated session and send permit are ephemeral, owner-epoch-bound, non-serializable and unusable after process recreation.
- [ ] Inject a narrow `ExecutionCapabilityVerifier` into Vault/SSH Gateway; prove forged/unregistered/replayed nonce handles fail closed and that the production composition root binds exactly the Runtime-owned verifier/issuer pair. Promote a Gate-A candidate only through distinct preparation, authorized-execution and cancellation bindings; the preparation binding exposes no authentication/exec call.
- [ ] Implement safety merge as `most restrictive(frozen historical policy, current mandatory safety floor)`; append the current safety version/decision as execution facts without mutating Snapshot.
- [ ] Run `./gradlew :core:runtime:test :integration:execution-api:test --tests '*Authorization*'`; expect all race and compile-boundary tests PASS.
- [ ] Write Gate C evidence, accept ADR-006/015, and include linearization-point diagrams plus forbidden API inventory.
- **Slice integration label:** `security: prove ticketed execution authorization pipeline`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E4.1 | Narrow opaque Ports | Add `ExecutionApiSurfaceTest.preparationExecutionAndCancelAreSeparateOpaqueSurfaces`; run `./gradlew :integration:execution-api:test --tests '*ExecutionApiSurfaceTest'`; RED = symbols absent or one façade exposes multiple capabilities. | Add only opaque types and the three Port signatures; same command → PASS; commit `security: split execution capability ports`. |
| E4.2 | Deterministic race fixture | Add `ExecutionAuthorizationRaceTest.fixtureEnumeratesAllLinearizationPoints`; run `./gradlew :core:runtime:test --tests '*ExecutionAuthorizationRaceTest.fixtureEnumeratesAllLinearizationPoints'`; RED = scheduler/facts absent. | Implement test scheduler/fakes only; rerun the exact command → PASS; commit `test: add execution race scheduler`. |
| E4.3 | T6-P preparation | Add `ExecutionAuthorizationRaceTest.invalidAuthorityCannotPrepare`; run `./gradlew :core:runtime:test --tests '*ExecutionAuthorizationRaceTest.invalidAuthorityCannotPrepare'`; RED = preparation permit is issued or API absent. | Implement T6-P short transaction/expiry only; rerun the exact command → PASS; commit `security: gate transport preparation`. |
| E4.4 | T6-A auth authority | Add `ExecutionAuthorizationRaceTest.cancelBeforeTicketPreventsAuthentication`; run `./gradlew :core:runtime:test --tests '*ExecutionAuthorizationRaceTest.cancelBeforeTicketPreventsAuthentication'`; RED = Ticket/auth is reachable after stale authority. | Implement revalidation, fence and one-use Ticket only; rerun the exact command → PASS; commit `security: gate ticket scoped authentication`. |
| E4.5 | T6-B send boundary | Add `ExecutionAuthorizationRaceTest.cancelAfterAuthSendsNoExec`; run `./gradlew :core:runtime:test --tests '*ExecutionAuthorizationRaceTest.cancelAfterAuthSendsNoExec'`; RED = request write occurs or stage is optimistic. | Implement final permit consumption at actual write linearization only; rerun the exact command → PASS; commit `security: gate exact execution send`. |
| E4.6 | Process/safety invalidation | Add `ExecutionAuthorizationRaceTest.processLossAndSafetyTighteningInvalidateCapabilities`; run `./gradlew :core:runtime:test --tests '*ExecutionAuthorizationRaceTest.processLossAndSafetyTighteningInvalidateCapabilities'`; RED = old handle/relaxed rule remains usable. | Add owner/safety epochs and most-restrictive merge facts only; rerun the exact command → PASS; commit `security: invalidate stale execution authority`. |
| E4.7 | Composition/evidence | Add `ExecutionCompositionTest.productionBindingsCannotReunifyPorts`; run `./gradlew :architecture-tests:test --tests '*ExecutionCompositionTest'`; RED = broad binding/issuer exposure or evidence absent. | Bind distinct ports/verifier, record diagrams and accept ADR-006/015; run `./gradlew :core:runtime:test :integration:execution-api:test :architecture-tests:test` → PASS; commit `docs: record gate c authorization evidence`. |

**Reviewer Gate 4 / Gate C GO criteria:** no path reaches authentication without exact Ticket authority; no path reaches exec write without fresh final permit; stale authenticated sessions cannot be adopted; all pre-send races are `NOT_STARTED`; post-write ambiguity is preserved; no coordination lock spans external I/O.

### Task 5: Gate D — Credential Vault and Scoped Lease

**Depends on:** D1 depends on Task 1; D2 depends on Gate C; Reviewer Gate 5 closes after both checkpoints  
**User/runtime-verifiable result:** D1 proves a Keystore-backed Vault and invocation-bound Provider credential use early enough for chat; D2 proves exact Ticket-scoped SSH credential use without broadening the Vault surface  
**Traceability:** `B36.5`, `B36.9`, `B36.10`, `SA32.3`, `SA32.10`, `SA32.13`

**Files:**

- Create in D1: `security/vault-api/src/main/kotlin/dev/cockpit/security/vault/api/{CredentialMetadata,CredentialAdminPort,ProviderCredentialLeasePort}.kt`
- Create in D1: `integration/provider-api/src/main/kotlin/dev/cockpit/provider/api/{ProviderIdentifiers,ProviderInvocationAuthority,ProviderAuthorizationHandle}.kt`
- Create: `security/vault/src/main/kotlin/dev/cockpit/security/vault/{AndroidCredentialVault,EnvelopeCipher,KeystoreKeyManager}.kt`
- Create in D1: `security/vault/src/androidTest/kotlin/dev/cockpit/security/vault/{VaultRoundTripTest,VaultInvalidationTest,VaultLeakageTest,ProviderCredentialAuthorityTest}.kt`
- Create in D2: `security/vault-api/src/main/kotlin/dev/cockpit/security/vault/api/{CredentialLease,SshCredentialLeasePort}.kt`
- Create in D2: `security/vault/src/androidTest/kotlin/dev/cockpit/security/vault/SshTicketScopedLeaseTest.kt`
- Create: `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `docs/evidence/gate-d-credential-vault.md`, `docs/evidence/gate-d1-vault-provider.md`, `docs/evidence/gate-d2-ssh-ticket-credential.md`
- Create after GO: `docs/adr/ADR-011-credential-vault.md`

**Vault API:**

```kotlin
interface CredentialAdminPort {
    suspend fun create(input: NewCredential, authPolicy: DeviceAuthPolicy): CredentialReference
    suspend fun metadata(ref: CredentialReference): CredentialMetadata
    suspend fun rotate(ref: CredentialReference, replacement: NewCredential): CredentialRotation
    suspend fun delete(ref: CredentialReference)
}
```

**D1 checkpoint — Vault core / Provider credential:**

- [ ] Write instrumentation RED tests for Keystore-wrapped envelope encryption, auth-per-use and timed-auth keys, app restart, rotation, deletion, invalidation, device lock, biometric enrollment change and backup exclusion.
- [ ] Write a leakage test that scans Room files, blob directory, SavedState, logcat test buffer, exception text and generated test reports for seeded Provider canary secrets.
- [ ] Implement random per-secret data keys, authenticated encryption and Keystore wrapping. Persist only ciphertext, nonce/tag, logical reference, rotation version and non-secret metadata.
- [ ] Keep admin and Provider-use ports separate. `ProviderCredentialLeasePort` accepts only a one-use `ProviderInvocationAuthority` bound to invocation/profile/model/purpose/credential rotation/owner epoch/expiry and returns an opaque `ProviderAuthorizationHandle`; it never returns an API-key `String` and cannot accept SSH authority.
- [ ] Configure Android Auto Backup/data extraction exclusion for Vault/database/blob paths and assert the release manifest merger output contains those exclusions.
- [ ] Run `./gradlew :security:vault:connectedCheck :app:processReleaseManifest`; expect PASS and zero canary hits. Write `gate-d1-vault-provider.md` with `GO` before Task 8 starts; do not yet close Reviewer Gate 5.

**D2 checkpoint — SSH Ticket-scoped credential:**

- [ ] After Gate C, write RED tests that `SshCredentialLeasePort.acquire(ticket)` validates ticket ID, exact `PreparedSshTransport` identity, Run/attempt/owner epoch, target digest, exact credential rotation, `purpose=SSH_AUTH` and expiry. A Provider authority, forged nonce, preparation permit or pre-Ticket handle must be rejected before secret use; no pre-Ticket `CredentialLease` may exist.
- [ ] Return a one-use `AutoCloseable` credential operation; do not return password/private-key `String`. Invalidate/close it immediately after the authentication attempt, Cancel, Ticket invalidation or timeout; never cache a decrypted key.
- [ ] Prove rotation after permission but before T6-A is revalidated and frozen; rotation after Ticket issue cannot silently substitute a newer secret. Credential secret rotation does not mutate the frozen SSH target.
- [ ] Prove production composition injects only `CredentialAdminPort` into Settings, only `ProviderCredentialLeasePort` into `ProviderInvocationGate`, only `SshCredentialLeasePort` into the SSH authentication boundary, and never exposes the concrete Vault outside `:platform:android`.
- [ ] Run `./gradlew :security:vault:connectedCheck :architecture-tests:test`; expect PASS on API 28, API 36 and one hardware-backed device. Write `gate-d2-ssh-ticket-credential.md`, update the Gate-D index, then accept ADR-011.
- [ ] Commit D1 and D2 separately using the Execution Tasks below; the Task 5 Reviewer Gate is evaluated only after D2.

#### Execution Tasks

| ID | Checkpoint / focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E5.1 | D1 encryption core | Add `VaultRoundTripTest.keystoreWrappedCiphertextSurvivesRestart`; run `./gradlew :security:vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.security.vault.VaultRoundTripTest`; RED = Vault/ciphertext round-trip absent. | Implement per-secret data key + authenticated wrapping only; rerun the exact command → PASS; commit `security: add keystore vault core`. |
| E5.2 | D1 auth/invalidation/leakage | Add `VaultLeakageTest.canaryNeverLeavesVaultArtifacts` and `VaultInvalidationTest.neverFallsBackToPlaintext`; run `./gradlew :security:vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.security.vault.VaultLeakageTest,dev.cockpit.security.vault.VaultInvalidationTest`; RED = canary hit or invalidation state absent. | Add device-auth policy, invalidation recovery, exclusions and redaction only; rerun the exact command → PASS; commit `security: harden vault auth and leakage`. |
| E5.3 | D1 Provider authority | Add `ProviderCredentialAuthorityTest.handleIsBoundToInvocationProfileModelAndRotation`; run `./gradlew :security:vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.security.vault.ProviderCredentialAuthorityTest`; RED = opaque handle/authority validation absent. | Add D1 Provider authority/lease surface and one-use validation only; rerun the exact command → PASS; commit `security: scope provider credential handles`. |
| E5.4 | D1 GO evidence | Add `GateD1EvidenceTest.providerPrerequisiteIsComplete`; run `./gradlew :architecture-tests:test --tests '*GateD1EvidenceTest'`; RED = D1 evidence/device matrix/manifest proof absent. | Write D1 evidence/index state; run `./gradlew :security:vault:connectedCheck :app:processReleaseManifest :architecture-tests:test` → PASS; commit `docs: record gate d1 provider vault go`. |
| E5.5 | D2 SSH lease | Add `SshTicketScopedLeaseTest.onlyExactTicketCanAcquireSelectedRotation`; run `./gradlew :security:vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.security.vault.SshTicketScopedLeaseTest`; RED = lease API absent or forged/provider authority accepted. | Add `SshCredentialLeasePort` and Ticket-bound one-use operation only; rerun the exact command → PASS; commit `security: scope ssh credential lease to ticket`. |
| E5.6 | D2 rotation/lifetime | Add `SshTicketScopedLeaseTest.rotationAndCancelInvalidateLease`; run `./gradlew :security:vault:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.security.vault.SshTicketScopedLeaseTest`; RED = newer secret substitution or surviving lease is observed. | Add exact-rotation and lifecycle invalidation only; rerun the exact command → PASS; commit `security: enforce ssh lease lifetime`. |
| E5.7 | D2 composition + Gate D close | Add `GateD2EvidenceTest.apiDeviceMatrixAuthorityChainAndSeparatePortsAreComplete`; run `./gradlew :architecture-tests:test --tests '*GateD2EvidenceTest'`; RED = separate production binding, D2 evidence, ADR or index is absent. | Add only separate Vault port bindings, run API 28/36/hardware evidence, complete Gate-D index and accept ADR-011; run `./gradlew :security:vault:connectedCheck :architecture-tests:test` → PASS; commit `docs: close gate d credential evidence`. |

**Gate D1 GO criteria:** Keystore encryption/device auth, Provider invocation-bound one-use authorization, rotation/invalidation, backup exclusion and leakage tests pass; Task 8 can chat without receiving secret bytes.  
**Gate D2 GO criteria:** only exact Ticket-scoped SSH auth can use the selected rotation; lease lifetime and invalidation fail closed; API/device matrix and separation tests pass. Together D1+D2 satisfy Gate D.

**Reviewer Gate 5 / Gate D GO criteria:** Reject until both D1 and D2 evidence are GO. Reject if any consumer can obtain secret bytes or the concrete Vault, Provider and SSH authority types are interchangeable, rotation is silently substituted, invalidation falls back to plaintext, or leakage/backup/device-matrix evidence is incomplete.

### Task 6: Gate E — Android Background and Distribution Decision

**Depends on:** Task 1; must complete before Task 16 and before any external test release  
**User-visible result:** A policy/lifecycle spike demonstrates either a compliant, visible, stoppable `specialUse` FGS candidate or the approved foreground-only fallback; process kill always yields safe PAUSED semantics  
**Traceability:** `B36.9`, `UX34.7`, `UX34.12`, `UX34.13`, `SA32.2`, `SA32.11`, `SA32.13`

**Files:**

- Create: `platform/background-api/src/main/kotlin/dev/cockpit/background/api/RunHost.kt`
- Create: `platform/background/src/main/kotlin/dev/cockpit/background/{ForegroundRunService,RunNotificationFactory}.kt`
- Create: `platform/background/src/androidTest/kotlin/dev/cockpit/background/{ForegroundServiceLifecycleTest,ProcessDeathPauseTest}.kt`
- Create: `app/src/gateE/AndroidManifest.xml` (spike flavor only)
- Create: `docs/evidence/gate-e-android-background-distribution.md`
- Create: `docs/evidence/play-fgs-declaration-draft.md`
- Create after decision: `docs/adr/ADR-012-android-background-host.md`

**Lifecycle Port:**

```kotlin
interface RunHost {
    suspend fun startFromVisibleUserAction(runId: RunId, token: UserInitiationToken): RunHostResult
    suspend fun stop(runId: RunId, reason: HostStopReason)
    val capability: BackgroundCapability
}
```

- [ ] On the day Gate E runs, reread and timestamp the three official Android/Play sources in “Current Platform Evidence”; record current target API deadline, required permissions/types, restricted-start rules and declaration requirements. If rules changed, update implementation evidence—not the Frozen product promise.
- [ ] Write a RED instrumentation test around a fake long Run: only an explicit user action may start host execution; notification appears promptly; notification names the Agent/task, exposes Stop/Open, and is removed on terminal/paused state.
- [ ] Add kill tests for Activity loss, process death, user Stop, force stop, notification denial, FGS start denial and reboot. Durable fake Runtime facts must recover to safe `PAUSED`/attention; no external action is replayed.
- [ ] Implement the smallest Gate-E `RunHost` adapter. Keep Runtime ownership outside Service. Use `specialUse` only in the `gateE` manifest with `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` and a precise `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; do not merge it into production release yet.
- [ ] Test API 28, 31, 34 and 36 emulators plus at least one restrictive OEM device. Record background start failures and notification-permission behavior without adding retry loops that could duplicate work.
- [ ] Draft the Play declaration: core feature, why interruption/defer harms the user-started Run, how the user perceives/stops it, duration limits, and a reproducible demo-video script.
- [ ] Decide one build-time `BackgroundCapability`: `SPECIAL_USE_CANDIDATE` or `FOREGROUND_ONLY`. The latter disables background continuation but preserves normal foreground execution and safe pause/recovery.
- [ ] Run `./gradlew :platform:background:connectedCheck :app:processGateEManifest`; expect tests PASS for the selected branch and no production manifest claim yet.
- [ ] Write Gate E evidence and accept ADR-012. Schedule a release-day policy recheck; a successful local spike is not equivalent to Play approval.
- **Slice integration label:** `spike: decide android run hosting and distribution fallback`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E6.1 | RunHost contract | Add `ForegroundServiceLifecycleTest.onlyVisibleUserActionStartsHost`; run `./gradlew :platform:background:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.background.ForegroundServiceLifecycleTest`; RED = host/token contract absent. | Add `RunHost` and fake host only; rerun the exact command → PASS; commit `test: define android run host contract`. |
| E6.2 | Safe termination | Add `ProcessDeathPauseTest.killPersistsPausedWithoutReplay`; run `./gradlew :platform:background:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.background.ProcessDeathPauseTest`; RED = durable PAUSED fact absent or replay occurs. | Add minimal spike host/stop persistence only; rerun the exact command → PASS; commit `spike: prove safe run host termination`. |
| E6.3 | Candidate/fallback manifests | Add `BackgroundManifestTest.selectedBranchHasExactReviewedClaims`; run `./gradlew :platform:background:connectedCheck :app:processGateEManifest`; RED = claim/capability mismatch. | Implement `specialUse` spike and foreground-only branch behind build-time capability; same command → PASS; commit `spike: test background capability branches`. |
| E6.4 | API/OEM policy matrix | Add `BackgroundDeviceMatrixTest.requiredScenariosHaveRecordedResults`; on each named Gate-E device run `./gradlew :platform:background:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.background.BackgroundDeviceMatrixTest`; RED = API/OEM/scenario evidence rows missing. | Execute only the required device matrix and declaration rehearsal; rerun that exact per-device command → PASS; commit `test: record android background matrix`. |
| E6.5 | Gate E decision | Add `GateEEvidenceTest.decisionMatchesManifestAndFallback`; run `./gradlew :architecture-tests:test --tests '*GateEEvidenceTest'`; RED = timestamped sources/evidence/ADR absent. | Record one GO branch and accept ADR-012; run `./gradlew :platform:background:connectedCheck :app:processGateEManifest :architecture-tests:test` → PASS; commit `docs: record gate e distribution decision`. |

**Reviewer Gate 6 / Gate E GO criteria:** user initiation/perceptibility/stoppability are demonstrated; all termination paths persist safe pause; selected manifest is defensible against current official policy; foreground-only fallback is tested and shippable.

---

## Phase 1 — Conversation and Agent Continuity

### Task 7: Ship a Local Conversation-Only Agent Slice

**Depends on:** Task 1  
**User-visible result:** On a phone, the user can create/select an Agent, see Agent Detail, create multiple Conversations, switch/archive/restore them, and exchange persisted Messages with a debug-only deterministic Agent  
**Capabilities added:** Agent/Persona identity, Agent-first root IA, multiple Conversation continuity, Message timeline, destination-keyed drafts; no Task/Run/SSH  
**Traceability:** `B36.1`, `B36.2`, `UX34.1`–`UX34.5`, `UX34.7`, `UX34.14`, `UX35`, `SA32.1`, `SA32.5`, `SA32.13`

**Files:**

- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/agent/{Agent,Persona,AgentCapabilities}.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/conversation/{Conversation,Message,ComposerDestination,ConversationMessageDestination,Draft}.kt`
- Create: `core/application/src/main/kotlin/dev/cockpit/application/agent/{CreateAgent,ObserveAgents}.kt`
- Create: `core/application/src/main/kotlin/dev/cockpit/application/conversation/{CreateConversation,SendConversationMessage,ArchiveConversation,SaveDraft}.kt`
- Create: `core/application-api/src/main/kotlin/dev/cockpit/application/api/{AgentApplicationPort,ConversationApplicationPort,AgentConversationQueryPort}.kt`
- Create: `data/persistence-api/src/main/kotlin/dev/cockpit/persistence/api/ConversationRepository.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/{CockpitDatabase,AgentDao,ConversationDao,MessageDao,DraftDao}.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/migration/SchemaV1.kt`
- Create: `data/projection-models/src/main/kotlin/dev/cockpit/projection/model/{HomeProjection,AgentDetailProjection,ConversationProjection,TimelineItemProjection}.kt`
- Create: `data/projection/src/main/kotlin/dev/cockpit/projection/ConversationProjector.kt`
- Create: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/{home,agents,conversation}/*`
- Create: `app/src/debug/kotlin/dev/cockpit/mobile/debug/DeterministicDemoAgent.kt`
- Create tests mirroring all listed production files; export `data/persistence-room/schemas/1.json`

**Projection invariant:**

```kotlin
sealed interface TimelineItemProjection {
    data class MessageItem(val message: MessageProjection) : TimelineItemProjection
}
```

- [ ] Write pure domain tests first: Persona style cannot grant Skill/Permission; Agent owns multiple Conversations; archive is reversible; Message ordering uses persisted ordinal; no Character/Server hard-coded mode field exists.
- [ ] Write Room DAO/migration RED tests for Agent → Conversations → Messages and destination-keyed drafts. Use referential constraints and deterministic IDs; reject orphan Message.
- [ ] Write Compose UI RED tests for Home/Agents/Agent Detail/Conversation navigation, two Conversation switcher entries, archived restore, scroll stability, content descriptions and 200% font scale.
- [ ] Run `./gradlew :core:domain:test :data:persistence-room:test :presentation:testDebugUnitTest`; expect RED.
- [ ] Implement schema v1 and use cases. `Persona` owns voice/avatar/prompt presentation only; `AgentCapabilities` is a truthful summary and does not grant execution authority.
- [ ] Implement root navigation `Home / Agents / Activity / Settings`; Activity and Settings may show honest “not configured yet” empty states. Keep Agent—not model—prominent in list/detail/header.
- [ ] Implement unified Conversation shell with header, optional workspace chip placeholder, Message timeline and Composer. Store and submit drafts by `ConversationMessageDestination(conversationId, expectedConversationRevision)` so navigation or a stale revision never sends to a different/current Conversation implicitly.
- [ ] Add the deterministic responder only in debug/androidTest source sets. Release build with no Provider shows a clear “Configure a model provider” action and still preserves the user Message.
- [ ] Generate golden screenshots for empty, one-Agent, multiple-Conversation, long-message, dark theme and 200% font states under `docs/evidence/slice-07-conversation/`.
- [ ] Run `./gradlew :core:domain:test :data:persistence-room:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest lint`; expect PASS; install and demonstrate switching two conversations without state leakage.
- **Slice integration label:** `feat: add agent-first local conversation continuity`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E7.1 | Agent/Persona capability isolation | Add `AgentPersonaTest.personaCannotGrantCapability`; run `./gradlew :core:domain:test --tests '*AgentPersonaTest'`; RED = domain types/rule absent. | Implement Agent, Persona and capability summary only; same command → PASS; commit `feat: define agent and persona identity`. |
| E7.2 | Conversation/destination domain | Add `ConversationDestinationTest.messageRequiresExpectedConversationRevision`; run `./gradlew :core:domain:test --tests '*ConversationDestinationTest'`; RED = `ConversationMessageDestination` or stale rejection absent. | Implement Conversation/Message/Draft and exact destination value only; rerun the exact command → PASS; commit `feat: define conversation destination semantics`. |
| E7.3 | Schema 1 | Add `SchemaV1MigrationTest.agentConversationMessageDraftRoundTrip`; run `./gradlew :data:persistence-room:test --tests '*SchemaV1MigrationTest'`; RED = database/DAOs/schema export absent. | Add schema-1 entities/DAOs/repositories only; same command → PASS; commit `data: persist agent conversations`. |
| E7.4 | Application use cases/projections | Add `ConversationProjectorTest.onlyMessagesAreTopLevelItems`; run `./gradlew :data:projection:test --tests '*ConversationProjectorTest'`; RED = use case/query/projection absent. | Add create/archive/send/save use cases and read models only; same command → PASS; commit `feat: project local conversations`. |
| E7.5 | Agent-first navigation | Add `AgentNavigationTest.homeAgentsDetailAndSwitcherAreReachable`; run `./gradlew :presentation:testDebugUnitTest --tests '*AgentNavigationTest'`; RED = routes/screens absent. | Implement Home/Agents/Detail/switcher shell only; same command → PASS; commit `feat: add agent first navigation`. |
| E7.6 | Composer/draft continuity | Add `ConversationComposerTest.navigationCannotRedirectDraft`; run `./gradlew :presentation:testDebugUnitTest --tests '*ConversationComposerTest'`; RED = draft is sent using current-page inference. | Bind Composer/drafts to full message destination and quarantine stale revision; rerun the exact command → PASS; commit `feat: preserve destination keyed drafts`. |
| E7.7 | Slice device acceptance | Add `ConversationContinuityDeviceTest.switchesTwoConversationsWithoutLeakage`; run `./gradlew :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; RED = flow/accessibility/golden evidence fails. | Add debug-only responder and missing accessibility states; rerun the same command → PASS, then full slice command; commit `test: accept local conversation slice`. |

**Reviewer Gate 7:** Reject if Persona affects capability, model identity outranks Agent identity, a Conversation is treated as a Run, or any fake Agent ships in release.

### Task 8: Add Multi-Provider Streaming Conversation

**Depends on:** Gate D1 and Task 7; does not wait for Gate C, Gate D2, SSH, or Gate E  
**User-visible result:** The user configures an OpenAI-compatible, Anthropic, or custom OpenAI-compatible endpoint, probes capabilities, binds it to an Agent, and receives cancellable streaming replies in the existing Conversation  
**Capabilities added:** provider profiles, model selection, custom base URL, streaming text, normalized errors; execution/tool proposals remain disabled in product flow  
**Traceability:** `B36.1`, `B36.2`, `B36.5`, `B36.10`, `UX34.1`, `UX34.2`, `UX34.4`, `UX34.5`, `UX34.13`, `SA32.5`, `SA32.6`, `SA32.10`, `SA32.13`

**Files:**

- Create: `integration/provider-api/src/main/kotlin/dev/cockpit/provider/api/{ProviderAdapter,ProviderProfile,ProviderCapabilities,NormalizedProviderRequest,ProviderStreamEvent,ProviderError}.kt`
- Create: `integration/provider/src/main/kotlin/dev/cockpit/provider/{OpenAiCompatibleAdapter,AnthropicAdapter,ProviderAdapterRegistry,SseEventParser}.kt`
- Create: `integration/provider/src/test/kotlin/dev/cockpit/provider/ProviderAdapterContractTest.kt`
- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/coordinator/ProviderInvocationGate.kt`
- Modify: `security/vault/src/main/kotlin/dev/cockpit/security/vault/AndroidCredentialVault.kt`
- Create: `core/application/src/main/kotlin/dev/cockpit/application/settings/{SaveProviderProfile,ProbeProvider,BindAgentProvider}.kt`
- Create: `core/application-api/src/main/kotlin/dev/cockpit/application/api/ProviderSettingsPort.kt`
- Modify: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/CockpitDatabase.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/migration/Migration1To2.kt`
- Create: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/settings/provider/*`
- Modify: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/conversation/*`
- Export: `data/persistence-room/schemas/2.json`

**Normalized stream:**

```kotlin
sealed interface ProviderStreamEvent {
    data class TextDelta(val invocationId: String, val ordinal: Long, val text: String) : ProviderStreamEvent
    data class ToolProposalDelta(val callId: String, val bytes: ImmutableBytes) : ProviderStreamEvent
    data class Completed(val usage: ProviderUsage?) : ProviderStreamEvent
    data class Failed(val error: ProviderError) : ProviderStreamEvent
}
```

- [ ] Write one abstract `ProviderAdapterContractTest` first and run it against Fake, OpenAI-compatible and Anthropic implementations. Cover truthful probe, stream ordering, split/malformed tool arguments, duplicate finish, cancellation, late events, auth/rate/context errors, TLS failure and redacted logging.
- [ ] Add a compile-boundary test proving `ProviderAdapter` cannot see `RawObservationRef`, Vault implementation, SSH types, Room entities or `ProviderCredentialLeasePort`; it may receive only a one-use opaque `ProviderAuthorizationHandle` and cannot construct one.
- [ ] Use MockWebServer fixtures for text streaming and malformed responses. Seed API-key canaries and assert no request/response logs, exception text or test snapshots contain them.
- [ ] Run `./gradlew :integration:provider:test`; expect RED before adapters exist.
- [ ] Implement Provider profiles as non-secret metadata plus Vault `CredentialReference`. Consume Gate-D1's separate `ProviderCredentialLeasePort` only inside `ProviderInvocationGate`; issue the D1-defined invocation/profile/model/purpose authority and pass only its opaque one-use header-authorizing handle to the adapter. Custom endpoint accepts HTTPS by default; an explicit local-development override is debug-only. Never silently fall back to another host/model.
- [ ] Handle `AUTH_PER_USE` / expired validity window as an explicit user-auth-required state: no invocation starts before successful device auth, no handle survives the wait/process change, and a fresh invocation authority is issued afterward.
- [ ] Implement capability probe as cached, expiring evidence with `UNKNOWN` support; a Provider claiming no tool support can chat but cannot start an execution Run later.
- [ ] Persist streaming deltas through an invocation accumulator and commit stable assistant Message content transactionally. Cancellation stops local consumption; late events are ignored by invocation/owner epoch.
- [ ] Keep incomplete `ToolProposalDelta` out of Message text and out of Runtime. For this slice, a complete proposal produces a truthful “execution capability not enabled in this build slice” event in debug tests, not an execution.
- [ ] Implement Settings provider forms, connection test, clear error/retry, Agent binding summary and model selection. Do not expose API keys after save.
- [ ] Add migration 1→2 and verify Conversations/Messages/drafts survive. Add a release test ensuring debug cleartext/network overrides are absent.
- [ ] Run `./gradlew :integration:provider:test :data:persistence-room:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; expect PASS for all three adapter profiles using MockWebServer.
- **Slice integration label:** `feat: stream conversations across configurable model providers`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E8.1 | Normalized Provider contract | Add `ProviderAdapterContractTest.normalizesOrderedCancelableStream`; run `./gradlew :integration:provider:test --tests '*ProviderAdapterContractTest*'`; RED = contract/types/adapter fixture absent. | Add Provider API and fake contract harness only; same command → PASS for Fake; commit `feat: define provider streaming contract`. |
| E8.2 | OpenAI-compatible adapter | Run `./gradlew :integration:provider:test --tests '*ProviderAdapterContractTest' -PproviderUnderTest=openai`; RED = OpenAI SSE mapping/probe cases fail. | Implement only OpenAI-compatible probe/SSE mapping; rerun the exact command → PASS; commit `feat: add openai compatible provider`. |
| E8.3 | Anthropic/custom endpoint | Run `./gradlew :integration:provider:test --tests '*ProviderAdapterContractTest' -PproviderUnderTest=anthropic,custom`; RED = Anthropic event or custom endpoint/TLS cases fail. | Add Anthropic mapping and explicit HTTPS custom profile only; rerun the exact command → PASS; commit `feat: add anthropic and custom providers`. |
| E8.4 | D1 credential integration | Add `ProviderInvocationGateTest.authorityHandleIsExactAndOneUse`; run `./gradlew :core:runtime:test --tests '*ProviderInvocationGateTest'`; RED = Gate-D1 handle not consumed/validated. | Bind D1 lease to invocation gate without secret DTOs; same command → PASS; commit `security: authorize provider invocations`. |
| E8.5 | Schema 2/settings | Add `Migration1To2Test.preservesConversationAndStoresNoSecret`; run `./gradlew :data:persistence-room:test --tests '*Migration1To2Test'`; RED = profile/binding migration absent. | Add non-secret profile/model binding metadata and settings use cases; same command → PASS; commit `data: persist provider profiles safely`. |
| E8.6 | Streaming conversation UI | Add `StreamingConversationTest.cancelAndLateEventsDoNotCorruptMessage`; run `./gradlew :presentation:testDebugUnitTest --tests '*StreamingConversationTest'`; RED = stream state/UI binding absent. | Add accumulator, transactional assistant Message and settings/conversation UI only; same command → PASS; commit `feat: stream provider replies in conversation`. |
| E8.7 | Slice boundary acceptance | Add `ProviderReleaseBoundaryTest.rawTypesDebugOverridesAndSecretsAreAbsent`; run `./gradlew :architecture-tests:test --tests '*ProviderReleaseBoundaryTest'`; RED = forbidden import/artifact or evidence absent. | Remove forbidden release edges, then run `./gradlew :integration:provider:test :data:persistence-room:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest :architecture-tests:test :security-tests:test` → PASS; commit `test: accept multi provider conversation slice`. |

**Reviewer Gate 8:** Reject if custom endpoints bypass TLS in release, capability claims are guessed, partial tool proposals enter Runtime, or secrets/raw observations are visible to Provider code.

---

## Phase 2 — Typed Task and Fake Execution

### Task 9: Add Task/Run State Machine with a Fake Runtime

**Depends on:** Tasks 7 and 8; does not depend on Gate B, SSH Gates, or Gate E  
**User-visible result:** A debug scenario turns a user request into a stable Task Card, runs a visible Plan with fake ToolCalls, handles guidance/question/cancel/retry, and produces a non-success fake result without touching a server  
**Capabilities added:** Message/Task/Run separation, immutable Run Snapshot, append-only runtime inputs/events, one-active-Run, projections, Composer destinations  
**Traceability:** `B36.1`–`B36.4`, `B36.8`–`B36.10`, `UX34.2`, `UX34.5`–`UX34.8`, `UX34.12`, `UX34.13`, `UX35`, `SA32.2`, `SA32.4`, `SA32.5`, `SA32.13`

**Files:**

- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/task/{Task,TaskStatus,SuccessCriterion}.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/run/{Run,RunState,RunSnapshot,RuntimeInput,RuntimeFact,RunStateReducer,RunGuidanceDestination,RunQuestionReplyDestination}.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/execution/{Plan,ToolCall,ToolCallState}.kt`
- Create: `core/runtime-api/src/main/kotlin/dev/cockpit/runtime/api/{RuntimeCommand,RuntimeCommandPort,CommandReceipt}.kt`
- Create: `core/application-api/src/main/kotlin/dev/cockpit/application/api/{RunApplicationPort,RunProjectionQueryPort}.kt`
- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/coordinator/{RunCoordinator,RunOwnerLease,ExternalEffectRunner}.kt`
- Create: `data/persistence-api/src/main/kotlin/dev/cockpit/persistence/api/{RuntimeEventStore,RuntimeTransactionPort,ProjectionCheckpointStore}.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/{RuntimeEventDao,RunDao,TaskDao,ActiveRunSlotDao}.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/migration/Migration2To3.kt`
- Create: `data/projection-models/src/main/kotlin/dev/cockpit/projection/model/{TaskCardProjection,RunDetailProjection,ActivityProjection}.kt`
- Modify: `data/projection-models/src/main/kotlin/dev/cockpit/projection/model/TimelineItemProjection.kt` to add `TaskCardItem`
- Create: `data/projection/src/main/kotlin/dev/cockpit/projection/{TimelineProjector,TaskCardProjector,RunDetailProjector,HomeProjector,ActivityProjector}.kt`
- Create: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/task/*`
- Modify: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/{home,conversation,activity}/*`
- Export: `data/persistence-room/schemas/3.json`
- Create after tests: `docs/adr/ADR-013-composer-destination-drafts.md`

**Reducer contract:**

```kotlin
fun reduce(
    initialSnapshot: RunSnapshot,
    appendOnlyInputs: List<RuntimeInput>,
    orderedFacts: List<RuntimeFact>,
): RecoverableRunContext
```

Task 9 is deliberately execution-free: `Task`, `Run`, `RunSnapshot`, `Plan` and fake `ToolCall` can exist without importing `ExecutionEnvelope`, permission authority, SSH target or any Gate-B type. Snapshot fields for not-yet-installed capabilities use versioned immutable references/absence, not placeholder Envelopes. Task 10 is the first slice that attaches canonical execution facts.

- [ ] Write exhaustive state transition tests first, including forbidden transitions, terminal immutability, `Cancel Run != Close Task`, Retry creating a new Run, and one global active Run.
- [ ] Add property-based random event tests: reducer is deterministic; duplicate delivery is idempotent; terminal Run never reopens; immutable Snapshot bytes never change; runtime answers/decisions are append-only facts.
- [ ] Write transaction/crash RED tests for T1–T5 and projection watermark: before/after commit, duplicate command, late Provider callback, process owner epoch change, projection deletion/rebuild and disk-full failure.
- [ ] Write Compose UI RED tests for stable Task Card, Active Run strip, Plan expansion, fake ToolCall rows, Run Detail, Waiting User, Waiting Permission placeholder, Cancel Run, Close Task and Retry. Switch to another Agent/Conversation mid-Run and prove the Run continues through the global affordance while drafts/destinations do not leak.
- [ ] Run `./gradlew :core:domain:test :core:runtime:test :data:persistence-room:test :presentation:testDebugUnitTest`; expect RED.
- [ ] Implement single-writer `RunCoordinator` with global `ActiveRunSlot`, expected versions, global ordinal/per-Run sequence and no external I/O while its coordination turn/transaction is held.
- [ ] Implement schema v3 authoritative Task/Run/Snapshot/fact tables plus rebuildable projections. Snapshot captures Agent/Persona/provider/skill/policy/workspace context and remains immutable.
- [ ] Route Composer only through the full stable destinations: `ConversationMessageDestination(conversationId, expectedConversationRevision)`, `RunGuidanceDestination(conversationId, taskId, runId, expectedRunVersion)`, or `RunQuestionReplyDestination(conversationId, taskId, runId, questionId, expectedQuestionVersion, oneTimeReplyNonce)`. `RuntimeCommand.GuideRun` / `AnswerRunQuestion` carry those value objects intact; Coordinator validates every relationship/version and consumes the reply nonce atomically. There is no current page/current Run inference. Quarantine a draft if destination authority changes during editing; Waiting Permission never interprets Composer text as approval.
- [ ] Implement debug fake Provider/executor scripts that pause at every Run state. They must use the same Runtime API as future adapters and cannot be imported by release source sets.
- [ ] Render Message and Task Card as the only Timeline first-level types; ToolCall/Plan/Run facts appear inside Task/Run detail projections, not as assistant prose.
- [ ] Add migration 2→3 and projection rebuild tests. Kill/restart the debug process in each nonterminal state and assert a safe recoverable context.
- [ ] Run `./gradlew :core:domain:test :core:runtime:test :data:persistence-room:test :data:projection:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; expect PASS.
- [ ] Accept ADR-013 from the destination-keyed draft and stale-quarantine evidence; approval/egress decisions remain explicit cards, never Composer text.
- **Slice integration label:** `feat: add durable task and run runtime with fake execution`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E9.1 | Task/Run legal vocabulary | Add `RunStateReducerTest.onlyApprovedTaskAndRunStatesCompileAndTransition`; run `./gradlew :core:domain:test --tests '*RunStateReducerTest'`; RED = domain/reducer absent or illegal transition accepted. | Add only Approved `TaskStatus`/`RunState`/`ToolCallState` and reducer skeleton; same command → PASS; commit `feat: define task run lifecycle`. |
| E9.2 | Reducer properties | Add `RunReducerPropertyTest.snapshotImmutableAndFactsIdempotent`; run `./gradlew :core:domain:test --tests '*RunReducerPropertyTest'`; RED = randomized invariant fails. | Add pure reduction/idempotency only; rerun the exact command → PASS; commit `test: prove recoverable run reduction`. |
| E9.3 | Schema 3/event transactions | Add `RuntimeCrashBoundaryTest.t1ThroughT5AreAtomic`; run `./gradlew :data:persistence-room:test --tests '*RuntimeCrashBoundaryTest'`; RED = ledger/active slot/checkpoint missing. | Add schema-3 stores and expected-sequence transactions only; same command → PASS; commit `data: persist runtime facts atomically`. |
| E9.4 | Single-writer Coordinator | Add `RunCoordinatorTest.oneGlobalActiveRunAndNoIoUnderTurn`; run `./gradlew :core:runtime:test --tests '*RunCoordinatorTest'`; RED = concurrent ownership or lock detector fails. | Add Coordinator/owner lease/external-effect split only; same command → PASS; commit `feat: coordinate one active run`. |
| E9.5 | Projection boundaries | Add `RuntimeProjectionTest.timelineHasOnlyMessageAndTaskCard`; run `./gradlew :data:projection:test --tests '*RuntimeProjectionTest'`; RED = projection types/projectors absent. | Add TaskCard/RunDetail/Home/Activity projectors only; same command → PASS; commit `feat: project task and run facts`. |
| E9.6 | Full Composer destinations | Add `RuntimeComposerDestinationTest.rejectsWrongRelationshipStaleVersionsAndReusedNonce`; run `./gradlew :core:runtime:test --tests '*RuntimeComposerDestinationTest'`; RED = full destinations/atomic nonce validation absent. | Add both destination value objects, RuntimeCommand variants and Coordinator validation only; same command → PASS; commit `feat: route composer to exact run destinations`. |
| E9.7 | Fake Runtime UI | Add `FakeRunUiTest.planQuestionGuidanceCancelRetryRemainTaskScoped`; run `./gradlew :presentation:testDebugUnitTest --tests '*FakeRunUiTest'`; RED = Task Card/Run detail flow absent. | Add debug fakes and Task/Run UI projections only; same command → PASS; commit `feat: show fake task execution`. |
| E9.8 | Migration/recovery acceptance | Add `Migration2To3RecoveryTest.everyNonterminalRunRecoversSafely`; run `./gradlew :data:persistence-room:test --tests '*Migration2To3RecoveryTest'`; RED = migration/rebuild/recovery case fails. | Add migration and safe bootstrap behavior; run full Task-9 verification → PASS; accept ADR-013; commit `test: accept durable fake runtime slice`. |

**Reviewer Gate 9:** Reject if the UI owns Run state, a Provider callback mutates tables directly, Snapshot is updated in place, Cancel closes Task, or Timeline becomes an event log.

### Task 10: Persist Success Definition and Integrate Envelope, Permission, and Byte-Faithful Fake Execution

**Depends on:** Gates B/C and Task 9  
**User-visible result:** A fake Tool Proposal becomes an immutable Envelope; its immutable Success Criteria / Verification Plan is durably recorded before a trusted full-screen MODIFY review; Approve Once executes only the reviewed Envelope and persisted plan binding through the fake authorization pipeline  
**Capabilities added:** ToolProposal normalization, immutable Verification definition persistence (not execution), Safe Read Profile, permission authority, current safety floor, secure renderer, frozen target display; still no real SSH or Verification checks/evaluators  
**Traceability:** `B36.4`–`B36.6`, `B36.8`–`B36.10`, `UX34.8`, `UX34.9`, `UX34.12`–`UX34.14`, `UX35`, `SA32.3`–`SA32.5`, `SA32.8`, `SA32.12`, `SA32.13`

**Files:**

- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/safety/PermissionRequest.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/verification/{VerificationPlanVersion,VerificationPlanBinding,VerificationPlanDigest,VerificationCriterion,VerificationCriterionId,CriterionRequirement,ExpectedCondition,EvaluatorTrustRequirement,VerificationProvenance,VerificationPlanResolution}.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/verification/{CanonicalVerificationPlanEncoder,CanonicalVerificationPlanDecoder}.kt`
- Create: `core/domain/src/test/kotlin/dev/cockpit/domain/verification/{VerificationPlanDefinitionTest,CanonicalVerificationPlanGoldenTest}.kt`
- Create: `data/persistence-api/src/main/kotlin/dev/cockpit/persistence/api/VerificationPlanAuthorityPort.kt`
- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/gate/VerificationPlanAuthorityService.kt`
- Create: `core/runtime/src/test/kotlin/dev/cockpit/runtime/gate/MutatingPermissionVerificationPlanTest.kt`
- Create: `security/permission-api/src/main/kotlin/dev/cockpit/security/permission/api/PermissionAnalysisPort.kt`
- Create: `security/permission/src/main/kotlin/dev/cockpit/security/permission/{PermissionEngine,SafeReadProfile,EffectiveSafetyPolicy}.kt`
- Create: `security/byte-renderer-api/src/main/kotlin/dev/cockpit/security/render/api/PresentationToken.kt`
- Create: `security/byte-renderer/src/main/kotlin/dev/cockpit/security/render/SafeByteRenderer.kt`
- Create: `core/application/src/main/kotlin/dev/cockpit/application/permission/TrustedPermissionReviewAssembler.kt`
- Create: `core/application-api/src/main/kotlin/dev/cockpit/application/api/permission/{TrustedPermissionReviewModel,PermissionReviewQueryPort,PermissionActionPort}.kt`
- Modify: `core/runtime-api/src/main/kotlin/dev/cockpit/runtime/api/RuntimeCommand.kt` to add `DecidePermission`
- Create: `security/byte-renderer/src/test/kotlin/dev/cockpit/security/render/SafeByteRendererAdversarialTest.kt`
- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/gate/PermissionAuthorityService.kt`
- Create: `agent/skill-api/src/main/kotlin/dev/cockpit/skill/api/{Skill,SkillOperation,ToolProposal,SkillRegistryPort}.kt`
- Create: `agent/skill-runtime/src/main/kotlin/dev/cockpit/skill/runtime/{DefaultSkillRegistry,ToolProposalNormalizer}.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/{VerificationPlanDao,VerificationCriterionDao}.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/migration/Migration3To4.kt`
- Create: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/permission/{PermissionCard,PermissionReviewScreen,ByteTokenView}.kt`
- Create: `presentation/src/androidTest/kotlin/dev/cockpit/presentation/ui/permission/PermissionDeceptionTest.kt`
- Export: `data/persistence-room/schemas/4.json`
- Create after tests: `docs/adr/ADR-008-secure-byte-rendering.md`

**Trusted review API:**

```kotlin
data class TrustedPermissionReviewModel internal constructor(
    val authorityRef: PermissionAuthorityRef,
    val envelopeDigest: EnvelopeDigest,
    val verificationPlanBinding: VerificationPlanBinding?,
    val verificationCriteria: List<TrustedVerificationCriterionReview>,
    val target: FrozenTargetReview,
    val commandTokens: List<PresentationToken>, // assembler-owned defensive copy
    val environmentTokens: List<EnvironmentReviewToken>,
    val risk: RuntimeRisk,
    val warnings: List<ReviewWarning>,
    val expectedAuthorityVersion: Long,
)
```

`ExpectedCondition` is a closed, schema-versioned v1 algebra aligned to the Task-14 deterministic evaluators: exact byte/text value, HTTP status, service state and explicit boolean result. Unknown variants fail decoding; criteria order is canonicalized by stable criterion ID/version. `CriterionRequirement` is exactly `REQUIRED` or `ADVISORY`. Provenance records the source model invocation/runtime policy/user confirmation references without storing mutable prose as authority. `VerificationPlanDigest` is calculated over the canonical unsigned definition fields (including every criterion), excluding the digest field itself; the stored digest is then verified against those bytes on every resolve. `PermissionRequest.verificationPlanBinding` is typed and nullable only because non-mutating requests need no success plan; creation and decoding reject a null binding whenever the Envelope can mutate remote state.

**Pre-mutation persistence order:**

```text
build complete VerificationPlanVersion P
→ validate stable IDs / REQUIRED definitions / typed conditions / trust requirements
→ canonical encode + digest P
→ atomically persist P + all criterion definitions
→ resolve P back from authoritative storage and verify digest
→ create PermissionRequest binding exact P
→ show trusted Envelope + P review
→ user Approve Once
→ T6-P / T6-A / T6-B each re-resolve P
→ only then may mutating request bytes be sent
```

Publishing a `PermissionRequest` before the plan transaction commits is forbidden. The store is insert-only per `planId + version`; a byte-different duplicate version is corruption, not an update. A plan with zero criteria, a missing REQUIRED definition referenced by the binding, an unsupported schema/condition, or a digest mismatch is unresolved authority—not a prompt to regenerate criteria.

- [ ] Write Verification definition RED/golden tests first: stable criterion IDs/versions, REQUIRED/ADVISORY, description, typed expected condition, minimum evaluator trust and provenance must round-trip through canonical v1 bytes; mutation-after-construction, criterion reordering, duplicate IDs, unknown schema/condition and lost REQUIRED definition must fail or change the digest deterministically.
- [ ] Write transaction/authority RED tests: a mutating PermissionRequest cannot be published until the exact complete plan version commits and resolves by digest. Missing/corrupt/unsupported plan, digest mismatch or incomplete REQUIRED definitions must be rejected at Permission creation and again at T6-P/T6-A/T6-B, with zero fake mutating writes. No recovery branch may call Provider to regenerate a plan.
- [ ] Write Safe Read RED tests for a deliberately small v1 profile: fixed `/usr/bin/uname -a`, `/usr/bin/uptime`, `/bin/df -P`, and `/usr/bin/systemctl is-active -- <unit>` where unit matches a strict ASCII validator. Redirection, substitution, `eval`, interpreters/scripts, sudo/root, glob/expansion, unknown executable, network egress, secret paths, pipeline/compound/control operators all produce ASK or DENY, never ALLOW.
- [ ] Write policy lattice tests: uncertainty raises privilege; current hard DENY/tighter Safe Read rules override old Snapshot; later relaxed Runtime rules cannot widen the frozen Run.
- [ ] Write renderer adversarial fixtures for NUL/control bytes, ANSI, CR/LF, tabs, bidi, zero-width characters, Markdown lookalikes, trailing spaces, huge lines and invalid UTF-8. Assert tokens round-trip to exact bytes and never execute markup/ANSI.
- [ ] Add architecture tests: UI cannot access raw Envelope reader or construct authoritative review Strings; only `TrustedPermissionReviewAssembler` can combine authority facts + canonical bytes + renderer; Approve command contains authority ref/digest/version, not a command.
- [ ] Write a full-screen UI RED test where a malicious command visually tries to hide a second line. Approval remains disabled until the entire trusted page is loaded; digest, host ID/revision, resolved address, port, username, fingerprint, workspace, command byte count, timeout and warnings are present.
- [ ] Run `./gradlew :security:permission:test :security:byte-renderer:test :architecture-tests:test :presentation:testDebugUnitTest`; expect RED.
- [ ] Implement schema v4 with immutable Envelope bytes/digests, permission request/decision, safety/Fence facts, and authoritative `verification_plan_version` + `verification_criterion` definitions. Persist canonical plan bytes/digest and normalized indexed fields in one transaction; the canonical bytes are digest authority and rows support referential/recovery checks. Never store a post-approval “pretty command” as authority.
- [ ] Implement Permission Card summary and trusted Review page. Visual wrapping/copy is presentation only; copied content uses an explicit escaped-byte export and warns that it is not a shell reconstruction.
- [ ] Bind Approve Once to exact Envelope/target/policy/authority version/expiry and, for mutation, exact persisted `VerificationPlanBinding`. Double tap, stale page, modified SSHHost, changed workspace target, changed safety epoch, new conflict obligation or changed/unresolvable plan must reject and re-plan/review.
- [ ] Integrate Gate-C fakes through distinct `FakeTransportPreparationPort`, `FakeAuthorizedExecutionPort` and `FakeExecutionControlPort`. Demonstrate `analyzed Envelope digest == approved Envelope digest == executed Envelope digest` and `persisted plan digest == reviewed plan digest == T6-resolved plan digest`; prove the preparation fake cannot authenticate/execute and all broad or mismatched paths fail closed.
- [ ] Add migration 3→4 and test that pre-v4 active Runs recover PAUSED and require creation of fresh pre-execution authority. Migration never fabricates an Envelope or Verification Plan; a legacy Run cannot gain mutating authority until a new plan is durably persisted before execution.
- [ ] Run `./gradlew :security:permission:test :security:byte-renderer:test :core:runtime:test :data:persistence-room:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; expect PASS, including screenshot/accessibility fixtures.
- [ ] Accept ADR-008 only after the adversarial token/round-trip/accessibility suite passes; record that this renderer is dedicated to trusted review and is not a terminal widget.
- **Slice integration label:** `feat: add trusted permission review and fake authorized execution`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E10.1 | Proposal normalization | Add `ToolProposalNormalizerTest.onlyCompleteVersionedSkillProposalCreatesEnvelopeInput`; run `./gradlew :agent:skill-runtime:test --tests '*ToolProposalNormalizerTest'`; RED = normalizer/registry absent. | Add skill registry and complete-proposal normalization only; same command → PASS; commit `feat: normalize versioned tool proposals`. |
| E10.2 | Immutable Verification definition | Add `CanonicalVerificationPlanGoldenTest.completeDefinitionIsCanonicalAndImmutable`; run `./gradlew :core:domain:test --tests '*CanonicalVerificationPlanGoldenTest'`; RED = definition types/encoder/digest or duplicate/unknown rejection is absent. | Add the closed definition algebra and canonical v1 encoder/decoder only; rerun the exact command → PASS; commit `security: define immutable verification plans`. |
| E10.3 | Schema 4 atomic persistence | Add `Migration3To4Test.planAndCriteriaCommitBeforePermissionBinding`; run `./gradlew :data:persistence-room:test --tests '*Migration3To4Test'`; RED = Envelope/plan tables, atomic transaction or migration is absent. | Add schema-4 Envelope/permission/safety/Fence plus immutable plan/criterion storage and migration only; rerun the exact command → PASS; commit `data: persist execution authority and success definitions`. |
| E10.4 | Plan authority fail-closed | Add `MutatingPermissionVerificationPlanTest.missingCorruptUnsupportedOrIncompletePlanSendsZeroMutationBytes`; run `./gradlew :core:runtime:test --tests '*MutatingPermissionVerificationPlanTest'`; RED = a mutating request reaches T6/preparation or an invalid binding resolves. | Add read-only resolver plus Permission and T6-P/A/B binding validation only; rerun the exact command → PASS; commit `security: require persisted plan for mutation`. |
| E10.5 | Safe Read policy | Add `SafeReadProfileTest.onlyV1AllowlistAutoAllows`; run `./gradlew :security:permission:test --tests '*SafeReadProfileTest'`; RED = unsafe fixture is ALLOW or engine absent. | Implement exact v1 allowlist and uncertainty lattice only; rerun the exact command → PASS; commit `security: enforce conservative safe read`. |
| E10.6 | Trusted byte rendering | Add `SafeByteRendererAdversarialTest.tokensRoundTripWithoutVisualExecution`; run `./gradlew :security:byte-renderer:test --tests '*SafeByteRendererAdversarialTest'`; RED = malicious fixture changes/hides bytes. | Implement escaped presentation tokens only; rerun the exact command → PASS; commit `security: render command bytes faithfully`. |
| E10.7 | Permission authority/review | Add `PermissionAuthorityServiceTest.reviewDecisionBindsEnvelopeTargetPolicyAndPersistedPlan`; run `./gradlew :core:runtime:test --tests '*PermissionAuthorityServiceTest'`; RED = stale/mismatched Envelope or plan authority is accepted. | Add authority service and trusted assembler using the authoritative plan resolver only; rerun the exact command → PASS; commit `security: bind approve once to envelope and plan`. |
| E10.8 | Permission UI | Add `PermissionDeceptionTest.maliciousCommandAndCriteriaCannotHideFromFullReview`; run `./gradlew :presentation:connectedDebugAndroidTest`; RED = target/byte/plan digest/REQUIRED criterion/accessibility assertion fails. | Add Card/full-screen Review/byte tokens and trusted criterion summary only; rerun the exact command → PASS; commit `feat: show trusted mutation success criteria`. |
| E10.9 | Split-port fake mutation | Add `FakeExecutionPipelineTest.fakeMutationUsesPersistedPlanAtEveryT6Gate`; run `./gradlew :core:runtime:test --tests '*FakeExecutionPipelineTest'`; RED = broad Port, Envelope mismatch, ephemeral-only plan or regeneration path reaches fake write. | Wire the three Gate-C fakes and persisted plan resolver through T6-P/A/B only; rerun the exact command → PASS; commit `feat: execute fake mutation with durable plan authority`. |
| E10.10 | Slice acceptance | Add `PermissionSliceArchitectureTest.uiCannotCarryExecutableBytesOrWriteVerificationDefinitions`; run `./gradlew :architecture-tests:test --tests '*PermissionSliceArchitectureTest'`; RED = forbidden dependency/command field, mutable plan update or Provider regeneration path is found. | Remove forbidden surfaces, run `./gradlew :security:permission:test :security:byte-renderer:test :core:domain:test :core:runtime:test :data:persistence-room:test :architecture-tests:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest` → PASS and accept ADR-008; commit `test: accept trusted permission and plan slice`. |

**Reviewer Gate 10:** Reject if any command is prettified before approval, the model supplies its own risk, READ implies ALLOW, a mutable host record can redirect execution, an Approve action carries executable bytes from UI, or a fake mutation can obtain authority without resolving the exact durably persisted immutable Verification Plan and all REQUIRED definitions.

---

## Phase 3 — Real SSH Capability

### Task 11: Execute Real Safe Read Operations over SSH

**Depends on:** Gate A, Gate D2 and Task 10 (Task 10 already carries Gate B/C prerequisites)  
**User-visible result:** The user onboards a Host with explicit Host Key trust, binds a Workspace, asks the Agent for a supported diagnostic, watches real stdout/stderr on the phone, and sees the Run pause before any Provider egress  
**Capabilities added:** SSHHost revisions, Workspace three-layer context, frozen address/identity, Safe Read auto-ALLOW, real byte-preserving SSH, encrypted raw output; no MODIFY and no observation egress yet  
**Traceability:** `B36.3`–`B36.10`, `UX34.6`–`UX34.10`, `UX34.12`–`UX34.14`, `UX35`, `SA32.2`–`SA32.5`, `SA32.8`–`SA32.10`, `SA32.13`

**Files:**

- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/workspace/{SSHHost,SSHHostRevision,WorkspaceContext,TaskTarget,RunWorkspaceSnapshot}.kt`
- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/observation/RawObservationRef.kt`
- Create: `core/application/src/main/kotlin/dev/cockpit/application/settings/{OnboardSshHost,ConfirmHostKey,SaveWorkspace,SaveSshCredential}.kt`
- Create: `core/application/src/main/kotlin/dev/cockpit/application/runtime/ObserveLocalOutput.kt`
- Create: `integration/ssh/src/main/kotlin/dev/cockpit/ssh/{SshTransportPreparer,TicketAuthorizedSshExecutor,SshExecutionController,FrozenTargetConnector,HostKeyVerifier,TicketScopedAuthenticator,ExactExecChannel,DeliveryCertaintyMonitor}.kt`
- Create: `data/persistence-api/src/main/kotlin/dev/cockpit/persistence/api/{RawObservationWriter,RawObservationReader}.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/blob/{EncryptedRawObservationStore,ByteSpool}.kt`
- Create: `security/byte-renderer-api/src/main/kotlin/dev/cockpit/security/render/api/OutputToken.kt`
- Create: `security/byte-renderer/src/main/kotlin/dev/cockpit/security/render/UntrustedOutputRenderer.kt`
- Create: `agent/skill-runtime/src/main/kotlin/dev/cockpit/skill/runtime/SafeReadSshSkill.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/migration/Migration4To5.kt`
- Create: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/settings/{host,workspace}/*`
- Create: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/task/{ToolCallRow,LiveOutputPanel,FullOutputScreen}.kt`
- Export: `data/persistence-room/schemas/5.json`

**Frozen connector rule:**

```kotlin
data class FrozenSshEndpoint(
    val sshHostId: SshHostId,
    val sshHostRevision: Long,
    val resolvedAddressBytes: ImmutableBytes,
    val port: UShort,
    val usernameBytes: ImmutableBytes,
    val hostKey: PinnedHostKey,
)
```

- [ ] Port the entire Gate A contract suite from the spike to `:integration:ssh` before implementation. Production adapter tests must run against the recording server and OpenSSH lane; no test import from spike production code.
- [ ] Write Host onboarding RED tests: resolve and display candidate address, connect only for handshake, show algorithm/fingerprint out of trusted verifier, require explicit confirmation, create immutable `SSHHostRevision`; changed/missing key creates a new review and sends no Credential.
- [ ] Write frozen-target acceptance test exactly: approve Envelope → modify mutable SSHHost hostname/port/username/key → execute attempt → old Envelope either uses original frozen endpoint or is invalidated; it never targets the modified record.
- [ ] Write Safe Read integration tests for the four Task-10 operation shapes. Assert auto-ALLOW only when Snapshot ∩ current profile proves safety; every other read pauses for ASK/DENY.
- [ ] Add raw spool tests for independent stdout/stderr sequence, receive ordinal, binary bytes, output limits, truncation metadata/digest and crash-safe encrypted blob commit. Live UI must never interpret ANSI/Markdown/bidi as trusted UI.
- [ ] Run `./gradlew :integration:ssh:test :agent:skill-runtime:test :presentation:testDebugUnitTest`; expect RED.
- [ ] Implement SSHHost/Workspace schema v5. Conversation context is mutable for future tasks; Task target freezes task intent; Run Snapshot freezes exact workspace/target context for that Run.
- [ ] For a tool-capable Provider, expose only the versioned Safe Read Skill schemas frozen into the Run Snapshot and route only a complete normalized `ToolProposal` into Runtime. Text-only/unknown-capability Providers remain chat-only; model text is never parsed as a command.
- [ ] Promote the exact Gate-A selected transport behind three production surfaces. `SshTransportPreparer.prepare(permit)` uses only the frozen packed address/port + pinned key and creates an unauthenticated non-serializable handle; it exposes no credential/auth/exec call. `TicketAuthorizedSshExecutor.execute(ticket)` alone performs Ticket-scoped auth, obtains the exact D2 Vault lease and reaches final send. `SshExecutionController.cancel(toolCallId)` is control-only. No broad `SelectedSshTransport`, connection/session reuse or mutable-host lookup exists.
- [ ] Execute the Envelope-created `SshWirePlan` without adding newline, quoting, locale, shell wrapper, working-directory command or environment. First execution-wire write consumes final permit and records truthful delivery stage.
- [ ] Route the SSH adapter through `RawObservationWriter`; `ObserveLocalOutput` is the only presentation-facing read use case and emits bounded safe tokens from `UntrustedOutputRenderer`. It may share low-level byte-token primitives but cannot assemble Permission authority or render ANSI.
- [ ] Store output only as encrypted `RawObservationRef`; expose a bounded escaped local viewer. At the Run level show “Output retained on this device; Provider egress is not enabled in this slice” and pause rather than sending output back to the model.
- [ ] Add migration 4→5, blob rollback/cleanup tests and a key-change/process-death instrumentation scenario. Ephemeral transport/session/ticket never recover; conclusive pre-request state may re-plan, ambiguity fails closed.
- [ ] Run `./gradlew :integration:ssh:test :agent:skill-runtime:test :data:persistence-room:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; expect PASS on fake server plus the disposable OpenSSH target.
- **Slice integration label:** `feat: execute frozen safe-read ssh operations`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E11.1 | Workspace/Host domain + schema 5 | Add `Migration4To5Test.preservesThreeLayersAndFrozenTargetIdentity`; run `./gradlew :data:persistence-room:test --tests '*Migration4To5Test'`; RED = workspace domain, schema or migration is absent. | Add SSHHost revision, three workspace values, raw-ref metadata and migration only; rerun the exact command → PASS; commit `data: persist frozen ssh workspace`. |
| E11.2 | Host Key onboarding | Add `HostOnboardingTest.changedOrUnknownKeyRequiresExplicitConfirmationBeforeCredential`; run `./gradlew :core:application:test --tests '*HostOnboardingTest'`; RED = key trust flow/use cases absent. | Add handshake-only onboarding and immutable revision creation only; same command → PASS; commit `feat: onboard pinned ssh host key`. |
| E11.3 | Production split ports | Port `ExecutionApiSurfaceTest` as `SshPortSurfaceTest.productionPreparationCannotAuthenticateOrExecute`; run `./gradlew :integration:ssh:test --tests '*SshPortSurfaceTest'`; RED = transport implementation absent or broad surface detected. | Add `SshTransportPreparer`, `TicketAuthorizedSshExecutor`, `SshExecutionController` shells behind separate interfaces; same command → PASS; commit `feat: split production ssh capability surfaces`. |
| E11.4 | Frozen target + exact send | Port Gate-A tests and add `FrozenTargetExecutionTest.hostMutationCannotRedirectApprovedEnvelope`; run `./gradlew :integration:ssh:test --tests '*ExactExecPayloadContractTest' --tests '*FrozenTargetExecutionTest'`; RED = current-host lookup/byte transformation or adapter absent. | Bind the selected adapter to canonical endpoint/wire plan and final permit only; same command → PASS; commit `feat: execute exact frozen ssh target`. |
| E11.5 | Encrypted raw spool | Add `RawObservationSpoolTest.channelsBytesLimitsAndCrashCommitArePreserved`; run `./gradlew :data:persistence-room:test --tests '*RawObservationSpoolTest'`; RED = spool/writer/digest/truncation fact absent. | Add encrypted bounded byte spool and raw writer only; same command → PASS; commit `data: spool raw ssh observations`. |
| E11.6 | Trusted local viewer | Add `UntrustedOutputRendererTest.ansiBidiAndBinaryCannotBecomeExecutableUi`; run `./gradlew :security:byte-renderer:test --tests '*UntrustedOutputRendererTest'`; RED = renderer/token boundary absent. | Add bounded escaped output tokens and `ObserveLocalOutput` only; same command → PASS; commit `feat: render raw output locally and safely`. |
| E11.7 | Tool-capable Safe Read integration | Add `SafeReadSshSkillTest.onlyCompleteFrozenAllowlistedProposalReachesPreparation`; run `./gradlew :agent:skill-runtime:test --tests '*SafeReadSshSkillTest'`; RED = skill/provider capability routing absent. | Add versioned Safe Read skill and capability gate only; same command → PASS; commit `feat: route safe read ssh skill`. |
| E11.8 | Blob migration/recovery | Add `Migration4To5BlobRecoveryTest.partialBlobAndProcessLossFailClosed`; run `./gradlew :data:persistence-room:test --tests '*Migration4To5BlobRecoveryTest'`; RED = partial cleanup/recovery policy absent. | Add blob commit marker/cleanup and migration recovery only; same command → PASS; commit `data: recover ssh observation storage`. |
| E11.9 | Android onboarding/live UI | Add `RealReadDeviceTest.onboardsWorkspaceAndShowsSeparateStdoutStderr`; run `./gradlew :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; RED = settings/workspace/output flow absent. | Add Host/Workspace screens, ToolCall row and local output screens only; same command → PASS; commit `feat: show real ssh read on android`. |
| E11.10 | Slice acceptance | Add `RealReadAcceptanceTest.pausesBeforeProviderEgressAndNeverPoolsSession`; run `./gradlew :integration:ssh:test :agent:skill-runtime:test :data:persistence-room:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; RED = any Gate-A/frozen/output/pause assertion fails. | Fix only integration wiring/evidence; rerun the exact command → PASS on recording server + disposable OpenSSH; commit `test: accept real safe read slice`. |

**Reviewer Gate 11:** Reject if execution resolves current SSHHost at send time, a host key can be silently accepted, output is decoded before raw persistence, any real session is pooled, or raw output reaches Provider context.

### Task 12: Execute Approved MODIFY Operations

**Depends on:** Tasks 11 and 13; Task 10's Schema-4 persisted Verification definition is already mandatory through Task 11; document number is a stable Review ID, so the real capability schedule is Task 11 → Task 13 → Task 12 → Task 14  
**User-visible result:** On a disposable test host, the Agent proposes an exact non-interactive modification; the user reviews target/command/risk and the already-persisted immutable success definition, approves once, sees real execution stages/output, and the Run stops before claiming success  
**Capabilities added:** real ASK/Approve Once consuming Schema-4 plan authority, bounded MODIFY/DESTRUCTIVE execution, cancel semantics, current-safety and plan-binding revalidation; CRITICAL remains hard DENY  
**Traceability:** `B36.3`, `B36.6`, `B36.8`–`B36.10`, `UX34.6`, `UX34.7`, `UX34.9`, `UX34.13`, `UX35`, `SA32.2`–`SA32.4`, `SA32.8`, `SA32.9`, `SA32.12`, `SA32.13`

**Files:**

- Create: `agent/skill-runtime/src/main/kotlin/dev/cockpit/skill/runtime/ApprovedSshCommandSkill.kt`
- Create: `security/permission/src/main/kotlin/dev/cockpit/security/permission/{ShellRiskTokenizer,CriticalOperationRules,WorkspaceRiskPolicy}.kt`
- Create: `security/permission/src/test/kotlin/dev/cockpit/security/permission/ModifyRiskCorpusTest.kt`
- Create: `core/runtime/src/test/kotlin/dev/cockpit/runtime/{RealExecutionBoundaryContractTest,RealMutationVerificationPlanAuthorityTest,MutationVerificationPlanRecoveryTest}.kt`
- Modify: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/{permission,task}/*`
- Create: `integration/ssh/src/integrationTest/kotlin/dev/cockpit/ssh/ApprovedModifyIntegrationTest.kt`

**v0.1 execution shape:** One non-interactive/no-PTY exec request, `stdinMode=NONE`, explicit non-secret environment only. Known bounded operations receive semantic risk; other syntactically reviewable commands are at least `DESTRUCTIVE/ASK`; ambiguous encoding/parsing or a hard-critical rule is `DENY`. User approval never converts `CRITICAL` to executable.

```kotlin
@Test fun `real adapter executes only the envelope approved by this authority`() = runTest {
    val authority = fixture.approveOnce(envelopeA)
    val result = fixture.execute(authority, attemptedEnvelope = envelopeB)
    assertThat(result).isEqualTo(AuthorityRejected.DIGEST_MISMATCH)
    assertThat(recordingServer.execRequests).isEmpty()
}
```

**Required pre-verification crash sequence:**

```kotlin
@Test fun `successful mutation recovers the exact preapproved plan after process death`() = runTest {
    val plan = fixture.persistImmutablePlanWithRequiredCriteria()
    val request = fixture.createMutatingPermission(binding = plan.binding)
    fixture.approveOnce(request)
    fixture.executeMutationSuccessfully()
    fixture.killProcess(beforeVerificationBegins = true)

    val recovered = fixture.restartAndRecover()
    assertThat(recovered.verificationPlan).isEqualTo(plan)
    assertThat(recovered.verificationPlan.requiredCriteria).isEqualTo(plan.requiredCriteria)
    assertThat(recovered.run.state).isEqualTo(RunState.PAUSED)
    assertThat(recovered.task.status).isEqualTo(TaskStatus.OPEN)
    assertThat(fixture.provider.planRegenerationRequests).isEmpty()
}
```

Task 12 proves durable recovery of the original plan and the legal non-complete state. Task 14 later proves Verification continues against that exact binding and may complete only from its REQUIRED criteria.

- [ ] Build a versioned adversarial corpus first: file create/replace, process/service change, package command, redirection/compound shell, sudo/root, wildcard/variables, recursive delete, disk tools, SSH/auth changes, database drop, remote download+execute and obfuscated/control-byte variants. Expected risk can only stay equal or increase when uncertainty is added.
- [ ] Write end-to-end RED tests proving `ToolProposal → EnvelopeFactory → Permission analysis → trusted review → decision → T6-P/A/B → exact SSH bytes`; compare the analyzed, displayed, approved and executed digest/byte source.
- [ ] Add deterministic race tests with the real adapter: Cancel/safety update/host mutation/credential rotation/new obligation before auth, during auth, after auth/before write and after write. Assert the Approved delivery semantics, not optimistic success.
- [ ] Run `./gradlew :security:permission:test :core:runtime:test :integration:ssh:integrationTest`; expect RED.
- [ ] Implement byte-oriented tokenization sufficient to identify control operators and critical signatures. It is not a general shell safety analyzer: parse failure is DENY; unknown-but-reviewable is never auto-ALLOW.
- [ ] Implement Workspace risk overrides: production increases warning/approval; current mandatory hard rules dominate; Snapshot constraints cannot be silently relaxed.
- [ ] Consume Task-10's authoritative immutable `VerificationPlanVersion`; Task 12 does not create criterion/plan types and cannot generate a replacement. The mutating Permission authority must bind exact plan ID/version/digest, and T6-P/T6-A/T6-B must re-resolve all REQUIRED definitions. Missing/corrupt/unsupported/incomplete or digest-mismatched binding yields authority rejection and zero mutating request bytes.
- [ ] Execute on a disposable OpenSSH test host using a dedicated unprivileged account and fixture directory/service. Record before/after remote state separately; never point automated tests at a user Host.
- [ ] Show delivery stage and factual copy: exit code 0 means only that the command returned successfully, not Task success. Until Task 14, after the mutating `ToolCall` is `SUCCEEDED`, persist `RunState.PAUSED` and keep `TaskStatus.OPEN`; Presentation derives the waiting reason `VERIFICATION_NOT_AVAILABLE_IN_CURRENT_SLICE`. Do not add a terminal “unverified success” Domain state and never use `RunState.COMPLETED` here.
- [ ] Add the required process-death test after successful mutation and before Verification begins. Restart must recover byte/digest-identical plan P and every REQUIRED criterion from Schema 4, keep Run non-complete and Task OPEN, and expose P for later Verification. Assert no model invocation attempts to reconstruct missing criteria.
- [ ] Test Cancel: pre-write cancellation is confirmed not executed; after `REQUEST_WRITE_STARTED` loss/ambiguity enters the unknown path stub and blocks further mutation. Task 15 will add reconciliation UI/recovery, but this slice must already fail closed.
- [ ] Run `./gradlew :security:permission:test :core:runtime:test :integration:ssh:integrationTest :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; expect PASS with test-host state assertions.
- **Slice integration label:** `feat: execute once-approved ssh modifications safely`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E12.1 | Risk corpus | Add `ModifyRiskCorpusTest.uncertaintyNeverLowersRiskAndCriticalIsDeny`; run `./gradlew :security:permission:test --tests '*ModifyRiskCorpusTest'`; RED = tokenizer/rules absent or fixture under-classified. | Add byte-oriented tokenizer and versioned critical rules only; same command → PASS; commit `security: classify bounded ssh mutations`. |
| E12.2 | Real plan-authority prerequisite | Add `RealMutationVerificationPlanAuthorityTest.missingCorruptUnsupportedOrIncompleteBindingWritesZeroRequestBytes`; run `./gradlew :core:runtime:test --tests '*RealMutationVerificationPlanAuthorityTest'`; RED = real T6 path trusts only an ID/version or reaches transport with an unresolved plan. | Reuse Task-10's resolver at each real T6 gate and close any prepared/session state on rejection; rerun the exact command → PASS; commit `security: enforce durable plan on real mutation`. |
| E12.3 | End-to-end authority chain | Add `RealExecutionBoundaryContractTest.onlyReviewedEnvelopeDigestExecutes`; run `./gradlew :core:runtime:test --tests '*RealExecutionBoundaryContractTest.onlyReviewedEnvelopeDigestExecutes'`; RED = alternate bytes/digest can reach executor or wiring absent. | Add approved command skill through existing T6 + split ports only; rerun the exact command → PASS; commit `feat: authorize exact ssh mutation`. |
| E12.4 | Real-adapter races | Add `RealExecutionBoundaryContractTest.allPreSendInvalidationsWriteZeroRequests`; run `./gradlew :core:runtime:test --tests '*RealExecutionBoundaryContractTest.allPreSendInvalidationsWriteZeroRequests'`; RED = cancel/safety/host/rotation/obligation case writes. | Add final integration revalidation/close behavior only; rerun the exact command → PASS; commit `security: close real mutation race windows`. |
| E12.5 | Disposable-host mutation | Add `ApprovedModifyIntegrationTest.onceApprovedCommandChangesOnlyFixtureTarget`; run `./gradlew :integration:ssh:integrationTest --tests '*ApprovedModifyIntegrationTest'`; RED = mutation path absent or before/after fixture mismatch. | Add the minimum controlled test operation; same command → PASS; commit `feat: execute approved fixture mutation`. |
| E12.6 | Legal pre-verification state | Add `ModifyPreVerificationStateTest.successfulToolCallLeavesRunPausedAndTaskOpen`; run `./gradlew :core:runtime:test --tests '*ModifyPreVerificationStateTest'`; RED = Run completes, Task resolves, or waiting reason absent. | Persist `ToolCallState.SUCCEEDED`, `RunState.PAUSED`, `TaskStatus.OPEN` and presentation reason only; same command → PASS; commit `feat: pause mutation pending verification`. |
| E12.7 | Post-mutation plan recovery | Add `MutationVerificationPlanRecoveryTest.successfulMutationThenProcessDeathRecoversExactRequiredCriteria`; run `./gradlew :core:runtime:test --tests '*MutationVerificationPlanRecoveryTest'`; RED = restart loses/changes P, regenerates criteria, or marks Run/Task complete. | Add recovery lookup of the original Schema-4 binding and legal PAUSED/OPEN continuation only; rerun the exact command → PASS; commit `test: recover mutation success definition`. |
| E12.8 | Slice acceptance | Add `ModifySliceAcceptanceTest.exitZeroNeverClaimsTaskSuccessAndApprovalIsOneUse`; run `./gradlew :security:permission:test :core:runtime:test :integration:ssh:integrationTest :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; RED = any state/authority/plan recovery/cancel/host assertion fails. | Fix only slice wiring/UI evidence; rerun the exact command → PASS; commit `test: accept approved modify slice`. |

**Reviewer Gate 12:** Reject if approval can be reused, critical rules are overridable, success is inferred from exit code/model prose, automation targets a personal server, any pre-send invalidation still writes an exec request, or a successful mutation can outlive the exact persisted Verification Plan/REQUIRED definitions it was approved against.

---

## Phase 4 — Observation, Verification, and Truth Recovery

### Task 13: Add the Observation / Egress Boundary

**Depends on:** Task 11; this Provider-safe observation boundary must pass before Task 12 enables real mutation  
**User-visible result:** Real server output is classified locally; NORMAL output can safely continue the Agent loop, SENSITIVE output is locally transformed or asks separately, and SECRET output is blocked while remaining viewable only through the trusted local viewer  
**Capabilities added:** NORMAL/SENSITIVE/SECRET, raw/safe stores, deterministic redaction/extraction, Egress Decision Card, structured untrusted Provider context  
**Traceability:** `B36.6`–`B36.10`, `UX34.9`, `UX34.10`, `UX34.12`–`UX34.14`, `UX35`, `SA32.5`–`SA32.7`, `SA32.13`

**Files:**

- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/observation/{ProviderSafeObservationRef,ProviderSafeObservationPayload,DataSensitivity,EgressDisposition,EgressDecision}.kt`
- Create: `security/egress/src/main/kotlin/dev/cockpit/security/egress/{SourceClassifier,LocalSecretScanner,StructuredOutputParserRegistry,EgressPolicy,DeterministicTransformer}.kt`
- Create: `security/egress-api/src/main/kotlin/dev/cockpit/security/egress/api/ObservationEgressPort.kt`
- Create: `security/egress/src/test/kotlin/dev/cockpit/security/egress/EgressAdversarialCorpusTest.kt`
- Create: `data/persistence-api/src/main/kotlin/dev/cockpit/persistence/api/{ProviderSafeObservationReader,ProviderSafeObservationWriter}.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/migration/Migration5To6.kt`
- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/observation/ObservationPipeline.kt`
- Create: `core/application-api/src/main/kotlin/dev/cockpit/application/api/EgressActionPort.kt`
- Modify: `core/runtime-api/src/main/kotlin/dev/cockpit/runtime/api/RuntimeCommand.kt` to add `DecideEgress`
- Create: `integration/provider-api/src/main/kotlin/dev/cockpit/provider/api/UntrustedObservationBlock.kt`
- Create: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/task/EgressDecisionCard.kt`
- Export: `data/persistence-room/schemas/6.json`
- Create after tests: `docs/adr/ADR-007-observation-store-isolation.md`

**Compile-time separation:**

```kotlin
interface RawObservationReader {
    suspend fun read(ref: RawObservationRef): Flow<ObservationChunk>
}

interface ProviderSafeObservationReader {
    suspend fun read(ref: ProviderSafeObservationRef): ProviderSafeObservationPayload
}
```

No conversion or subtype relation exists between the refs. Only `ObservationPipeline` can consume the raw reader and produce a safe ref.

- [ ] Write RED corpus tests for `.env`, private keys, tokens, Authorization headers, shadow hashes, password fields, database dumps, confidential logs, binary/invalid encoding, control bytes, internal topology, huge output and prompt-injection text.
- [ ] Test pre-content classification from operation/path/workspace before reading bytes. Known secret source is hard BLOCK even if content scanner misses it; uncertainty is at least SENSITIVE.
- [ ] Write deterministic transformation golden tests: fixed placeholders/counts, allowlisted structured fields, known log fields, byte/line counts, exit status and bounded safe excerpts. No general/cloud LLM summarizer may receive raw input.
- [ ] Add compile architecture tests proving Provider/Context Builder cannot import `RawObservationReader/Ref`; Egress Guard cannot grant execution; UI Permission decision cannot satisfy Egress decision; crash/logging packages cannot materialize either store.
- [ ] Write decision-binding RED tests: raw digest, sensitivity, transform version, destination Provider/model and expiry must match; Provider/model change invalidates; ordinary ASK cannot override known SECRET.
- [ ] Run `./gradlew :security:egress:test :architecture-tests:test :core:runtime:test`; expect RED.
- [ ] Implement local source classifier → byte scanner → known structured parser → uncertainty rule → policy. Keep execution risk and sensitivity as separate facts.
- [ ] Implement physically separate encrypted safe blob location/key purpose and schema v6 metadata. A safe record contains source raw digest + transformation version but never aliases/overwrites raw evidence.
- [ ] Let `WorkingContextBuilder` convert only `ProviderSafeObservationPayload` into an `UntrustedObservationBlock` with explicit data role/length/digest; never concatenate server text into system/developer instructions.
- [ ] Implement a separate Egress Decision Card with safe metadata and transformation description, not hard-blocked bytes. Copy makes clear that “local safe extraction” did not expose raw data to the cloud.
- [ ] Resume the Agent loop only with `ProviderSafeObservationRef`. Prompt-injection text can cause a later model proposal, but that proposal must traverse a brand-new Envelope/Permission path.
- [ ] Add migration 5→6 and tests for recovered old raw observations under a stricter current hard block. Current Egress floor can tighten; it cannot silently loosen historical constraints.
- [ ] Run `./gradlew :security:egress:test :architecture-tests:test :data:persistence-room:test :core:runtime:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; expect PASS.
- [ ] Accept ADR-007 with type/store/key/access boundaries.
- **Slice integration label:** `security: isolate and guard observation egress`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E13.1 | Sensitivity/source corpus | Add `EgressAdversarialCorpusTest.secretSourcesHardBlockAndUnknownIsSensitive`; run `./gradlew :security:egress:test --tests '*EgressAdversarialCorpusTest'`; RED = classifier/scanner absent or fixture under-classified. | Add source classifier and byte scanner only; same command → PASS; commit `security: classify observation sensitivity`. |
| E13.2 | Deterministic transformations | Add `DeterministicTransformerGoldenTest.outputIsBoundedStableAndLocal`; run `./gradlew :security:egress:test --tests '*DeterministicTransformerGoldenTest'`; RED = transform/parser output absent or non-deterministic. | Add allowlisted parsers/fixed redaction only; rerun the exact command → PASS; commit `security: transform observations locally`. |
| E13.3 | Type/module separation | Add `EgressArchitectureTest.providerCannotImportRawObservationAndRefsDoNotConvert`; run `./gradlew :architecture-tests:test --tests '*EgressArchitectureTest'`; RED = forbidden import/conversion is possible. | Add separate raw/safe APIs and narrow `ObservationEgressPort` only; same command → PASS; commit `security: separate raw and provider safe types`. |
| E13.4 | Physical safe store/schema 6 | Add `Migration5To6Test.rawAndSafeUseSeparateLocationsKeysAndRefs`; run `./gradlew :data:persistence-room:test --tests '*Migration5To6Test'`; RED = schema/store boundary absent. | Add safe store/key purpose/metadata and migration only; same command → PASS; commit `data: isolate provider safe observations`. |
| E13.5 | Egress decision authority | Add `EgressDecisionAuthorityTest.bindsRawDigestTransformProviderModelAndExpiry`; run `./gradlew :core:runtime:test --tests '*EgressDecisionAuthorityTest'`; RED = stale/mismatched decision accepted. | Add separate authority fact/command validation only; same command → PASS; commit `security: bind observation egress decisions`. |
| E13.6 | Provider context continuation | Add `WorkingContextBuilderTest.acceptsOnlySafeRefAsUntrustedDataBlock`; run `./gradlew :core:runtime:test --tests '*WorkingContextBuilderTest'`; RED = raw ref/text reaches request or builder absent. | Add safe-reader context block conversion only; same command → PASS; commit `feat: continue agent with guarded output`. |
| E13.7 | Egress UI/local secret handling | Add `EgressDecisionUiTest.secretBytesNeverRenderInDecisionCard`; run `./gradlew :presentation:testDebugUnitTest --tests '*EgressDecisionUiTest'`; RED = card/view state absent or secret shown. | Add metadata-only Egress Card/local viewer routing only; same command → PASS; commit `feat: show separate egress decisions`. |
| E13.8 | Migration/slice acceptance | Add `EgressRecoveryTest.stricterCurrentFloorReclassifiesOldRawWithoutLoosening`; run `./gradlew :security:egress:test :architecture-tests:test :data:persistence-room:test :core:runtime:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; RED = recovery/architecture/canary assertion fails. | Fix only migration/integration evidence, accept ADR-007; rerun the exact command → PASS; commit `test: accept observation egress boundary`. |

**Reviewer Gate 13:** Reject if any raw bytes can reach a Provider/log/analytics path, user ASK can override SECRET, summaries use a cloud model, or execution approval and egress approval share an authority type.

### Task 14: Add Evidence-Based Verification and RUN_RESULT

**Depends on:** Task 12 (and therefore the Task 13 Egress prerequisite)  
**User-visible result:** After a modification or post-mutation restart, the Agent resolves the exact immutable Verification Plan persisted before approval, executes its checks, shows criterion/check/evidence/evaluator/verdict rows, and only marks the Task complete when the original applicable REQUIRED criteria pass  
**Capabilities added:** Verification execution/check/evidence/result persistence, evaluator registry, weakening detection over pre-existing plan versions, CompletionGate, honest RUN_RESULT; no new or regenerated success definition  
**Traceability:** `B36.3`, `B36.6`, `B36.8`, `B36.10`, `UX34.6`, `UX34.11`, `UX34.13`, `UX34.14`, `UX35`, `SA32.5`, `SA32.7`, `SA32.12`, `SA32.13`

**Files:**

- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/verification/{VerificationCheck,EvidenceRef,VerificationEvaluatorResult,VerificationVerdict}.kt`
- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/verification/{VerificationRegistry,CompletionGate,PlanWeakeningDetector}.kt`
- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/verification/evaluator/{ExitStatusEvaluator,HttpStatusEvaluator,ServiceStateEvaluator,ExactValueEvaluator}.kt`
- Create: `core/runtime/src/test/kotlin/dev/cockpit/runtime/verification/VerificationCompletionTest.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/migration/Migration6To7.kt`
- Create: `data/projection-models/src/main/kotlin/dev/cockpit/projection/model/{VerificationProjection,RunResultProjection}.kt`
- Create: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/task/{VerificationSection,VerificationEvidenceScreen,RunResultBlock}.kt`
- Export: `data/persistence-room/schemas/7.json`
- Create after tests: `docs/adr/ADR-014-verification-completion-gate.md`

**Trust chain:**

```kotlin
data class VerificationOutcome(
    val criterion: VerificationCriterion,
    val check: VerificationCheck,
    val evidence: EvidenceRef,
    val evaluatorKind: VerificationEvaluatorKind,
    val evaluatorVersion: UInt,
    val verdict: VerificationVerdict,
)
```

- [ ] Write RED tests for deterministic examples: expected HTTP 200/actual 200 → PASS; expected service `active`/actual `active` → PASS; mismatches → FAIL; parse/version/evidence ambiguity → INCONCLUSIVE.
- [ ] Test that exit code 0 alone cannot satisfy Task success, model prose cannot create high-trust PASS, and MODEL_ASSISTED evaluation cannot alone pass a required critical criterion.
- [ ] Write CompletionGate tests: every REQUIRED criterion needs acceptable PASS; any required FAIL/INCONCLUSIVE blocks COMPLETED; optional failures are shown; missing evidence/digest mismatch blocks.
- [ ] Write Plan weakening tests against the immutable Schema-4 definitions: after a Permission request is shown, removing/downgrading/rewording a key criterion, switching below its minimum evaluator trust or broadening tolerance cannot alter the bound version. Before mutation it invalidates authority and requires a newly persisted version/re-plan/confirmation; after mutation the original applicable REQUIRED definitions remain the CompletionGate source. A strictly stronger compatible plan is a new immutable version, never an update.
- [ ] Run `./gradlew :core:runtime:test --tests '*Verification*'`; expect RED.
- [ ] Implement versioned evaluator registry. Evaluators receive local evidence refs through explicit authority and return structured actual/expected data; natural-language explanation is presentation only.
- [ ] Make verification checks normal read-only Envelopes: Safe Read/Permission and Egress rules still apply. Local deterministic evaluator may read authorized raw evidence even when Provider egress is blocked.
- [ ] Persist schema v7 `VerificationCheck`, evidence, evaluator result, verdict and CompletionGate-related facts append-only, each foreign-keyed/bound to the exact Schema-4 `planId + version + planDigest + criterionId + criterionVersion`. Do not duplicate, overwrite or first-create Verification Plan/criterion definitions in schema v7.
- [ ] Implement factual Verification UI: trust label, expected/actual, evidence timestamp/digest, evaluator version, PASS/FAIL/INCONCLUSIVE. Persona styling cannot recolor or rewrite system verdict semantics.
- [ ] Generate `RUN_RESULT` only from Projection facts after CompletionGate. Separate “command executed”, “verification passed”, “task completed” and “user action still required”.
- [ ] Add migration 6→7 and recovery tests while verification is pending after Task-12 mutation, running, paused by egress, failed, and completed. The mutation→process-death fixture must resolve byte/digest-identical Schema-4 plan P, schedule checks only for P, retain every REQUIRED criterion and never call the model to reconstruct it. No restart may synthesize PASS.
- [ ] Run `./gradlew :core:runtime:test :data:persistence-room:test :data:projection:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; expect PASS.
- [ ] Accept ADR-014 from the evaluator/CompletionGate evidence.
- **Slice integration label:** `feat: verify execution with evidence-backed completion`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E14.1 | Verification execution/evaluators | Add `VerificationCompletionTest.deterministicExamplesProduceStructuredVerdictsForPersistedCriteria`; run `./gradlew :core:runtime:test --tests '*deterministicExamplesProduceStructuredVerdictsForPersistedCriteria'`; RED = check/evidence/result types, registry or evaluators are absent. | Add execution-side values and four deterministic evaluators consuming Task-10 definitions only; rerun the exact command → PASS; commit `feat: evaluate persisted verification criteria`. |
| E14.2 | Completion gate/legal states | Add `VerificationCompletionTest.requiredPassTransitionsRunCompletedAndTaskResolved`; run `./gradlew :core:runtime:test --tests '*VerificationCompletionTest.requiredPassTransitionsRunCompletedAndTaskResolved'`; RED = missing CompletionGate or wrong lifecycle vocabulary. | Add gate transition only: required PASS → `RunState.COMPLETED` + `TaskStatus.RESOLVED`; rerun the exact command → PASS; commit `feat: gate run completion on verification`. |
| E14.3 | Plan weakening | Add `PlanWeakeningDetectorTest.boundSchema4PlanCannotBeOverwrittenOrReplacedAfterMutation`; run `./gradlew :core:runtime:test --tests '*PlanWeakeningDetectorTest'`; RED = weakened/newly regenerated criteria can replace the Permission-bound version. | Add immutable version/digest comparison and pre-mutation invalidation/post-mutation original-plan selection only; rerun the exact command → PASS; commit `security: preserve approved verification definitions`. |
| E14.4 | Local evidence authority | Add `VerificationEvidenceAccessTest.egressBlockStillAllowsAuthorizedLocalEvaluator`; run `./gradlew :core:runtime:test --tests '*VerificationEvidenceAccessTest'`; RED = evaluator cannot read permitted local evidence or bypasses authority. | Add narrow local evidence authorization only; rerun the exact command → PASS; commit `security: authorize local verification evidence`. |
| E14.5 | Schema 7 execution facts | Add `Migration6To7Test.preservesSchema4PlanAndAddsOnlyChecksEvidenceResultsAndVerdicts`; run `./gradlew :data:persistence-room:test --tests '*Migration6To7Test'`; RED = migration recreates/loses plan definitions, lacks exact foreign keys, or can synthesize PASS. | Add only append-only check/evidence/evaluator-result/verdict/CompletionGate facts bound to Schema-4 definitions; rerun the exact command → PASS; commit `data: persist verification execution trust chain`. |
| E14.6 | Verification UI/RUN_RESULT | Add `VerificationUiTest.showsExpectedActualTrustAndSeparatesExecutionFromCompletion`; run `./gradlew :presentation:testDebugUnitTest --tests '*VerificationUiTest'`; RED = rows/result distinctions absent. | Add projections/UI generated only after CompletionGate; same command → PASS; commit `feat: show evidence backed run result`. |
| E14.7 | Verification recovery | Add `VerificationRecoveryTest.postMutationRestartContinuesAgainstExactPersistedPlan`; run `./gradlew :core:runtime:test --tests '*VerificationRecoveryTest'`; RED = P/REQUIRED criteria are lost/regenerated/weakened or nonterminal state resumes as PASS/COMPLETED. | Add recovery that resolves the original binding and schedules only its outstanding checks; run `./gradlew :core:runtime:test :data:persistence-room:test` → PASS; commit `test: recover exact verification plan without false pass`. |
| E14.8 | Slice acceptance | Add `VerificationSliceAcceptanceTest.modelTextAndExitZeroCannotResolveTask`; run `./gradlew :core:runtime:test :data:persistence-room:test :data:projection:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; RED = any trust/state/UI assertion fails. | Fix only slice integration, accept ADR-014; rerun the exact command → PASS; commit `test: accept verification completion slice`. |

**Reviewer Gate 14:** Reject if model text can award PASS, key success criteria can be silently weakened/regenerated after mutation, schema 7 first-creates or overwrites plan definitions, egress BLOCK prevents permitted local deterministic checking, or `RunState.COMPLETED` / `TaskStatus.RESOLVED` precedes CompletionGate over the exact Permission-bound REQUIRED criteria.

### Task 15: Preserve UNKNOWN_OUTCOME and Reconcile Across Runs

**Depends on:** Task 14 (which already implies Tasks 12 and 13)  
**User-visible result:** If connectivity/process loss makes a modification uncertain, the phone shows Needs Attention, Cancel only stops the Run, Retry is mutation-blocked, and a read-only reconciliation establishes executed/not-executed/unknown truth before progress resumes  
**Capabilities added:** RECONCILING, cross-run external-side-effect obligation, conflict preflight, Needs Attention/Activity recovery paths  
**Traceability:** `B36.3`, `B36.8`–`B36.10`, `UX34.6`, `UX34.7`, `UX34.12`–`UX34.14`, `UX35`, `SA32.2`–`SA32.5`, `SA32.9`, `SA32.13`

**Files:**

- Create: `core/domain/src/main/kotlin/dev/cockpit/domain/run/{UnresolvedSideEffectObligationFact,ConflictScope,ReconciliationVerdict}.kt` (facts/value objects, not a new top-level Domain entity)
- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/recovery/{DeliveryOutcomeReducer,AuthoritativeConflictObligationIndex,ReconciliationCoordinator}.kt`
- Create: `core/runtime/src/test/kotlin/dev/cockpit/runtime/recovery/CrossRunUnknownOutcomePropertyTest.kt`
- Create: `data/persistence-room/src/main/kotlin/dev/cockpit/persistence/room/migration/Migration7To8.kt`
- Create: `data/projection-models/src/main/kotlin/dev/cockpit/projection/model/{NeedsAttentionProjection,ReconciliationProjection}.kt`
- Modify: `presentation/src/main/kotlin/dev/cockpit/presentation/ui/{home,task,activity}/*`
- Export: `data/persistence-room/schemas/8.json`

**Required transaction:**

```text
delivery ambiguity detected
→ persist ToolCall UNKNOWN_OUTCOME
→ persist source Envelope/target/conflict facts
→ create unresolved obligation
→ transition Run to RECONCILING or CANCELLED if Cancel already won
→ commit
→ only then notify UI / close owner
```

```kotlin
@Test fun `cancel and retry cannot clear an unresolved external effect`() = runTest {
    fixture.loseConnectionAfterRequestWrite()
    fixture.cancelRun()
    val retry = fixture.retryTask()
    assertThat(retry.nextMutation()).isEqualTo(MutationGateBlocked.RECONCILIATION_REQUIRED)
    assertThat(recordingServer.newMutatingRequests).isEmpty()
}
```

- [ ] Write the three exact cross-run acceptance sequences from System Architecture §27.13 as RED tests, plus same Task, same Workspace/Host, overlapping resource, unknown conflict scope, Archive/restore, reboot and projection deletion/rebuild.
- [ ] Add property tests over randomized event/cancel/retry/recovery sequences: while any potentially conflicting obligation is unresolved, zero mutating tickets and zero exec writes can occur.
- [ ] Fault-inject every transport boundary from `PREPARATION_NOT_STARTED` through `CHANNEL_CLOSED_WITHOUT_EXIT`; verify only evidence before `REQUEST_WRITE_STARTED` can prove command not sent, while mutation ambiguity at/after write creates obligation.
- [ ] Write UI RED tests for Needs Attention priority/deduplication, Activity facts, source Run link, read-only reconciliation progress, “still unknown” pause and the distinction among Cancel Run, Retry Task and Close Task.
- [ ] Run `./gradlew :core:runtime:test :data:persistence-room:test :presentation:testDebugUnitTest`; expect RED.
- [ ] Implement schema v8 authoritative obligation facts/index. Projection loss cannot remove authority; mutation preflight queries authoritative unresolved facts with a conservative conflict matcher.
- [ ] On Cancel during uncertainty, persist obligation first and mark Run CANCELLED; Task remains OPEN. Retry creates a new Run whose first execution precondition is reconciliation, not mutation.
- [ ] Implement read-only reconciliation from the source target/envelope/conflict facts. `CONFIRMED_NOT_EXECUTED` → re-plan/new Envelope; `CONFIRMED_EXECUTED` → Verification; still unknown → WAITING_USER/PAUSED. Never blindly replay the source command.
- [ ] Make reconciliation checks traverse Safe Read and Observation/Egress. A changed Host Key prevents trusted reconciliation and leaves obligation unresolved.
- [ ] Implement Needs Attention and Activity projections from facts; global active Run affordance remains singular. Dismiss/archive affects presentation only, never obligation truth.
- [ ] Add migration 7→8. Old persisted ambiguous delivery facts migrate conservatively to unresolved obligations or safe PAUSED review; never infer not-executed from missing data.
- [ ] Run `./gradlew :core:runtime:test :data:persistence-room:test :data:projection:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; expect PASS with network-drop/process-kill scenarios.
- **Slice integration label:** `feat: reconcile cross-run unknown ssh outcomes`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E15.1 | Delivery reducer | Add `DeliveryOutcomeReducerTest.onlyPreWriteEvidenceCanConfirmNotSent`; run `./gradlew :core:runtime:test --tests '*DeliveryOutcomeReducerTest'`; RED = ambiguous write maps optimistically or reducer absent. | Add evidence-to-delivery reduction only; same command → PASS; commit `feat: reduce ssh delivery truth`. |
| E15.2 | Obligation schema/index | Add `Migration7To8Test.ambiguityCreatesAuthoritativeObligation`; run `./gradlew :data:persistence-room:test --tests '*Migration7To8Test'`; RED = schema/index/migration absent. | Add obligation facts/index and conservative migration only; same command → PASS; commit `data: persist unresolved side effects`. |
| E15.3 | Conflict property | Add `CrossRunUnknownOutcomePropertyTest.unresolvedConflictIssuesZeroMutatingTickets`; run `./gradlew :core:runtime:test --tests '*CrossRunUnknownOutcomePropertyTest'`; RED = randomized sequence reaches Ticket/write. | Add authoritative conservative conflict query to mutation preflight only; rerun the exact command → PASS; commit `security: block conflicting cross run mutation`. |
| E15.4 | Cancel/retry semantics | Add `CrossRunUnknownOutcomePropertyTest.cancelAndRetryCannotClearObligation`; run `./gradlew :core:runtime:test --tests '*CrossRunUnknownOutcomePropertyTest.cancelAndRetryCannotClearObligation'`; RED = new Run bypasses source uncertainty. | Persist obligation before Cancel and require reconciliation on Retry only; rerun the exact command → PASS; commit `feat: preserve uncertainty across cancel retry`. |
| E15.5 | Read-only reconciliation | Add `ReconciliationCoordinatorTest.threeVerdictsRouteWithoutMutationReplay`; run `./gradlew :core:runtime:test --tests '*ReconciliationCoordinatorTest'`; RED = coordinator/check path absent or source command replays. | Add read-only checks and confirmed-executed/not-executed/still-unknown routing only; rerun the exact command → PASS; commit `feat: reconcile remote execution truth`. |
| E15.6 | Needs Attention projections | Add `ReconciliationUiTest.deduplicatesObligationAndDistinguishesRunTaskActions`; run `./gradlew :presentation:testDebugUnitTest --tests '*ReconciliationUiTest'`; RED = projection/UI path absent. | Add Needs Attention/Activity/reconciliation projections and CTAs only; same command → PASS; commit `feat: surface unknown outcomes`. |
| E15.7 | Reconciliation migration/recovery | Add `ObligationRecoveryTest.archiveRebootAndProjectionRebuildPreserveBlock`; run `./gradlew :core:runtime:test --tests '*ObligationRecoveryTest'`; RED = any lifecycle clears truth. | Add rebuild/recovery wiring only; run `./gradlew :core:runtime:test :data:persistence-room:test` → PASS; commit `test: preserve obligations through recovery`. |
| E15.8 | Slice acceptance | Add `UnknownOutcomeAcceptanceTest.cancelRetryReconcileMatchesAllThreeFrozenSequences`; run `./gradlew :core:runtime:test :data:persistence-room:test :data:projection:test :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; RED = any cross-run/Host-Key/egress assertion fails. | Fix only slice integration/evidence; rerun the exact command → PASS; commit `test: accept unknown outcome reconciliation`. |

**Reviewer Gate 15:** Reject if Cancel/Retry/new Run ID clears uncertainty, conflict lookup uses a projection, missing evidence becomes NOT_EXECUTED, reconciliation mutates, or changed Host Key is ignored.

---

## Phase 5 — Android Recovery and Release

### Task 16: Integrate Android Run Hosting and Process-Death Recovery

**Depends on:** Gate E and Task 15  
**User-visible result:** A user-started active Run remains visibly hosted when the approved background branch allows it, or pauses honestly in foreground-only builds; Activity/process loss, FGS denial and relaunch recover without replaying mutation  
**Capabilities added:** production RunHost, notification projection, owner-epoch recovery bootstrap, ephemeral-capability invalidation, offline/pause/re-entry behavior  
**Traceability:** `B36.3`, `B36.8`–`B36.10`, `UX34.7`, `UX34.12`–`UX34.14`, `UX35`, `SA32.2`–`SA32.5`, `SA32.11`, `SA32.13`

**Files:**

- Create: `core/runtime/src/main/kotlin/dev/cockpit/runtime/recovery/{RunRecoveryBootstrap,FenceRecovery,OwnerEpochClaimer}.kt`
- Create: `platform/background/src/main/kotlin/dev/cockpit/background/{AndroidRunHost,ForegroundRunService,ForegroundOnlyRunHost,RunNotificationProjector}.kt`
- Create: `platform/background/src/androidTest/kotlin/dev/cockpit/background/RuntimeProcessDeathMatrixTest.kt`
- Create: `platform/android/src/main/kotlin/dev/cockpit/platform/android/RuntimeProcessGraph.kt`
- Modify conditionally: `app/src/main/AndroidManifest.xml`
- Create: `docs/evidence/slice-16-process-death-matrix.md`

**Recovery order:**

```text
open + migrate durable stores
→ validate safety/envelope/event versions
→ claim a new process owner epoch
→ reconstruct Snapshot + append-only inputs + facts
→ reconcile held fences/delivery ambiguity
→ rebuild obligations before projections
→ rebuild projections/notifications
→ expose PAUSED / attention state
→ require explicit current-owner continuation
```

```kotlin
@Test fun `authenticated pre-send session dies with the process and is never recovered`() {
    scenario.killAt(ProcessKillPoint.AUTHENTICATED_BEFORE_FINAL_PERMIT)
    val recovered = scenario.relaunchAndReadRun()
    assertThat(recovered.ephemeralCapabilities).isEmpty()
    assertThat(recordingServer.execRequests).isEmpty()
    assertThat(recovered.state).isIn(RunState.PAUSED, RunState.RECONCILING)
}
```

- [ ] Write instrumentation RED tests that kill only Activity and then the full app process at planning, Provider streaming, prepared unauthenticated transport, auth in progress, authenticated-before-T6-B, `HELD_PRE_SEND`, immediately before/after actual `SEND_STARTED`, output streaming, egress wait, verification and reconciliation.
- [ ] For each point assert: exactly one owner epoch after relaunch; no ephemeral handle/ticket/lease/session/permit recovers; no mutating request auto-replays; only proven pre-request outcomes become NOT_SENT; ambiguity creates/preserves obligation.
- [ ] Add API-level/OEM tests for notification denied, user Stop, FGS start denied/restricted, task swipe, force stop, reboot, offline start and network return. Network return wakes UI eligibility only; it does not execute.
- [ ] Run `./gradlew :platform:background:connectedCheck :core:runtime:test --tests '*Recovery*'`; expect RED.
- [ ] Implement `RunRecoveryBootstrap` as the only process composition entry for active Runtime. It validates schema/encoder/safety versions before claiming owner and fails closed to safe local review when unsupported.
- [ ] Integrate the Gate-E branch: `SPECIAL_USE_CANDIDATE` uses the precise reviewed FGS manifest/notification path; `FOREGROUND_ONLY` hosts while Activity is foreground and persists PAUSED on loss. Runtime itself is identical in both branches.
- [ ] Keep Service as a lifecycle host, never state owner. It submits typed commands and observes NotificationProjection; WorkManager, BroadcastReceiver and notification actions cannot call SSH directly.
- [ ] Make notification content metadata-only: Agent/task/status and Stop/Open actions; no command, raw output, server secret, username or sensitive evidence on lock screen.
- [ ] Re-evaluate current mandatory safety floor and unresolved obligations before any explicit resume. A stricter Runtime update records a new fact and may change old ALLOW to ASK/DENY; a relaxed update cannot widen old authority.
- [ ] Run the full matrix on API 28/31/34/36 emulators and the Gate-E OEM device. Store test reports and screen recordings under `docs/evidence/slice-16-process-death-matrix.md`.
- [ ] Run `./gradlew :core:runtime:test :platform:background:connectedCheck :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; expect PASS for the selected capability branch and the foreground-only fallback.
- **Slice integration label:** `feat: recover android runs safely across process death`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E16.1 | Recovery bootstrap | Add `RunRecoveryBootstrapTest.validatesVersionsBeforeOwnerClaim`; run `./gradlew :core:runtime:test --tests '*RunRecoveryBootstrapTest'`; RED = bootstrap/order/version rejection absent. | Add ordered bootstrap and owner-epoch claim only; same command → PASS; commit `feat: bootstrap durable run recovery`. |
| E16.2 | Process-kill harness | Add `RuntimeProcessDeathMatrixTest.ephemeralCapabilitiesNeverRecover`; run `./gradlew :platform:background:connectedDebugAndroidTest`; RED = kill points/harness or assertion absent. | Add instrumentation kill hooks and fact inspection only; same command → PASS for fake host; commit `test: add runtime process death matrix`. |
| E16.3 | Selected RunHost branch | Add `AndroidRunHostTest.gateEDecisionSelectsExactAdapter`; run `./gradlew :platform:background:connectedDebugAndroidTest`; RED = selected/fallback adapter binding absent. | Implement reviewed FGS candidate or foreground-only host without Runtime forks; same command → PASS; commit `feat: bind approved android run host`. |
| E16.4 | Notification/control | Add `RunNotificationTest.metadataOnlyStopAndOpenUseTypedCommands`; run `./gradlew :platform:background:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.background.RunNotificationTest`; RED = content/action boundary absent or sensitive field shown. | Add notification projector/actions only; rerun the exact command → PASS; commit `feat: show safe run notification controls`. |
| E16.5 | API/OEM failure matrix | Add `RuntimeProcessDeathMatrixTest.denialsStopsRebootOfflineNeverReplay`; on each named API/OEM target run `./gradlew :platform:background:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.background.RuntimeProcessDeathMatrixTest`; RED = any scenario missing or write count nonzero. | Add lifecycle failure handling only; rerun that exact per-device command → PASS; commit `test: cover android lifecycle failures`. |
| E16.6 | Resume safety preflight | Add `RunResumeSafetyTest.currentFloorAndObligationsRecheckedBeforeResume`; run `./gradlew :core:runtime:test --tests '*RunResumeSafetyTest'`; RED = old authority resumes under tighter/unresolved state. | Add explicit-resume preflight and new facts only; same command → PASS; commit `security: revalidate run resume`. |
| E16.7 | Slice acceptance | Add `AndroidRecoveryAcceptanceTest.selectedAndForegroundOnlyBranchesPauseSafely`; run `./gradlew :core:runtime:test :platform:background:connectedCheck :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest`; RED = branch/process/notification assertion fails. | Fix only composition/evidence; rerun the exact command → PASS and record matrix; commit `test: accept android recovery slice`. |

**Reviewer Gate 16:** Reject if Service owns domain state, background denial is hidden, notification leaks trusted/sensitive content, recovered capabilities are reused, or connectivity automatically replays mutation.

### Task 17: Release Hardening and MVP Golden Path

**Depends on:** Task 16 and all five recorded GO/fallback Gate decisions; this is the external-release join  
**User-visible result:** A release candidate demonstrates both Persona-led conversation and real Server Agent execution in one Agent-first framework, including approval, egress, verification, uncertainty recovery and safe Android lifecycle behavior  
**Capabilities added:** no new product capability; only completeness, accessibility, privacy, performance, migration, distribution and evidence hardening  
**Traceability:** every `B36.*`, `UX34.*`, `UX35`, and `SA32.*`

**Files:**

- Create: `app/src/androidTest/kotlin/dev/cockpit/mobile/golden/MvpGoldenPathTest.kt`
- Create: `app/src/androidTest/kotlin/dev/cockpit/mobile/golden/UnifiedAgentFrameworkTest.kt`
- Create: `data/persistence-room/src/androidTest/kotlin/dev/cockpit/persistence/AllMigrationPathsTest.kt`
- Create: `security-tests/src/test/kotlin/dev/cockpit/security/{ReleaseLeakageTest,AuthorityBypassTest,DependencyAdvisoryTest}.kt`
- Create: `docs/evidence/release-v0.1/{traceability.md,security-review.md,accessibility.md,performance.md,play-policy.md,golden-path.md}`
- Create: `docs/runbooks/disposable-mvp-test-host.md`
- Modify: release Gradle/manifest/R8/resource configuration only as proven by tests

**MVP golden path:**

```text
Configure Provider + Vault credential
→ create Persona-led Agent with no execution Skill
→ hold two Character-style Conversations in the normal Conversation shell
→ create Server Agent with SSH Skill + Workspace
→ onboard pinned Host Key and frozen host revision
→ ask why disposable demo service is down
→ Safe Read diagnosis (visible, locally guarded output)
→ model receives only Provider-safe observation
→ Agent proposes exact repair command + critical Success Criteria / Verification Plan
→ atomically persist immutable plan P + every criterion definition in Schema 4
→ create PermissionRequest bound to exact P ID / version / digest
→ trusted full-screen review of exact Envelope + P, then Approve Once
→ T6-P / T6-A / T6-B real execution on disposable host
→ local Egress Guard
→ deterministic service-state Verification PASS
→ evidence-backed RUN_RESULT / Run COMPLETED / Task RESOLVED
```

The disposable host exposes only a narrowly scoped test service and account. A second golden test injects disconnect at `REQUEST_WRITE_STARTED`, then proves Cancel → Retry remains blocked → read-only reconciliation → verification/re-plan.

```kotlin
@Test fun `golden repair completes only after persisted plan and deterministic pass`() = runTest {
    golden.askServerAgentToRepairDemoService()
    val approvedPlan = golden.persistAndReviewExactVerificationPlan()
    golden.approveExactPendingEnvelope()
    golden.executeMutationSuccessfully()
    golden.restartBeforeVerification()
    assertThat(golden.recoveredVerificationPlan()).isEqualTo(approvedPlan)
    assertThat(golden.run().state).isNotEqualTo(RunState.COMPLETED)
    assertThat(golden.task().status).isEqualTo(TaskStatus.OPEN)
    golden.awaitVerification()
    assertThat(golden.run().state).isEqualTo(RunState.COMPLETED)
    assertThat(golden.task().status).isEqualTo(TaskStatus.RESOLVED)
    assertThat(golden.task().requiredCriteria).allMatch { it.verdict == VerificationVerdict.PASS }
}
```

- [ ] Write the two end-to-end tests first and make them fail against the current integrated build. All cloud responses use recorded/fake Provider fixtures; real SSH targets only the disposable controlled host.
- [ ] Add a unified-framework test: Persona/visual language can differ by Agent identity/capabilities, but both Agents use the same Agent → Conversations → Message/Task projection and Composer framework; no `CharacterMode`/`ServerMode` switch or second Runtime exists.
- [ ] Add chained and direct Room migration tests from every exported schema 1…8 to current, plus fresh install. Seed active Run and, at the schema where each first appears, Envelope/permission plus complete immutable plan P and criterion definitions in Schema 4, raw/safe observations in Schema 6, verification checks/evidence/evaluator results/verdicts in Schema 7, and unresolved obligations in Schema 8. Every path must preserve P's canonical bytes/digest, stable criterion IDs, REQUIRED/ADVISORY classification, typed conditions, minimum trust and provenance; migrations must not fabricate, regenerate or weaken definitions.
- [ ] Run security review: module/authority bypass tests, exact-byte adversarial corpus, Safe Read/risk corpus, missing/corrupt/unsupported/incomplete Verification Plan and digest-mismatch fixtures proving zero mutating request bytes, post-mutation/pre-verification crash recovery of exact P, secret canary scan, backup/data extraction, TLS config, release log stripping, dependency verification/advisory scan, debug component/exported component inspection and R8 smoke test.
- [ ] Run accessibility review: TalkBack traversal, switch access, 200% font, contrast, touch targets, motion reduction, byte-token spoken labels, permission review completeness and no color-only verdict/risk semantics.
- [ ] Run privacy review: screenshots/recents protection on Vault/permission/raw-secret views as specified, lock-screen notification redaction, clipboard warnings/expiry behavior, erase/retention flow and no analytics content payload.
- [ ] Run performance/resource tests with explicit checked-in budgets: bounded Provider/SSH/projection/UI queues, raw spool storage cap/truncation fact, safe-context cap, projection rebuild time, 10k-message Conversation scroll and 10 MiB binary output. Budget exceedance must degrade visibly/fail closed, never drop authority facts.
- [ ] Re-read current target API and FGS/Play rules, set production manifest to the Gate-E approved branch, update the declaration/video evidence, and prove the foreground-only build variant still passes. Do not submit/publish without separate user authorization.
- [ ] Exercise the golden path on API 28 and API 36 plus the selected physical device. Capture screen recording, Runtime audit IDs/digests and disposable-host before/after evidence without secrets.
- [ ] Run the full release suite from a clean checkout:

```bash
./gradlew clean test connectedCheck lint dependencyCheckAnalyze verifyArchitecture verifyReleaseEvidence bundleRelease
```

Expected: every task PASS, zero unverified dependencies, all traceability links resolve, release AAB builds, no debug/fake transport/provider code is packaged.

- [ ] Perform a manual evidence review: command bytes/digests align across Envelope/review/audit/transport; the Permission-bound plan ID/version/digest aligns across Schema-4 persistence/review/T6/recovery/CompletionGate; required Verification PASS backs `RunState.COMPLETED` and `TaskStatus.RESOLVED`; injected UNKNOWN obligation survives Cancel/retry/restart.
- **Slice integration label:** `release: harden and verify mobile agent runtime v0.1`

#### Execution Tasks

| ID | Focus | Focused RED and exact expected failure | Minimal Green, focused verification, commit |
|---|---|---|---|
| E17.1 | Unified framework golden | Add `UnifiedAgentFrameworkTest.characterAndServerAgentsShareConversationFramework`; run `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.mobile.golden.UnifiedAgentFrameworkTest`; RED = mode split or golden flow incomplete. | Fix only shared projections/navigation/composer integration; same command → PASS; commit `test: prove unified agent conversation framework`. |
| E17.2 | Repair golden lifecycle | Add `MvpGoldenPathTest.repairCompletesOnlyAfterPersistedPlanAndDeterministicPass`; run `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.mobile.golden.MvpGoldenPathTest#repairCompletesOnlyAfterPersistedPlanAndDeterministicPass`; RED = plan is not durable before Permission/mutation, binding changes across T6/recovery, or final state/evidence is wrong. | Fix only golden integration against fixtures/disposable host; prove exact P survives a post-mutation/pre-verification restart and only its REQUIRED deterministic PASS permits `RunState.COMPLETED` / `TaskStatus.RESOLVED`; rerun the exact command → PASS; commit `test: prove durable verified repair golden path`. |
| E17.3 | UNKNOWN golden | Add `MvpGoldenPathTest.disconnectCancelRetryRequiresReconciliation`; run `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.mobile.golden.MvpGoldenPathTest#disconnectCancelRetryRequiresReconciliation`; RED = mutation retry bypasses obligation. | Fix only fault/reconciliation integration; rerun the exact command → PASS; commit `test: prove unknown outcome golden path`. |
| E17.4 | All migrations | Add `AllMigrationPathsTest.schemasOneThroughEightUpgradeWithoutLoss`; run `./gradlew :data:persistence-room:connectedDebugAndroidTest`; RED = a direct/chained fixture loses/fabricates Schema-4 plan definitions, misplaces Schema-7 execution facts, or otherwise fails. | Add missing supported migration wiring only while preserving exact P/criteria and their later execution references; same command → PASS; commit `test: verify all database migrations`. |
| E17.5 | Security release | Add/enable `ReleaseLeakageTest`, `AuthorityBypassTest`, `DependencyAdvisoryTest`; run `./gradlew :security-tests:test dependencyCheckAnalyze verifyArchitecture`; RED = canary/bypass/advisory/forbidden edge, invalid plan reaches T6 send, or recovery regenerates criteria. | Remove only reported release leak/bypass/dependency issue; retain fail-closed zero-byte mutation and exact-plan crash recovery; same command → PASS; commit `security: harden release boundaries`. |
| E17.6 | Accessibility/privacy | Add `ReleaseAccessibilityPrivacyTest.requiredDeviceEvidenceIsComplete`; run `./gradlew :presentation:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.cockpit.presentation.ReleaseAccessibilityPrivacyTest`; RED = test/evidence assertion missing. | Fix only accessibility, redaction, clipboard/recents/retention issues; rerun the exact command → PASS; commit `fix: meet release accessibility privacy gates`. |
| E17.7 | Performance/policy | Add `ReleaseBudgetAndPolicyEvidenceTest.budgetsAndCurrentPolicyEvidencePass`; run `./gradlew :architecture-tests:test --tests '*ReleaseBudgetAndPolicyEvidenceTest'`; RED = budget/source timestamp/branch proof absent. | Tune bounded queues/storage/projection only and refresh policy evidence; run `./gradlew verifyReleaseEvidence` → PASS; commit `test: verify release budgets and policy`. |
| E17.8 | Clean release acceptance | From a clean checkout run `./gradlew clean test connectedCheck lint dependencyCheckAnalyze verifyArchitecture verifyReleaseEvidence bundleRelease`; RED = any task, trace link, dependency verification or release bundle fails. | Make no new capability; fix only integration/evidence and rerun the exact command → PASS; commit `release: verify mobile agent runtime v0.1`. |

**Reviewer Gate 17:** Reject the release candidate for any missing traceability evidence, migration/data loss, missing/fabricated/weakened Verification Plan definition, mutating authority before exact Schema-4 plan resolution, packaged fake/debug capability, stale policy assumption, accessibility blocker on approval, secret leak, authority bypass, false `RunState.COMPLETED` / `TaskStatus.RESOLVED`, or mutation replay.

---

## Schema and Migration Sequence

| Schema | Introduced by | Authoritative additions | Required upgrade proof |
|---|---|---|---|
| 1 | Task 7 | Agent, Persona, Conversation, Message, Draft | fresh install; archive/draft round trip |
| 2 | Task 8 | Provider profile metadata, Agent binding | 1→2 keeps all conversation ordinals/drafts; secret stays in Vault |
| 3 | Task 9 | Task, Run, immutable Snapshot, runtime inputs/facts, active slot, projection checkpoint | 2→3; active fake Run recovery and projection rebuild |
| 4 | Task 10 | Envelope bytes/digests, permission request/decision, safety facts/Fence, immutable `VerificationPlanVersion` and complete `VerificationCriterion` definitions/canonical digest | 3→4 pauses old execution; never fabricates Envelope, plan or criteria; no mutation authority until a newly persisted complete plan resolves |
| 5 | Task 11 | SSHHost revision, Workspace/target, observation metadata/blob commit | 4→5; target/key identity preserved; partial blob rollback |
| 6 | Task 13 | sensitivity/egress facts and physically separate provider-safe refs | 5→6; old raw data reclassified under current floor |
| 7 | Task 14 | Verification checks, evidence, evaluator results, verdicts and CompletionGate facts referencing Schema-4 plan/criteria | 6→7 preserves exact Schema-4 definitions; missing evidence never becomes PASS and no plan is regenerated |
| 8 | Task 15 | unresolved external-side-effect facts/index and reconciliation | 7→8; ambiguous legacy facts migrate conservatively |

Every schema export is immutable after its slice release tag. Task 17 tests both adjacent chained migration and supported direct Room migration paths; no `fallbackToDestructiveMigration`, drop/recreate or “clear app data” acceptance path is permitted.

## Test Infrastructure Ownership

| Fixture | Created | Used by | Constraint |
|---|---|---|---|
| `DeterministicDemoAgent` | Task 7 | UI continuity tests | debug/androidTest only |
| MockWebServer Provider corpus | Task 8 | Provider, Runtime, golden path | no real cloud cost or secrets in CI |
| Immutable Verification Plan corpus | Task 10 | Permission/T6, Task 12 crash recovery, Task 14 completion, release | canonical plan bytes/digests + corrupt/missing/unsupported/incomplete fixtures; no model regeneration |
| `FakeTransportPreparationPort` / `FakeAuthorizedExecutionPort` / `FakeExecutionControlPort` | Gate C | Runtime race/fault tests | exact same three narrow public Ports; no fake or production façade may reunify them |
| `RecordingSshServer` | Gate A | SSH contract/fault tests | pause/capture every protocol boundary and raw bytes |
| Disposable OpenSSH container | Gate A | interoperability, real read/modify/reconcile | dedicated keys/account/fixtures; never personal Host |
| Android process-death harness | Gate E | Task 16/release | owner epoch and delivery facts asserted after relaunch |
| Canary secret corpus | Gate D1 | Vault/Egress/release | scan DB/blob/log/report/clipboard/notification outputs |

## Security Gates by Slice

| Gate | First enforced | Must remain green thereafter |
|---|---|---|
| Module trust boundary | Task 1 | UI/Provider/Skill cannot reach concrete SSH, Vault secret, raw store or authority constructors |
| Canonical authority | Gate B | analyzed = approved = executed Envelope/digest; schema versions fail closed |
| Send authorization | Gate C | no auth before Ticket, no wire write before final same-turn permit, no stale session reuse |
| Credential isolation | Gate D1 / D2 | D1 Provider invocation-scoped authorization + Keystore/backup/leakage; D2 Ticket-scoped one-use SSH lease |
| Provider isolation | Task 8 | normalized contract, TLS, cancellation, no raw observation type visibility |
| Success definition + Safe Read/Permission | Task 10 | immutable plan/criteria persist before mutating Permission; uncertainty raises privilege; trusted exact-byte/criteria review; stale or unresolved authority rejected |
| SSH target/output | Task 11 | frozen endpoint/key, exact bytes, truthful delivery, encrypted raw spool |
| Egress | Task 13 | raw/safe physical and type separation; SECRET hard block |
| Mutation | Task 12 (after Task 13) | Approve Once, hard CRITICAL DENY, no false success |
| Verification | Task 14 | evidence/evaluator verdict; required CompletionGate |
| Unknown/recovery | Task 15 | cross-run conflict block and read-only reconciliation |
| Android lifecycle | Task 16 | visible/stoppable host or honest fallback; no replay after death |
| Release | Task 17 | all prior gates + migration/accessibility/privacy/dependency evidence |

## Technical Go / No-Go Dependency Order

```text
Task 1 → {Task 7, Gate D1, Gate A, Gate B, Gate E}
Task 7 + Gate D1 → Task 8 → Task 9
Gate B → Gate C
Task 9 + Gate B + Gate C → Task 10
Gate D1 + Gate C → Gate D2
Gate A + Gate D2 + Task 10 → Task 11 READ
Task 11 → Task 13 Egress → Task 12 MODIFY → Task 14 Verification → Task 15 Reconciliation
Task 15 + Gate E decision → Task 16 Recovery
Task 16 + all recorded Gate decisions → Task 17 Release
```

Gate E joins only at Task 16; it is not a Task 10/11 dependency. Reviewer Slice numbers are stable IDs, not schedule order, so the real path intentionally runs Task 13 before Task 12. Gate A/B/C/D2 failure blocks real SSH/mutation slices, while D1 independently unlocks Provider chat. Gate E failure selects the tested foreground-only branch and blocks only background continuation/external release, not foreground Tasks 7–15. A Gate cannot be waived by a product toggle if the failing criterion protects Envelope identity, authority, Credential, target identity, egress or uncertainty semantics.

The preferred first-feedback schedule is `Task 1 → Task 7 → Gate D1 → Task 8 → Task 9`. Task 7 and D1 are technically independent after Task 1, but the single-active-slice rule and user-feedback priority select Task 7 first. After D1 records GO, Task 5 becomes inactive pending Gate C and Task 8 may become the active slice.

### Dependency DAG adjacency list

This table—not section number—is the executable dependency source. It is acyclic and names only direct prerequisites.

| Reviewer Slice / checkpoint | Direct prerequisite(s) | Unblocks |
|---|---|---|
| Task 1 | Frozen specs | every branch |
| Task 7 | Task 1 | Task 8 |
| Gate D1 | Task 1 | Task 8 |
| Task 8 | Task 7, Gate D1 | Task 9 |
| Task 9 | Task 8 | Task 10 |
| Gate B (Task 3) | Task 1 | Gate C |
| Gate C (Task 4) | Gate B | Task 10, Gate D2 |
| Gate D2 | Gate D1, Gate C | Task 11 |
| Gate A (Task 2) | Task 1 | Task 11 |
| Task 10 | Task 9, Gate B, Gate C | Task 11 |
| Task 11 | Gate A, Gate D2, Task 10 | Task 13 |
| Task 13 | Task 11 | Task 12 |
| Task 12 | Task 13 | Task 14 |
| Task 14 | Task 12 | Task 15 |
| Task 15 | Task 14 | Task 16 |
| Gate E (Task 6) | Task 1 | Task 16 and external release only |
| Task 16 | Task 15, Gate E decision | Task 17 |
| Task 17 | Task 16, all required Gate decisions | release review |

## Vertical Slice Capability Delta

| Slice | Domain delta | Runtime delta | Adapter delta | Persistence delta | User surface |
|---|---|---|---|---|---|
| Task 7 Conversation | Agent, Persona, Conversation, Message, Draft | application use cases only; no Run | Room repositories + debug responder | schema 1 facts/projections | Home, Agents, Agent Detail, switchable Conversation shell |
| Task 8 Provider | ProviderProfile/Capabilities references | invocation ownership, normalized stream accumulation | OpenAI-compatible, Anthropic, custom endpoint, Provider-scoped Vault use | schema 2 provider metadata/binding | Settings, probe/model binding, streaming Message |
| Task 9 Fake Runtime | Task, Run, Snapshot, RuntimeInput/Fact | single-writer Coordinator, reducer, one-active slot, Composer routing | deterministic fake Provider/executor | schema 3 ledger/checkpoints/projections | Task Card, Run Detail, Plan, fake ToolCall, Activity |
| Task 10 Fake authority | Envelope, frozen target, permission/safety facts, immutable Verification Plan/criterion definitions | proposal normalization, pre-mutation plan persistence/resolution, Safe Read, authority and T6 fake path | byte renderer + fake authorized transport | schema 4 Envelope/decision/Fence + authoritative plan version/criterion definitions | Permission Card and trusted Envelope + Success Criteria Review page |
| Task 11 Real READ | SSHHostRevision, Workspace layers, RawObservationRef | real Safe Read execution and delivery facts | selected SSH + Vault + encrypted spool | schema 5 Host/Workspace/raw blob metadata | onboarding, workspace sheet, live/full local output |
| Task 13 Egress | sensitivity/disposition/safe-ref facts | local guard, scoped egress decisions, safe context continuation | scanner/parser/transformer and isolated safe store | schema 6 safe observation/egress metadata | Egress Decision Card and local-secret state |
| Task 12 MODIFY | versioned risk/critical-rule facts; consumes Schema-4 plan binding, no new top-level entity | one-time approved mutation, plan revalidation/crash recovery, cancel/pre-send invalidation; ToolCall SUCCEEDED leaves Run PAUSED / Task OPEN | real MODIFY path on disposable SSH host | no new schema; recovery proves original plan/REQUIRED criteria survive mutation + process death | high-risk review and factual execution state |
| Task 14 Verification | Check/Evidence/EvaluatorResult/Verdict referencing existing Criterion/Plan | evaluator registry, weakening detector, CompletionGate against original bound plan | deterministic evaluators | schema 7 execution/evidence/verdict facts only; foreign keys to schema 4 definitions | Verification rows, evidence detail, RUN_RESULT |
| Task 15 Reconciliation | obligation/conflict/reconciliation facts | cross-run mutation preflight and read-only reconciliation | delivery reducer + controlled fault hooks | schema 8 obligation index/facts | Needs Attention, recovery Activity and CTAs |
| Task 16 Android recovery | no new product entity | owner-epoch bootstrap and explicit resume | FGS candidate or foreground-only RunHost | no new schema; recovery validates 1…8 | notification, pause/re-entry, lifecycle feedback |
| Task 17 Release | none | no new behavior | hardened release composition only | all migration paths and retention proof | two unified-Agent golden paths and release evidence |

Gates A–E are feasibility/security slices rather than product-capability slices: they may create spike adapters, pure authority types, test stores and evidence, but they do not expose unfinished production features.

## Acceptance Coverage Matrix

| Upstream acceptance | Primary implementation evidence |
|---|---|
| `B36.1` Agent-first Domain | Tasks 7–9, 17 |
| `B36.2` Conversation | Tasks 7–9, 17 |
| `B36.3` Task and Run | Tasks 9, 11–16 |
| `B36.4` Workspace | Gates B/C, Tasks 9–12 |
| `B36.5` Provider and Host | Task 8 + Gate D1; Task 11 + Gates A/B/D2 |
| `B36.6` Permission and Safety | Gates B/C, Tasks 10, 12–14 |
| `B36.7` Observation and Egress | Task 13 |
| `B36.8` Execution and Verification | Task 10 definition persistence; Tasks 11–15 execution/recovery |
| `B36.9` Recovery | Gates C–E, Tasks 15–16 |
| `B36.10` Failure Tests | every Gate, Tasks 9–17 |
| `UX34.1` Agent-first IA | Tasks 7, 17 |
| `UX34.2` Unified Conversation | Tasks 7, 9, 17 |
| `UX34.3` Multiple Conversations | Task 7, 17 |
| `UX34.4` Home/Agents/Detail | Tasks 7–8, 17 |
| `UX34.5` Shell/Timeline | Tasks 7, 9, 17 |
| `UX34.6` Task Card/Run Detail | Tasks 9–15 |
| `UX34.7` Active Run/Composer | Tasks 9, 15–17 |
| `UX34.8` Workspace Context | Gates B/C, Tasks 9–11 |
| `UX34.9` Permission Review | Task 10, 12–13 |
| `UX34.10` Observation/Egress | Tasks 11, 13 |
| `UX34.11` Verification/RUN_RESULT | Task 14 |
| `UX34.12` Needs Attention/Activity | Tasks 9, 13, 15–16 |
| `UX34.13` Recovery/failure | Tasks 8–16 |
| `UX34.14` Accessibility/privacy | every UI slice; release proof in Task 17 |
| `UX35` validation scenarios | slice UI suites; full replay in Task 17 |
| `SA32.1` Modules/dependencies | Task 1 and permanent architecture tests |
| `SA32.2` Run ownership/concurrency | Gate C, Tasks 9, 15–16 |
| `SA32.3` Execution chain | Gates A/B/C/D2, Tasks 10–12 |
| `SA32.4` Persistence/recovery | Tasks 9–16 and all migrations |
| `SA32.5` Projection/UX sources | Tasks 7, 9–16 |
| `SA32.6` Provider | Tasks 8, 13 |
| `SA32.7` Observation/Egress | Tasks 13–14 |
| `SA32.8` Permission/byte rendering | Gates B/C, Task 10 |
| `SA32.9` SSH | Gate A, Tasks 11–12, 15 |
| `SA32.10` Credential | Gate D1 + Task 8; Gate D2 + Task 11 |
| `SA32.11` Background | Gate E, Task 16 |
| `SA32.12` Verification | Task 10 plan/criterion persistence, Task 12 authority/recovery, Task 14 execution/completion |
| `SA32.13` Required test evidence | every task Reviewer Gate; consolidated Task 17 |

## Targeted Consistency Pass — 2026-08-30

This is a plan-level static consistency result, not an Implementation Plan approval and not implementation/test evidence.

| Review check | Result | Evidence in this plan |
|---|---|---|
| 1. Dependency DAG is acyclic | PASS | One valid topological witness is `T1 → T7 → D1 → T8 → T9 → T3 → T4 → T10 → D2 → T2 → T11 → T13 → T12 → T14 → T15 → T6 → T16 → T17`; section numbers are not schedule edges. |
| 2. Tasks 7/8/9 demo without SSH Gates | PASS | T7 depends only T1; D1 depends only T1; T8 depends T7+D1; T9 depends only T8. No A/B/C/D2/E edge exists. |
| 3. Task 10 waits for Gate B/C | PASS | Direct prerequisites are T9 + Gate B + Gate C. |
| 4. Task 11 waits for Gate A + D2 | PASS | Direct prerequisites are Gate A + Gate D2 + Task 10; Task 10 already requires B/C. |
| 5. Real MODIFY waits for Provider-safe observation | PASS | Runtime path is T11 READ → T13 Egress → T12 MODIFY; T11 itself pauses with local raw output. |
| 6. Composer destinations match Frozen System Architecture | PASS | All three destination field sets are identical; guidance/question commands carry the complete value object and prohibit current-page/current-Run inference. |
| 7. Preparation/execution capabilities are split | PASS | Registry, module rule, Gate C fakes/tests, Gate-A promotion rule and Task-11 production bindings use three separate Ports. |
| 8. Task/Run vocabulary is legal | PASS | Plan uses only Approved lifecycle names; pre-verification mutation is ToolCall `SUCCEEDED`, Run `PAUSED`, Task `OPEN`; completion is Run `COMPLETED`, Task `RESOLVED`. No new success state exists. |
| 9. Reviewer vs coding granularity | PASS | 17 Reviewer Slices retain exactly one Reviewer Gate each; 125 numbered Execution Tasks each name a focused RED, exact command/failure, minimal Green, verification and commit. T10 has 10, T11 has 10, and T12 has 8 Execution Tasks. |
| 10. Acceptance matrix remains complete | PASS | `B36.1…B36.10`, `UX34.1…UX34.14` + `UX35`, and `SA32.1…SA32.13` all retain primary evidence rows. |
| 11. Verification definition precedes mutation (IP-R5) | PASS | Task 10 / Schema 4 atomically persists immutable plan P + all criterion definitions before PermissionRequest; Permission and every T6 gate resolve exact ID/version/digest. Task 12 crash-recovers P after mutation; Task 14 / Schema 7 adds only check/evidence/result/verdict/completion facts. Missing/corrupt/unsupported/incomplete P sends zero mutation bytes and is never model-regenerated. |
| Type/signature introduction order | PASS | T1 owns generic IDs/bytes/credential ref; D1 owns Provider credential authority; Gate B owns execution IDs/envelope; T7 owns normal destination; T9 owns execution-free lifecycle and Run destinations; T10 owns immutable Verification definitions/binding/resolver; Tasks 12/14 consume them rather than redefine them. |
| Scope/frozen-source check | PASS | Only this Implementation Plan changed; no Marketplace, Memory, Character feature, new Runtime mode or upstream semantic was added. |

## Implementation Plan Review Gate

Implementation must not start until review resolves all of the following:

- [x] Vertical slices and dependency order are accepted; no layer-first milestone remains.
- [x] Gate A–E criteria are strong enough to produce an unambiguous GO/NO-GO/fallback decision.
- [x] Stable interfaces and module paths agree with the Approved System Architecture.
- [x] Every Baseline §36, UX §34/§35 and System Architecture §32 acceptance area maps to executable evidence.
- [x] TDD order, fake Provider/SSH infrastructure, Android instrumentation and process-death matrix are sufficient.
- [x] Migration sequence preserves immutable historical facts, authority and unresolved obligations.
- [x] Every mutating Permission/T6 path proves the exact complete Verification Plan was durably persisted before authority; post-mutation recovery never regenerates or weakens its REQUIRED criteria.
- [x] Security review can detect exact-byte, target, safety-floor, egress, Credential, verification and replay violations.
- [x] Golden path proves the product is Agent-first: Persona-led conversation and Server execution share one framework without two Runtime modes.
- [x] Current Android/Play assumptions are treated as recheckable release facts, not permanent architecture promises.
- [x] No upstream Approved/Frozen semantic was weakened or silently redefined.

Implementation proceeds with `superpowers:subagent-driven-development` for same-session execution or `superpowers:executing-plans` for a separate execution session. Execute one numbered Execution Task at a time and stop at every Reviewer Gate; any required deviation follows Global Constraint 1 rather than silently changing this frozen plan.
