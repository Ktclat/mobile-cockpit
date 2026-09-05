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

class OpenAiCompatibleAdapter(
    override val kind: ProviderKind,
    private val client: OkHttpClient = OkHttpClient(),
) : ProviderAdapter {
    init {
        require(kind == ProviderKind.OPENAI_RESPONSES || kind == ProviderKind.OPENAI_COMPATIBLE)
    }

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
        val builder = Request.Builder().url(profile.endpoint("models")).get()
        applyPublicHeaders(builder, profile)
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
                    val models = (root["data"] as? JsonArray).orEmpty()
                        .mapNotNull { element ->
                            element.asObject()?.string("id")?.takeIf(String::isNotBlank)
                        }
                        .distinct()
                        .map(::DiscoveredProviderModel)
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
        val path = if (kind == ProviderKind.OPENAI_RESPONSES) "responses" else "chat/completions"
        val builder = Request.Builder()
            .url(request.profile.endpoint(path))
            .post(requestBody(request))
            .header("Accept", "text/event-stream")
        applyPublicHeaders(builder, request.profile)
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
                var terminal = false
                for (wire in SseEventParser.parse(response.body.source())) {
                    currentCoroutineContext().ensureActive()
                    val events = if (kind == ProviderKind.OPENAI_RESPONSES) {
                        responsesEvents(request.invocationId, wire, ordinal)
                    } else {
                        chatEvents(request.invocationId, wire, ordinal)
                    }
                    for (event in events) {
                        if (terminal) break
                        emit(event)
                        if (event is ProviderStreamEvent.TextDelta) ordinal++
                        terminal = event is ProviderStreamEvent.Completed ||
                            event is ProviderStreamEvent.Failed
                    }
                    if (terminal) break
                }
                if (!terminal) emit(malformed(request.invocationId, "The provider stream ended early."))
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

    private fun requestBody(request: NormalizedProviderRequest) =
        (if (kind == ProviderKind.OPENAI_RESPONSES) responsesBody(request) else chatBody(request))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

    private fun responsesBody(request: NormalizedProviderRequest) = buildJsonObject {
        put("model", request.profile.model)
        put("stream", true)
        put("max_output_tokens", request.maxOutputTokens)
        if (request.systemInstruction.isNotBlank()) put("instructions", request.systemInstruction)
        put("input", buildJsonArray {
            request.messages.filter { it.role != ProviderMessageRole.SYSTEM }.forEach { message ->
                add(buildJsonObject {
                    put("role", if (message.role == ProviderMessageRole.USER) "user" else "assistant")
                    put("content", message.text)
                })
            }
        })
    }

    private fun chatBody(request: NormalizedProviderRequest) = buildJsonObject {
        put("model", request.profile.model)
        put("stream", true)
        put("max_tokens", request.maxOutputTokens)
        put("messages", buildJsonArray {
            if (request.systemInstruction.isNotBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", request.systemInstruction)
                })
            }
            request.messages.forEach { message ->
                add(buildJsonObject {
                    put("role", when (message.role) {
                        ProviderMessageRole.SYSTEM -> "system"
                        ProviderMessageRole.USER -> "user"
                        ProviderMessageRole.ASSISTANT -> "assistant"
                    })
                    put("content", message.text)
                })
            }
        })
    }

    private fun responsesEvents(
        invocationId: ProviderInvocationId,
        event: SseEvent,
        ordinal: Long,
    ): List<ProviderStreamEvent> {
        val root = parseObject(event.data)
            ?: return listOf(malformed(invocationId, "The provider sent malformed JSON."))
        return when (event.event ?: root.string("type")) {
            "response.output_text.delta" -> listOfNotNull(
                root.string("delta")?.takeIf(String::isNotEmpty)?.let {
                    ProviderStreamEvent.TextDelta(invocationId, ordinal, it)
                },
            )
            "response.function_call_arguments.delta" -> listOfNotNull(
                root.string("delta")?.let { bytes ->
                    ProviderStreamEvent.ToolProposalDelta(
                        invocationId,
                        root.string("call_id") ?: root.string("item_id") ?: "unknown",
                        ImmutableBytes.copyOf(bytes.toByteArray(Charsets.UTF_8)),
                    )
                },
            )
            "response.completed" -> listOf(
                ProviderStreamEvent.Completed(
                    invocationId,
                    root["response"]?.asObject()?.get("usage")?.asObject()?.toUsage(),
                ),
            )
            "response.failed", "error" -> listOf(providerFailure(invocationId))
            else -> emptyList()
        }
    }

    private fun chatEvents(
        invocationId: ProviderInvocationId,
        event: SseEvent,
        ordinal: Long,
    ): List<ProviderStreamEvent> {
        if (event.data.trim() == "[DONE]") {
            return listOf(ProviderStreamEvent.Completed(invocationId, null))
        }
        val root = parseObject(event.data)
            ?: return listOf(malformed(invocationId, "The provider sent malformed JSON."))
        if (root["error"] != null) return listOf(providerFailure(invocationId))

        val mapped = mutableListOf<ProviderStreamEvent>()
        (root["choices"] as? JsonArray).orEmpty().forEach { choiceElement ->
            val choice = choiceElement.asObject() ?: return@forEach
            val delta = choice["delta"]?.asObject()
            delta?.string("content")?.takeIf(String::isNotEmpty)?.let {
                mapped += ProviderStreamEvent.TextDelta(
                    invocationId,
                    ordinal + mapped.count { item -> item is ProviderStreamEvent.TextDelta },
                    it,
                )
            }
            (delta?.get("tool_calls") as? JsonArray).orEmpty().forEach { toolElement ->
                val tool = toolElement.asObject() ?: return@forEach
                tool["function"]?.asObject()?.string("arguments")?.let { arguments ->
                    mapped += ProviderStreamEvent.ToolProposalDelta(
                        invocationId,
                        tool.string("id") ?: tool["index"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                        ImmutableBytes.copyOf(arguments.toByteArray(Charsets.UTF_8)),
                    )
                }
            }
            if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull != null) {
                mapped += ProviderStreamEvent.Completed(invocationId, root["usage"]?.asObject()?.toUsage())
            }
        }
        return mapped
    }

    private fun parseObject(data: String): JsonObject? = runCatching {
        json.parseToJsonElement(data) as? JsonObject
    }.getOrNull()

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
    private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.toUsage() = ProviderUsage(
        inputTokens = get("input_tokens")?.jsonPrimitive?.longOrNull
            ?: get("prompt_tokens")?.jsonPrimitive?.longOrNull,
        outputTokens = get("output_tokens")?.jsonPrimitive?.longOrNull
            ?: get("completion_tokens")?.jsonPrimitive?.longOrNull,
        totalTokens = get("total_tokens")?.jsonPrimitive?.longOrNull,
    )

    private fun providerFailure(invocationId: ProviderInvocationId) = ProviderStreamEvent.Failed(
        invocationId,
        ProviderError(
            ProviderErrorCode.UNKNOWN_PROVIDER_ERROR,
            "The provider reported that the response failed.",
            retryable = false,
        ),
    )

    private fun malformed(invocationId: ProviderInvocationId, message: String) =
        ProviderStreamEvent.Failed(
            invocationId,
            ProviderError(ProviderErrorCode.MALFORMED_STREAM, message, retryable = true),
        )

    private fun authMissing() = ProviderError(
        ProviderErrorCode.AUTH,
        "The provider credential is unavailable or already consumed.",
        retryable = false,
    )

    private fun applyPublicHeaders(builder: Request.Builder, profile: ProviderProfile) {
        profile.organizationId.takeIf(String::isNotBlank)?.let { builder.header("OpenAI-Organization", it) }
        profile.projectId.takeIf(String::isNotBlank)?.let { builder.header("OpenAI-Project", it) }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
