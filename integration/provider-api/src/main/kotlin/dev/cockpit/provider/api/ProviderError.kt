package dev.cockpit.provider.api

enum class ProviderErrorCode {
    AUTH,
    PERMISSION,
    RATE_LIMIT,
    QUOTA,
    TRANSIENT_NETWORK,
    PROVIDER_UNAVAILABLE,
    ENDPOINT,
    MODEL_UNAVAILABLE,
    PARAMETER_UNSUPPORTED,
    INVALID_REQUEST,
    CONTEXT_LIMIT,
    CAPABILITY_UNSUPPORTED,
    MALFORMED_STREAM,
    MALFORMED_TOOL_PROPOSAL,
    CANCELLED,
    TIMEOUT,
    TLS_FAILURE,
    UNKNOWN_PROVIDER_ERROR,
}

data class ProviderError(
    val code: ProviderErrorCode,
    val safeMessage: String,
    val retryable: Boolean,
    val httpStatus: Int? = null,
)
