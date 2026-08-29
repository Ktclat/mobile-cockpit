package dev.cockpit.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.io.path.exists

class VersionCatalogPolicyTest {
    @Test
    fun rejectsDynamicAndUnverifiedDependencies() {
        val root = projectRoot()
        val catalog = root.resolve("gradle/libs.versions.toml")
        val verificationMetadata = root.resolve("gradle/verification-metadata.xml")
        val wrapperProperties = root.resolve("gradle/wrapper/gradle-wrapper.properties")
        val wrapperJar = root.resolve("gradle/wrapper/gradle-wrapper.jar")
        val gradleProperties = root.resolve("gradle.properties")

        assertTrue(catalog.exists(), "A version catalog must declare build dependencies.")
        assertTrue(verificationMetadata.exists(), "Resolved dependencies must have verification metadata.")
        assertTrue(wrapperProperties.exists(), "The Gradle wrapper must pin its distribution checksum.")
        assertTrue(wrapperJar.exists(), "The Gradle wrapper JAR must be present and verified.")
        assertTrue(gradleProperties.exists(), "Gradle must be configured to reject unverified dependencies.")

        val parsedCatalog = parseCatalog(Files.readString(catalog))
        val versions = parsedCatalog.versions
        assertTrue(versions.isNotEmpty(), "The catalog must declare pinned versions.")
        frozenVersions.forEach { (name, expectedVersion) ->
            assertEquals(expectedVersion, versions[name], "Version '$name' must retain its frozen value.")
        }
        versions.forEach { (name, version) ->
            assertFalse(
                version.contains(dynamicOrRangeVersion),
                "Version '$name' must be a fixed version, but was '$version'.",
            )
        }

        val aliases = parsedCatalog.aliases
        assertTrue(aliases.isNotEmpty(), "The catalog must contain explicitly versioned plugin or library aliases.")
        val aliasesByName = aliases.associateBy(Alias::name)
        frozenAliases.forEach { (name, expectedCoordinate) ->
            assertEquals(expectedCoordinate, aliasesByName[name]?.coordinate, "Alias '$name' must retain its frozen component.")
        }
        val verifiedComponents = verifiedComponents(verificationMetadata)
        aliases.forEach { alias ->
            assertFalse(
                alias.version.contains(dynamicOrRangeVersion),
                "${alias.kind} alias '${alias.name}' must use a fixed version, but was '${alias.version}'.",
            )
            assertTrue(
                alias.coordinate in verifiedComponents,
                "${alias.kind} alias '${alias.name}' resolves '${alias.coordinate}' without SHA-256 verification for every artifact.",
            )
        }

        assertTrue(
            Files.readString(gradleProperties).lineSequence().any { it.trim() == "org.gradle.dependency.verification=strict" },
            "Gradle must reject dependencies that are not recorded in verification metadata.",
        )
        val parsedWrapperProperties = Properties().apply {
            Files.newBufferedReader(wrapperProperties).use(::load)
        }
        assertTrue(
            parsedWrapperProperties.getProperty("distributionSha256Sum") ==
                "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746",
            "The Gradle wrapper must pin Gradle 9.5.0's official SHA-256 distribution checksum.",
        )
        assertEquals(
            "https://services.gradle.org/distributions/gradle-9.5.0-bin.zip",
            parsedWrapperProperties.getProperty("distributionUrl"),
            "The Gradle wrapper must use the exact official pinned binary distribution URL.",
        )
        assertTrue(
            sha256(wrapperJar) == "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
            "The Gradle wrapper JAR must match the official Gradle 9.5.0 SHA-256 checksum.",
        )
    }

    @Test
    fun rejectsCatalogDeclarationsItCannotValidate() {
        val catalog = projectRoot().resolve("gradle/libs.versions.toml")

        listOf<(String) -> String>(
            { it.replace("[libraries]\n", "[libraries]\nbypass-string = \"example:artifact:1.+\"\n") },
            {
                it.replace(
                    "[libraries]\n",
                    "[libraries]\nbypass-group-name = { group = \"example\", name = \"artifact\", version = \"1.+\" }\n",
                )
            },
            { "$it\n[libraries.bypass-dotted]\nmodule = \"example:artifact\"\nversion = \"1.+\"\n" },
            { it.replace("[versions]\n", "[versions]\nbypass-rich = { strictly = \"1.0\", prefer = \"1.+\" }\n") },
        ).forEach { mutate ->
            assertPolicyRejects(catalog, mutate)
        }
    }

    @Test
    fun rejectsWrapperUrlThatIsNotTheOfficialPinnedDistribution() {
        val wrapperProperties = projectRoot().resolve("gradle/wrapper/gradle-wrapper.properties")
        assertPolicyRejects(wrapperProperties) {
            it.replace(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.0-bin.zip",
                "distributionUrl=https\\://mirror.example/gradle-9.5.0-bin.zip",
            )
        }
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve(".git").exists() }

    private fun parseCatalog(catalog: String): Catalog {
        val versions = linkedMapOf<String, String>()
        val aliases = mutableListOf<UnresolvedAlias>()
        var section: CatalogSection? = null

        catalog.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (line.startsWith("[")) {
                section = when (line) {
                    "[versions]" -> CatalogSection.VERSIONS
                    "[libraries]" -> CatalogSection.LIBRARIES
                    "[plugins]" -> CatalogSection.PLUGINS
                    else -> throw AssertionError("Unsupported version catalog header at line ${index + 1}: $line")
                }
                return@forEachIndexed
            }

            when (section ?: throw AssertionError("Declaration before a supported header at line ${index + 1}: $line")) {
                CatalogSection.VERSIONS -> {
                    val match = canonicalVersionDeclaration.matchEntire(line)
                        ?: throw AssertionError("Unsupported version declaration at line ${index + 1}: $line")
                    val (name, version) = match.destructured
                    if (versions.put(name, version) != null) {
                        throw AssertionError("Duplicate version declaration '$name'.")
                    }
                }

                CatalogSection.LIBRARIES -> {
                    val match = canonicalLibraryDeclaration.matchEntire(line)
                        ?: throw AssertionError("Unsupported library declaration at line ${index + 1}: $line")
                    val (name, component, versionRef) = match.destructured
                    aliases += UnresolvedAlias(name, "library", component, versionRef)
                }

                CatalogSection.PLUGINS -> {
                    val match = canonicalPluginDeclaration.matchEntire(line)
                        ?: throw AssertionError("Unsupported plugin declaration at line ${index + 1}: $line")
                    val (name, pluginId, versionRef) = match.destructured
                    aliases += UnresolvedAlias(name, "plugin", "$pluginId:$pluginId.gradle.plugin", versionRef)
                }
            }
        }

        return Catalog(
            versions = versions,
            aliases = aliases.map { alias ->
                val version = versions[alias.versionRef]
                    ?: throw AssertionError("${alias.kind} alias '${alias.name}' references undeclared version '${alias.versionRef}'.")
                Alias(alias.name, alias.kind, alias.component, version)
            },
        )
    }

    private fun verifiedComponents(metadata: Path): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(metadata.toFile())
        return document.getElementsByTagName("component").let { nodes ->
            (0 until nodes.length).map { index ->
                val component = nodes.item(index)
                val artifacts = component.childNodes.let { children ->
                    (0 until children.length).mapNotNull { artifactIndex ->
                        children.item(artifactIndex).takeIf { it.nodeName == "artifact" }
                    }
                }
                val checksums = artifacts.isNotEmpty() && artifacts.all { artifact ->
                        artifact.childNodes.let { artifactChildren ->
                            (0 until artifactChildren.length).any { checksumIndex ->
                                artifactChildren.item(checksumIndex).let { checksum ->
                                    checksum.nodeName == "sha256" && checksum.attributes.getNamedItem("value") != null
                                }
                            }
                        }
                }
                component.attributes.let { attributes ->
                    "${attributes.getNamedItem("group").nodeValue}:${attributes.getNamedItem("name").nodeValue}:${attributes.getNamedItem("version").nodeValue}"
                        .takeIf { checksums }
                }
            }.filterNotNull().toSet()
        }
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private fun assertPolicyRejects(file: Path, mutate: (String) -> String) {
        val original = Files.readString(file)
        try {
            Files.writeString(file, mutate(original))
            assertThrows(AssertionError::class.java) { rejectsDynamicAndUnverifiedDependencies() }
        } finally {
            Files.writeString(file, original)
        }
    }

    private data class Catalog(val versions: Map<String, String>, val aliases: List<Alias>)

    private data class UnresolvedAlias(
        val name: String,
        val kind: String,
        val component: String,
        val versionRef: String,
    )

    private data class Alias(val name: String, val kind: String, val component: String, val version: String) {
        val coordinate = "$component:$version"
    }

    private enum class CatalogSection { VERSIONS, LIBRARIES, PLUGINS }

    private companion object {
        val dynamicOrRangeVersion = Regex("[+*]|\\blatest\\.|[\\[\\]()]")
        val frozenVersions = mapOf(
            "agp" to "9.3.0",
            "kotlin" to "2.4.10",
            "composeCompiler" to "2.4.10",
            "minSdk" to "28",
            "compileSdk" to "36",
            "targetSdk" to "36",
            "composeBom" to "2026.06.00",
            "junit" to "5.14.3",
        )
        val frozenAliases = mapOf(
            "android-application" to "com.android.application:com.android.application.gradle.plugin:9.3.0",
            "android-library" to "com.android.library:com.android.library.gradle.plugin:9.3.0",
            "compose-compiler" to "org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.4.10",
            "compose-bom" to "androidx.compose:compose-bom:2026.06.00",
        )
        val canonicalVersionDeclaration = Regex("^([\\w.-]+)\\s*=\\s*\\\"([^\\\"]+)\\\"$")
        val canonicalLibraryDeclaration =
            Regex("^([\\w.-]+)\\s*=\\s*\\{\\s*module\\s*=\\s*\\\"([^\\\"]+)\\\",\\s*version\\.ref\\s*=\\s*\\\"([^\\\"]+)\\\"\\s*}$")
        val canonicalPluginDeclaration =
            Regex("^([\\w.-]+)\\s*=\\s*\\{\\s*id\\s*=\\s*\\\"([^\\\"]+)\\\",\\s*version\\.ref\\s*=\\s*\\\"([^\\\"]+)\\\"\\s*}$")
    }
}
