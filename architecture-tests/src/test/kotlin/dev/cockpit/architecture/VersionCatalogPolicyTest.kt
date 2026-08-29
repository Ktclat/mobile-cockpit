package dev.cockpit.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

        val catalogText = Files.readString(catalog)
        val versions = versionDeclarations(catalogText)
        assertTrue(versions.isNotEmpty(), "The catalog must declare pinned versions.")
        versions.forEach { (name, version) ->
            assertFalse(
                version.contains(dynamicOrRangeVersion),
                "Version '$name' must be a fixed version, but was '$version'.",
            )
        }

        val aliases = explicitlyVersionedAliases(catalogText, versions)
        assertTrue(aliases.isNotEmpty(), "The catalog must contain explicitly versioned plugin or library aliases.")
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
        val wrapperText = Files.readString(wrapperProperties)
        assertTrue(
            wrapperText.lineSequence().any {
                it == "distributionSha256Sum=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
            },
            "The Gradle wrapper must pin Gradle 9.5.0's official SHA-256 distribution checksum.",
        )
        assertTrue(
            wrapperText.lineSequence().any { it.contains("gradle-9.5.0-bin.zip") },
            "The Gradle wrapper must use the pinned Gradle 9.5.0 binary distribution.",
        )
        assertTrue(
            sha256(wrapperJar) == "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
            "The Gradle wrapper JAR must match the official Gradle 9.5.0 SHA-256 checksum.",
        )
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve(".git").exists() }

    private fun versionDeclarations(catalog: String): Map<String, String> =
        section(catalog, "versions")
            .mapNotNull { line ->
                Regex("^([\\w.-]+)\\s*=\\s*\\\"([^\\\"]+)\\\"\\s*$").matchEntire(line.trim())
                    ?.destructured
                    ?.let { (name, version) -> name to version }
            }
            .toMap()

    private fun explicitlyVersionedAliases(catalog: String, versions: Map<String, String>): List<Alias> =
        listOf("libraries" to "library", "plugins" to "plugin").flatMap { (section, kind) ->
            this.section(catalog, section).mapNotNull { line ->
                val declaration = Regex("^([\\w.-]+)\\s*=\\s*\\{(.+)}\\s*$").matchEntire(line.trim())
                    ?: return@mapNotNull null
                val (name, attributes) = declaration.destructured
                val module = Regex("module\\s*=\\s*\\\"([^\\\"]+)\\\"").find(attributes)?.groupValues?.get(1)
                val pluginId = Regex("id\\s*=\\s*\\\"([^\\\"]+)\\\"").find(attributes)?.groupValues?.get(1)
                val version = Regex("version\\.ref\\s*=\\s*\\\"([^\\\"]+)\\\"").find(attributes)
                    ?.groupValues
                    ?.get(1)
                    ?.let(versions::get)
                    ?: Regex("version\\s*=\\s*\\\"([^\\\"]+)\\\"").find(attributes)?.groupValues?.get(1)
                val component = module ?: pluginId?.let { "$it:$it.gradle.plugin" }
                if (component != null && version != null) Alias(name, kind, component, version) else null
            }
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

    private fun section(catalog: String, name: String): List<String> =
        catalog.lineSequence()
            .dropWhile { it.trim() != "[$name]" }
            .drop(1)
            .takeWhile { !it.trim().startsWith("[") }
            .toList()

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private data class Alias(val name: String, val kind: String, val component: String, val version: String) {
        val coordinate = "$component:$version"
    }

    private companion object {
        val dynamicOrRangeVersion = Regex("[+*]|\\blatest\\.|[\\[\\]()]")
    }
}
