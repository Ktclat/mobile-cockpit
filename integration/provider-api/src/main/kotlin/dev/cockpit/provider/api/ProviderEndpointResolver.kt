package dev.cockpit.provider.api

import java.net.URI

data class ProviderEndpointResolution(
    val apiPrefix: String,
    val removedGenerationSuffix: String? = null,
)

/** Resolves a suffix without discarding gateway or version paths already present in the prefix. */
object ProviderEndpointResolver {
    private val generationSuffixes = listOf(
        "/chat/completions",
        "/responses",
        "/messages",
    )

    fun normalizePrefix(raw: String): ProviderEndpointResolution {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid API address")
        require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPS is required" }
        require(!uri.host.isNullOrBlank()) { "A host is required" }
        require(uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null) {
            "Credentials, fragments, and query parameters are not allowed in the API address"
        }

        var normalized = trimmed.trimEnd('/')
        val removed = generationSuffixes.firstOrNull { suffix ->
            normalized.endsWith(suffix, ignoreCase = true)
        }
        if (removed != null) normalized = normalized.dropLast(removed.length).trimEnd('/')
        require(normalized.isNotBlank()) { "An API prefix is required" }
        return ProviderEndpointResolution(normalized, removed)
    }

    fun endpoint(prefix: String, suffix: String): String {
        val normalized = normalizePrefix(prefix).apiPrefix
        val cleanSuffix = suffix.trim().trimStart('/')
        require(cleanSuffix.isNotBlank()) { "An endpoint suffix is required" }
        return "$normalized/$cleanSuffix"
    }
}
