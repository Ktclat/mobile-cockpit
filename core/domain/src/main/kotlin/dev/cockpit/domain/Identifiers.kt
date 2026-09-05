package dev.cockpit.domain

@JvmInline
value class AgentId(val value: String)

@JvmInline
value class ConversationId(val value: String)

@JvmInline
value class TaskId(val value: String)

@JvmInline
value class RunId(val value: String)

@JvmInline
value class ToolCallId(val value: String)

@JvmInline
value class PermissionAuthorityRef(val value: String)

@JvmInline
value class EgressAuthorityRef(val value: String)

@JvmInline
value class QuestionId(val value: String)

@JvmInline
value class ConversationRevision(val value: Long)

@JvmInline
value class RunVersion(val value: Long)

@JvmInline
value class QuestionVersion(val value: Long)

@JvmInline
value class OneTimeReplyNonce(val value: String)

@JvmInline
value class SafetyEpoch(val value: Long)

@JvmInline
value class VerificationPlanId(val value: String)

@JvmInline
value class VerificationCriterionId(val value: String)
