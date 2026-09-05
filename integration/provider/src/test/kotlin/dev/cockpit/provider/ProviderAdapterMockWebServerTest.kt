package dev.cockpit.provider

import dev.cockpit.domain.credential.CredentialReference
import dev.cockpit.domain.prompt.PromptMessage
import dev.cockpit.domain.prompt.PromptMessageRole
import dev.cockpit.domain.prompt.PromptPlan
import dev.cockpit.provider.api.NormalizedProviderRequest
import dev.cockpit.provider.api.ProviderAuthorizationHandle
import dev.cockpit.provider.api.ProviderAuthorizationSink
import dev.cockpit.provider.api.ProviderErrorCode
import dev.cockpit.provider.api.ProviderInvocationId
import dev.cockpit.provider.api.ProviderKind
import dev.cockpit.provider.api.ProviderMessage
import dev.cockpit.provider.api.ProviderMessageRole
import dev.cockpit.provider.api.ProviderModelDiscoveryResult
import dev.cockpit.provider.api.ProviderProfile
import dev.cockpit.provider.api.ProviderProfileId
import dev.cockpit.provider.api.ProviderStreamEvent
import dev.cockpit.provider.api.PromptPlacementSupport
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProviderAdapterMockWebServerTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun startServer() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        server = MockWebServer().also {
            it.protocols = listOf(Protocol.HTTP_1_1)
            it.useHttps(serverCertificates.sslSocketFactory())
            it.start()
        }
        client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    @AfterEach
    fun stopServer() {
        client.dispatcher.cancelAll()
        server.close()
    }

    @Test
    fun responsesRendersStructuredPromptStreamsAndPreservesEndpointPrefix() = runBlocking {
        server.enqueue(sseResponse(
            "event: response.output_text.delta\n" +
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n" +
                "event: response.completed\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":1,\"total_tokens\":4}}}\n\n",
        ))
        val adapter = OpenAiCompatibleAdapter(ProviderKind.OPENAI_RESPONSES, client)

        val events = adapter.startInvocation(request(ProviderKind.OPENAI_RESPONSES), authorization()).toList()
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val body = json.parseToJsonElement(requireNotNull(recorded.body).utf8()).jsonObject

        assertEquals("/gateway/v1/responses", recorded.url.encodedPath)
        assertEquals("Bearer secret", recorded.headers["Authorization"])
        assertEquals("leading system", body["instructions"]?.jsonPrimitive?.content)
        assertMessageOrder(
            body["input"]!!.jsonArray,
            listOf("user", "assistant", "user", "system"),
            listOf("example question", "example answer", "real history", "after history"),
        )
        assertEquals("hello", (events[0] as ProviderStreamEvent.TextDelta).text)
        val completed = events[1] as ProviderStreamEvent.Completed
        assertEquals(4L, completed.usage?.totalTokens)
    }

    @Test
    fun chatCompletionsRendersStructuredPromptAndStreams() = runBlocking {
        server.enqueue(sseResponse(
            "data: {\"choices\":[{\"delta\":{\"content\":\"chat\"},\"finish_reason\":null}]}\n\n" +
                "data: [DONE]\n\n",
        ))
        val adapter = OpenAiCompatibleAdapter(ProviderKind.OPENAI_COMPATIBLE, client)

        val events = adapter.startInvocation(request(ProviderKind.OPENAI_COMPATIBLE), authorization()).toList()
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val body = json.parseToJsonElement(requireNotNull(recorded.body).utf8()).jsonObject

        assertEquals("/gateway/v1/chat/completions", recorded.url.encodedPath)
        assertMessageOrder(
            body["messages"]!!.jsonArray,
            listOf("system", "user", "assistant", "user", "system"),
            listOf("leading system", "example question", "example answer", "real history", "after history"),
        )
        assertEquals("chat", (events[0] as ProviderStreamEvent.TextDelta).text)
        assertInstanceOf(ProviderStreamEvent.Completed::class.java, events[1])
        Unit
    }

    @Test
    fun anthropicRendersPostHistoryAfterMessagesAndStreams() = runBlocking {
        server.enqueue(sseResponse(
            "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":7}}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"anthropic\"}}\n\n" +
                "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":2}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n",
        ))
        val adapter = AnthropicAdapter(client)

        val events = adapter.startInvocation(
            request(ProviderKind.ANTHROPIC),
            authorization("x-api-key"),
        ).toList()
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val body = json.parseToJsonElement(requireNotNull(recorded.body).utf8()).jsonObject
        val messages = body["messages"]!!.jsonArray

        assertEquals("/gateway/v1/messages", recorded.url.encodedPath)
        assertEquals("secret", recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        assertEquals("leading system", body["system"]?.jsonPrimitive?.content)
        assertMessageOrder(
            messages,
            listOf("user", "assistant", "user"),
            listOf(
                "example question",
                "example answer",
                "real history\n\n<post_history_instruction>\nafter history\n</post_history_instruction>",
            ),
        )
        assertEquals(
            PromptPlacementSupport.DEGRADED_TO_USER,
            adapter.promptCapabilities.postHistorySystemPlacement,
        )
        assertEquals("anthropic", (events[0] as ProviderStreamEvent.TextDelta).text)
        assertEquals(9L, (events[1] as ProviderStreamEvent.Completed).usage?.totalTokens)
    }

    @Test
    fun httpFailuresMapToStableProviderErrors() = runBlocking {
        val cases = listOf(
            401 to ProviderErrorCode.AUTH,
            403 to ProviderErrorCode.PERMISSION,
            429 to ProviderErrorCode.RATE_LIMIT,
            500 to ProviderErrorCode.PROVIDER_UNAVAILABLE,
            503 to ProviderErrorCode.PROVIDER_UNAVAILABLE,
        )
        val adapter = OpenAiCompatibleAdapter(ProviderKind.OPENAI_COMPATIBLE, client)

        cases.forEach { (status, expected) ->
            server.enqueue(MockResponse.Builder().code(status).body("{}").build())
            val events = adapter.startInvocation(
                request(ProviderKind.OPENAI_COMPATIBLE, "invocation-$status"),
                authorization(),
            ).toList()
            val failed = events.single() as ProviderStreamEvent.Failed
            assertEquals(expected, failed.error.code)
            assertEquals(status, failed.error.httpStatus)
        }
    }

    @Test
    fun authorizationMaterialNeverAppearsInSafeHttpError() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("{\"error\":\"Bearer secret must not leak\"}")
                .build(),
        )
        val failed = OpenAiCompatibleAdapter(ProviderKind.OPENAI_COMPATIBLE, client)
            .startInvocation(request(ProviderKind.OPENAI_COMPATIBLE), authorization())
            .toList()
            .single() as ProviderStreamEvent.Failed

        assertEquals(ProviderErrorCode.AUTH, failed.error.code)
        assertFalse(failed.error.safeMessage.contains("secret", ignoreCase = true))
        assertFalse(failed.error.safeMessage.contains("Bearer", ignoreCase = true))
    }

    @Test
    fun anthropicAddsPostHistoryUserBlockAfterAssistantHistory() = runBlocking {
        server.enqueue(sseResponse("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"))
        val request = request(ProviderKind.ANTHROPIC).copy(
            messages = listOf(ProviderMessage(ProviderMessageRole.ASSISTANT, "last assistant")),
        )

        AnthropicAdapter(client).startInvocation(request, authorization("x-api-key")).toList()
        val recorded = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val messages = json.parseToJsonElement(requireNotNull(recorded.body).utf8())
            .jsonObject["messages"]!!.jsonArray

        assertMessageOrder(
            messages,
            listOf("user", "assistant", "assistant", "user"),
            listOf(
                "example question",
                "example answer",
                "last assistant",
                "<post_history_instruction>\nafter history\n</post_history_instruction>",
            ),
        )
    }

    @Test
    fun streamingAndDiscoveryClientsUseSeparateBoundedPolicies() {
        val streaming = ProviderHttpClientPolicy.streaming()
        val discovery = ProviderHttpClientPolicy.discoveryAndProbe()

        assertEquals(20_000, streaming.connectTimeoutMillis)
        assertEquals(300_000, streaming.readTimeoutMillis)
        assertEquals(0, streaming.callTimeoutMillis)
        assertFalse(streaming.retryOnConnectionFailure)
        assertFalse(streaming.followRedirects)
        assertFalse(streaming.followSslRedirects)
        assertEquals(20_000, discovery.connectTimeoutMillis)
        assertEquals(60_000, discovery.readTimeoutMillis)
        assertEquals(90_000, discovery.callTimeoutMillis)
    }

    @Test
    fun unknownEventsAreIgnoredUntilKnownTerminalAndOversizedEventsFailSafely() = runBlocking {
        val adapter = OpenAiCompatibleAdapter(ProviderKind.OPENAI_COMPATIBLE, client)
        server.enqueue(
            sseResponse(
                "event: vendor.keepalive\ndata: {\"type\":\"vendor.keepalive\"}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val events = adapter.startInvocation(
            request(ProviderKind.OPENAI_COMPATIBLE, "unknown-event"),
            authorization(),
        ).toList()
        assertEquals("ok", (events.first() as ProviderStreamEvent.TextDelta).text)
        assertInstanceOf(ProviderStreamEvent.Completed::class.java, events.last())

        server.enqueue(sseResponse("data: ${"x".repeat(262_145)}\n\n"))
        val oversized = adapter.startInvocation(
            request(ProviderKind.OPENAI_COMPATIBLE, "oversized-event"),
            authorization(),
        ).toList().single() as ProviderStreamEvent.Failed
        assertEquals(ProviderErrorCode.MALFORMED_STREAM, oversized.error.code)
    }

    @Test
    fun modelDiscoveryHandlesBothProtocolsAndMalformedPayload() = runBlocking {
        server.enqueue(MockResponse.Builder().body("{\"data\":[{\"id\":\"gpt-test\"}]}").build())
        server.enqueue(MockResponse.Builder().body("{\"data\":[{\"id\":\"claude-test\",\"display_name\":\"Claude Test\"}]}").build())
        server.enqueue(MockResponse.Builder().body("not-json").build())
        val openAi = OpenAiCompatibleAdapter(ProviderKind.OPENAI_RESPONSES, client)
        val anthropic = AnthropicAdapter(client)

        val openAiModels = openAi.discoverModels(profile(ProviderKind.OPENAI_RESPONSES), authorization())
            as ProviderModelDiscoveryResult.Available
        val anthropicModels = anthropic.discoverModels(
            profile(ProviderKind.ANTHROPIC),
            authorization("x-api-key"),
        ) as ProviderModelDiscoveryResult.Available
        val malformed = openAi.discoverModels(profile(ProviderKind.OPENAI_RESPONSES), authorization())
            as ProviderModelDiscoveryResult.Unavailable

        assertEquals(listOf("gpt-test"), openAiModels.models.map { it.remoteModelId })
        assertEquals("Claude Test", anthropicModels.models.single().displayName)
        assertEquals(ProviderErrorCode.MALFORMED_STREAM, malformed.error.code)
        val openAiRequest = server.takeRequest()
        val anthropicRequest = server.takeRequest()
        val malformedRequest = server.takeRequest()
        assertEquals("/gateway/v1/models", openAiRequest.url.encodedPath)
        assertEquals("/gateway/v1/models", anthropicRequest.url.encodedPath)
        assertEquals("1000", anthropicRequest.url.queryParameter("limit"))
        assertEquals("/gateway/v1/models", malformedRequest.url.encodedPath)
    }

    @Test
    fun malformedStreamFailsAndExplicitCancelStopsCall() = runBlocking {
        val adapter = OpenAiCompatibleAdapter(ProviderKind.OPENAI_COMPATIBLE, client)
        server.enqueue(sseResponse("data: not-json\n\n"))
        val malformed = adapter.startInvocation(
            request(ProviderKind.OPENAI_COMPATIBLE, "malformed"),
            authorization(),
        ).toList().single() as ProviderStreamEvent.Failed
        assertEquals(ProviderErrorCode.MALFORMED_STREAM, malformed.error.code)
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "text/event-stream")
                .body("data: {\"choices\":[]}")
                .onResponseBody(SocketEffect.Stall)
                .build(),
        )
        val invocationId = ProviderInvocationId("cancel")
        val collection = async(Dispatchers.IO) {
            adapter.startInvocation(
                request(ProviderKind.OPENAI_COMPATIBLE, invocationId.value),
                authorization(),
            ).toList()
        }
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        adapter.cancel(invocationId)
        val cancelled = withTimeout(5_000) { collection.await() }.single() as ProviderStreamEvent.Failed
        assertEquals(ProviderErrorCode.CANCELLED, cancelled.error.code)
    }

    private fun request(kind: ProviderKind, id: String = "invocation") = NormalizedProviderRequest(
        invocationId = ProviderInvocationId(id),
        profile = profile(kind),
        promptPlan = PromptPlan(
            systemInstructions = listOf("leading system"),
            fewShotMessages = listOf(
                PromptMessage(PromptMessageRole.USER, "example question"),
                PromptMessage(PromptMessageRole.ASSISTANT, "example answer"),
            ),
            postHistoryInstructions = listOf("after history"),
        ),
        messages = listOf(ProviderMessage(ProviderMessageRole.USER, "real history")),
    )

    private fun profile(kind: ProviderKind) = ProviderProfile(
        id = ProviderProfileId("profile"),
        displayName = "Test provider",
        kind = kind,
        baseUrl = server.url("/gateway/v1").toString().trimEnd('/'),
        model = "test-model",
        credentialReference = CredentialReference("credential"),
        credentialRotation = 1,
        maxOutputTokens = 256,
        revision = 1,
    )

    private fun authorization(header: String = "Authorization") = object : ProviderAuthorizationHandle {
        override val invocationId = ProviderInvocationId("authorization")
        private var used = false

        override fun authorize(sink: ProviderAuthorizationSink): Boolean {
            if (used) return false
            used = true
            sink.setHeader(header, if (header == "Authorization") "Bearer secret".toCharArray() else "secret".toCharArray())
            return true
        }

        override fun close() = Unit
    }

    private fun sseResponse(body: String) = MockResponse.Builder()
        .setHeader("Content-Type", "text/event-stream")
        .body(body)
        .build()

    private fun assertMessageOrder(
        messages: JsonArray,
        expectedRoles: List<String>,
        expectedContent: List<String>,
    ) {
        val objects = messages.map { it as JsonObject }
        assertEquals(expectedRoles, objects.map { it["role"]?.jsonPrimitive?.content })
        assertEquals(expectedContent, objects.map { it["content"]?.jsonPrimitive?.content })
        assertTrue(objects.isNotEmpty())
    }
}
