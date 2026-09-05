package dev.cockpit.provider

import dev.cockpit.provider.api.ProviderAdapter
import dev.cockpit.provider.api.ProviderAdapterResolver
import dev.cockpit.provider.api.ProviderKind
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class ProviderAdapterRegistry(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
) : ProviderAdapterResolver {
    private val adapters: Map<ProviderKind, ProviderAdapter> = mapOf(
        ProviderKind.OPENAI_RESPONSES to OpenAiCompatibleAdapter(ProviderKind.OPENAI_RESPONSES, client),
        ProviderKind.OPENAI_COMPATIBLE to OpenAiCompatibleAdapter(ProviderKind.OPENAI_COMPATIBLE, client),
        ProviderKind.ANTHROPIC to AnthropicAdapter(client),
    )

    override fun resolve(kind: ProviderKind): ProviderAdapter =
        requireNotNull(adapters[kind]) { "No adapter registered for $kind" }
}
