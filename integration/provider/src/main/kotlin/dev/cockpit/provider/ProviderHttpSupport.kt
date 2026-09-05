package dev.cockpit.provider

import dev.cockpit.provider.api.ProviderAuthorizationHandle
import dev.cockpit.provider.api.ProviderAuthorizationSink
import dev.cockpit.provider.api.ProviderError
import dev.cockpit.provider.api.ProviderErrorCode
import dev.cockpit.provider.api.ProviderEndpointResolver
import dev.cockpit.provider.api.ProviderProfile
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

internal const val MAX_MODEL_LIST_BODY_BYTES = 1_048_576L

internal const val PROVIDER_ADAPTER_VERSION = "1.0"

internal fun ProviderProfile.endpoint(path: String): HttpUrl =
    ProviderEndpointResolver.endpoint(baseUrl, path).toHttpUrlOrNull()
        ?: error("Validated provider URL could not be resolved")

internal fun Request.Builder.authorizeWith(handle: ProviderAuthorizationHandle): Boolean {
    var headerApplied = false
    val consumed = handle.authorize(
        ProviderAuthorizationSink { name, value ->
            try {
                header(name, String(value))
                headerApplied = true
            } finally {
                value.fill('\u0000')
            }
        },
    )
    return consumed && headerApplied
}

internal fun Response.readBoundedBody(maxBytes: Long): String? {
    require(maxBytes > 0L)
    val preview = peekBody(maxBytes + 1L)
    return preview.takeIf { it.contentLength() <= maxBytes }?.string()
}

internal fun Response.errorBodySnippet(): String = peekBody(MAX_ERROR_BODY_BYTES).string()

internal fun httpError(status: Int, responseBody: String? = null): ProviderError {
    val normalized = responseBody.orEmpty().lowercase()
    val code = when {
        status == 401 -> ProviderErrorCode.AUTH
        status == 403 -> ProviderErrorCode.PERMISSION
        status == 408 -> ProviderErrorCode.TIMEOUT
        status == 413 || "context_length" in normalized || "context window" in normalized ->
            ProviderErrorCode.CONTEXT_LIMIT
        status == 429 && listOf("quota", "billing", "insufficient_quota", "balance")
            .any { it in normalized } -> ProviderErrorCode.QUOTA
        status == 429 -> ProviderErrorCode.RATE_LIMIT
        status == 404 && "model" in normalized -> ProviderErrorCode.MODEL_UNAVAILABLE
        status == 404 || status == 405 -> ProviderErrorCode.ENDPOINT
        status in 400..499 && listOf("unsupported_parameter", "unknown parameter", "not supported")
            .any { it in normalized } -> ProviderErrorCode.PARAMETER_UNSUPPORTED
        status in 500..599 -> ProviderErrorCode.PROVIDER_UNAVAILABLE
        status in 400..499 -> ProviderErrorCode.INVALID_REQUEST
        else -> ProviderErrorCode.UNKNOWN_PROVIDER_ERROR
    }
    return ProviderError(
        code = code,
        safeMessage = when (code) {
            ProviderErrorCode.AUTH -> "The provider rejected this API key."
            ProviderErrorCode.PERMISSION -> "The credential lacks permission for this endpoint or model."
            ProviderErrorCode.RATE_LIMIT -> "The provider rate limit was reached."
            ProviderErrorCode.QUOTA -> "The provider reported insufficient quota or balance."
            ProviderErrorCode.CONTEXT_LIMIT -> "This conversation is larger than the model context limit."
            ProviderErrorCode.TIMEOUT -> "The provider request timed out."
            ProviderErrorCode.PROVIDER_UNAVAILABLE -> "The provider is temporarily unavailable."
            ProviderErrorCode.ENDPOINT -> "The endpoint or selected protocol was not found."
            ProviderErrorCode.MODEL_UNAVAILABLE -> "The exact model ID is unavailable or not permitted."
            ProviderErrorCode.PARAMETER_UNSUPPORTED -> "The provider rejected a parameter for this model or protocol."
            ProviderErrorCode.INVALID_REQUEST -> "The provider rejected this request or model configuration."
            else -> "The provider returned an unexpected response."
        },
        retryable = code in setOf(
            ProviderErrorCode.RATE_LIMIT,
            ProviderErrorCode.TIMEOUT,
            ProviderErrorCode.PROVIDER_UNAVAILABLE,
        ),
        httpStatus = status,
    )
}

internal fun transportError(error: Throwable, cancelled: Boolean): ProviderError = when {
    cancelled -> ProviderError(
        ProviderErrorCode.CANCELLED,
        "The response was stopped.",
        retryable = true,
    )
    error is SSLException -> ProviderError(
        ProviderErrorCode.TLS_FAILURE,
        "A secure TLS connection to the provider could not be established.",
        retryable = false,
    )
    error is SocketTimeoutException -> ProviderError(
        ProviderErrorCode.TIMEOUT,
        "The provider request timed out.",
        retryable = true,
    )
    error is IOException -> ProviderError(
        ProviderErrorCode.TRANSIENT_NETWORK,
        "The provider could not be reached. Check the network and endpoint.",
        retryable = true,
    )
    else -> ProviderError(
        ProviderErrorCode.UNKNOWN_PROVIDER_ERROR,
        "The provider request failed safely.",
        retryable = false,
    )
}

private const val MAX_ERROR_BODY_BYTES = 8_192L
