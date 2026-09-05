package dev.cockpit.provider

import dev.cockpit.domain.bytes.ImmutableBytes
import dev.cockpit.provider.api.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AnthropicAdapter(
    private val client: OkHttpClient = OkHttpClient(),
) : ProviderAdapter {
    override val kind = ProviderKind.ANTHROPIC
    private val json = Json { ignoreUnknownKeys = true }
    private val calls = ConcurrentHashMap<ProviderInvocationId, okhttp3.Call>()

    override suspend fun probe(
        profile: ProviderProfile,
        authorization: ProviderAuthorizationHandle,
    ): ProviderProbeResult = when (val result = discoverModels(profile, authorization)) {
        is ProviderModelDiscoveryResult.Available -> ProviderProbeResult.Available(
            ProviderCapabilities(
                ProviderCapabilitySupport.SUPPORTED,
                ProviderCapabilitySupport.UNKNOWN,
                System.currentTimeMillis(),
                PROVIDER_ADAPTER_VERSION,
            ),
        )
        is ProviderModelDiscoveryResult.Unavailable -> ProviderProbeResult.Unavailable(result.error)
        is ProviderModelDiscoveryResult.Unsupported -> ProviderProbeResult.Unavailable(
            ProviderError(
                ProviderErrorCode.CAPABILITY_UNSUPPORTED,
                result.message,
                retryable = false,
            ),
        )
    }

    override suspend fun discoverModels(
        profile: ProviderProfile,
        authorization: ProviderAuthorizationHandle,
    ): ProviderModelDiscoveryResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(profile.endpoint("models").newBuilder().addQueryParameter("limit", "1000").build())
            .get()
            .header("anthropic-version", profile.anthropicVersion.ifBlank { ANTHROPIC_VERSION })
        profile.workspaceId.takeIf(String::isNotBlank)?.let {
            builder.header("anthropic-workspace-id", it)
        }
        if (!builder.authorizeWith(authorization)) {
            return@withContext ProviderModelDiscoveryResult.Unavailable(authMissing())
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val payload = response.readBoundedBody(MAX_MODEL_LIST_BODY_BYTES)
                        ?: return@use ProviderModelDiscoveryResult.Unavailable(
                            ProviderError(
                                ProviderErrorCode.MALFORMED_STREAM,
                                "The model list response was too large.",
                                retryable = false,
                            ),
                        )
                    val root = parseObject(payload)
                        ?: return@use ProviderModelDiscoveryResult.Unavailable(
                            ProviderError(
                                ProviderErrorCode.MALFORMED_STREAM,
                                "The model list response was not valid JSON.",
                                retryable = false,
                            ),
                        )
                    val models = (root["data"] as? JsonArray).orEmpty().mapNotNull { element ->
                        val item = element.asObject() ?: return@mapNotNull null
                        val id = item.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                        DiscoveredProviderModel(id, item.string("display_name") ?: id)
                    }.distinctBy { it.remoteModelId }
                    ProviderModelDiscoveryResult.Available(models)
                } else {
                    ProviderModelDiscoveryResult.Unavailable(
                        httpError(response.code, response.errorBodySnippet()),
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ProviderModelDiscoveryResult.Unavailable(transportError(error, cancelled = false))
        }
    }

    override fun startInvocation(
        request: NormalizedProviderRequest,
        authorization: ProviderAuthorizationHandle,
    ): Flow<ProviderStreamEvent> = flow {
        val builder = Request.Builder()
            .url(request.profile.endpoint("messages"))
            .post(requestBody(request))
            .header("Accept", "text/event-stream")
            .header(
                "anthropic-version",
                request.profile.anthropicVersion.ifBlank { ANTHROPIC_VERSION },
            )
        request.profile.workspaceId.takeIf(String::isNotBlank)?.let {
            builder.header("anthropic-workspace-id", it)
        }
        if (!builder.authorizeWith(authorization)) {
            emit(ProviderStreamEvent.Failed(request.invocationId, authMissing()))
            return@flow
        }

        val call = client.newCall(builder.build())
        calls[request.invocationId] = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(
                        ProviderStreamEvent.Failed(
                            request.invocationId,
                            httpError(response.code, response.errorBodySnippet()),
                        ),
                    )
                    return@use
                }

                var ordinal = 0L
                var inputTokens: Long? = null
                var outputTokens: Long? = null
                var terminal = false
                val toolIds = mutableMapOf<String, String>()
                for (wire in SseEventParser.parse(response.body.source())) {
                    currentCoroutineContext().ensureActive()
                    val root = parseObject(wire.data)
                    if (root == null) {
                        emit(malformed(request.invocationId))
                        terminal = true
                        break
                    }
                    when (wire.event ?: root.string("type")) {
                        "message_start" -> inputTokens = root["message"]?.asObject()
                            ?.get("usage")?.asObject()
                            ?.get("input_tokens")?.jsonPrimitive?.longOrNull

                        "content_block_start" -> {
                            val index = root["index"]?.jsonPrimitive?.contentOrNull
                            val block = root["content_block"]?.asObject()
                            if (index != null && block?.string("type") == "tool_use") {
                                toolIds[index] = block.string("id") ?: "tool-$index"
                            }
                        }

                        "content_block_delta" -> {
                            val delta = root["delta"]?.asObject()
                            when (delta?.string("type")) {
                                "text_delta" -> delta.string("text")
                                    ?.takeIf(String::isNotEmpty)
                                    ?.let {
                                        emit(
                                            ProviderStreamEvent.TextDelta(
                                                request.invocationId,
                                                ordinal++,
                                                it,
                                            ),
                                        )
                                    }

                                "input_json_delta" -> delta.string("partial_json")?.let { partial ->
                                    val index = root["index"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                                    emit(
                                        ProviderStreamEvent.ToolProposalDelta(
                                            request.invocationId,
                                            toolIds[index] ?: "tool-$index",
                                            ImmutableBytes.copyOf(partial.toByteArray(Charsets.UTF_8)),
                                        ),
                                    )
                                }
                            }
                        }

                        "message_delta" -> outputTokens = root["usage"]?.asObject()
                            ?.get("output_tokens")?.jsonPrimitive?.longOrNull

                        "message_stop" -> {
                            val total = if (inputTokens != null || outputTokens != null) {
                                (inputTokens ?: 0L) + (outputTokens ?: 0L)
                            } else null
                            emit(
                                ProviderStreamEvent.Completed(
                                    request.invocationId,
                                    ProviderUsage(inputTokens, outputTokens, total),
                                ),
                            )
                            terminal = true
                        }

                        "error" -> {
                            emit(
                                ProviderStreamEvent.Failed(
                                    request.invocationId,
                                    ProviderError(
                                        ProviderErrorCode.UNKNOWN_PROVIDER_ERROR,
                                        "Anthropic reported that the response failed.",
                                        retryable = false,
                                    ),
                                ),
                            )
                            terminal = true
                        }
                    }
                    if (terminal) break
                }
                if (!terminal) {
                    emit(
                        ProviderStreamEvent.Failed(
                            request.invocationId,
                            ProviderError(
                                ProviderErrorCode.MALFORMED_STREAM,
                                "The Anthropic stream ended before message_stop.",
                                retryable = true,
                            ),
                        ),
                    )
                }
            }
        } catch (error: CancellationException) {
            call.cancel()
            throw error
        } catch (error: SseEventLimitException) {
            emit(malformed(request.invocationId, error.message ?: "The provider sent an oversized streaming event."))
        } catch (error: Throwable) {
            emit(
                ProviderStreamEvent.Failed(
                    request.invocationId,
                    transportError(error, call.isCanceled()),
                ),
            )
        } finally {
            calls.remove(request.invocationId, call)
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel(invocationId: ProviderInvocationId) {
        calls.remove(invocationId)?.cancel()
    }

    private fun requestBody(request: NormalizedProviderRequest) = buildJsonObject {
        put("model", request.profile.model)
        put("stream", true)
        put("max_tokens", request.maxOutputTokens)
        if (request.systemInstruction.isNotBlank()) put("system", request.systemInstruction)
        put("messages", buildJsonArray {
            request.messages.filter { it.role != ProviderMessageRole.SYSTEM }.forEach { message ->
                add(buildJsonObject {
                    put("role", if (message.role == ProviderMessageRole.USER) "user" else "assistant")
                    put("content", message.text)
                })
            }
        })
    }.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun parseObject(data: String): JsonObject? = runCatching {
        json.parseToJsonElement(data) as? JsonObject
    }.getOrNull()

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
    private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

    private fun malformed(
        invocationId: ProviderInvocationId,
        message: String = "Anthropic sent a malformed streaming event.",
    ) = ProviderStreamEvent.Failed(
        invocationId,
        ProviderError(
            ProviderErrorCode.MALFORMED_STREAM,
            message,
            retryable = true,
        ),
    )

    private fun authMissing() = ProviderError(
        ProviderErrorCode.AUTH,
        "The Anthropic credential is unavailable or already consumed.",
        retryable = false,
    )

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
