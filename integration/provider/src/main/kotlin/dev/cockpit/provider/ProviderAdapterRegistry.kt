package dev.cockpit.provider

import dev.cockpit.provider.api.ProviderAdapter
import dev.cockpit.provider.api.ProviderAdapterResolver
import dev.cockpit.provider.api.ProviderKind
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class ProviderAdapterRegistry(
    streamingClient: OkHttpClient = ProviderHttpClientPolicy.streaming(),
    serviceClient: OkHttpClient = ProviderHttpClientPolicy.discoveryAndProbe(),
) : ProviderAdapterResolver {
    private val adapters: Map<ProviderKind, ProviderAdapter> = mapOf(
        ProviderKind.OPENAI_RESPONSES to OpenAiCompatibleAdapter(
            ProviderKind.OPENAI_RESPONSES,
            streamingClient,
            serviceClient,
        ),
        ProviderKind.OPENAI_COMPATIBLE to OpenAiCompatibleAdapter(
            ProviderKind.OPENAI_COMPATIBLE,
            streamingClient,
            serviceClient,
        ),
        ProviderKind.ANTHROPIC to AnthropicAdapter(streamingClient, serviceClient),
    )

    override fun resolve(kind: ProviderKind): ProviderAdapter =
        requireNotNull(adapters[kind]) { "No adapter registered for $kind" }
}

internal object ProviderHttpClientPolicy {
    fun discoveryAndProbe(): OkHttpClient = baseBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun streaming(): OkHttpClient = baseBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun baseBuilder() = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
}
