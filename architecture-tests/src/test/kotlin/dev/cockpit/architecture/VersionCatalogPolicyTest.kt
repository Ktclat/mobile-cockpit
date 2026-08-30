package dev.cockpit.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertArrayEquals
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

        validateCatalogPolicy(
            catalog = Files.readString(catalog),
            verifiedComponents = verifiedComponents(verificationMetadata),
        )
        validateGradlePropertiesPolicy(Files.readString(gradleProperties))
        validateWrapperProperties(loadProperties(Files.readString(wrapperProperties)))
        assertTrue(
            sha256(wrapperJar) == "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7",
            "The Gradle wrapper JAR must match the official Gradle 9.5.0 SHA-256 checksum.",
        )
    }

    @Test
    fun requiresVerifiedAapt2BinariesForWindowsAndLinuxCi() {
        val artifacts = verifiedArtifactChecksums(
            metadata = projectRoot().resolve("gradle/verification-metadata.xml"),
            group = "com.android.tools.build",
            name = "aapt2",
            version = "9.3.0-15703166",
        )

        assertEquals(
            setOf("b1006ecec7e5936257e95e97f3eba7ef439d3e44178967cc048f86c9119fb231"),
            artifacts["aapt2-9.3.0-15703166-windows.jar"],
            "The frozen Windows AAPT2 binary must retain its verified checksum.",
        )
        assertEquals(
            setOf("e772a3dae8354764f1b0793903218427f483982445207f2e4ffc8c2026755bd4"),
            artifacts["aapt2-9.3.0-15703166-linux.jar"],
            "Linux CI must have the exact official frozen AAPT2 binary checksum.",
        )
    }

    @Test
    fun rejectsCatalogDeclarationsItCannotValidate() {
        val root = projectRoot()

        assertCatalogDeclarationsRejected(
            catalog = Files.readString(root.resolve("gradle/libs.versions.toml")),
            verifiedComponents = verifiedComponents(root.resolve("gradle/verification-metadata.xml")),
        )
    }

    @Test
    fun rejectsWrapperUrlThatIsNotTheOfficialPinnedDistribution() {
        assertWrapperUrlRejected(Files.readString(projectRoot().resolve("gradle/wrapper/gradle-wrapper.properties")))
    }

    @Test
    fun rejectsCatalogAdversariesWithCrLfInput() {
        val root = projectRoot()
        val catalog = Files.readString(root.resolve("gradle/libs.versions.toml"))
            .replace("\r\n", "\n")
            .replace("\n", "\r\n")

        assertCatalogDeclarationsRejected(
            catalog = catalog,
            verifiedComponents = verifiedComponents(root.resolve("gradle/verification-metadata.xml")),
        )
    }

    @Test
    fun adversarialValidationDoesNotMutateTrackedPolicyFiles() {
        val root = projectRoot()
        val catalogPath = root.resolve("gradle/libs.versions.toml")
        val wrapperPath = root.resolve("gradle/wrapper/gradle-wrapper.properties")
        val originalCatalogBytes = Files.readAllBytes(catalogPath)
        val originalWrapperBytes = Files.readAllBytes(wrapperPath)

        assertCatalogDeclarationsRejected(
            catalog = Files.readString(catalogPath),
            verifiedComponents = verifiedComponents(root.resolve("gradle/verification-metadata.xml")),
        )
        assertWrapperUrlRejected(Files.readString(wrapperPath))

        assertArrayEquals(originalCatalogBytes, Files.readAllBytes(catalogPath), "Catalog validation must be pure.")
        assertArrayEquals(originalWrapperBytes, Files.readAllBytes(wrapperPath), "Wrapper validation must be pure.")
    }

    @Test
    fun evaluatedGradleAuthorityAcceptsCurrentFixedDependencyDeclarations() {
        val result = runEvaluatedDependencyPolicy()

        assertTrue(
            result.policyAccepted,
            "Current evaluated external dependency declarations must remain fixed and accepted.\n${result.output}",
        )
    }

    @Test
    fun evaluatedGradleAuthorityRejectsDynamicDeclarationsOutsideTheCatalog() {
        val result = runEvaluatedDependencyPolicy(dynamicDependencyFixture)
        val missingEvidence = dynamicDependencyEvidence.filterNot(result.output::contains)

        assertFalse(
            result.policyAccepted,
            "Evaluated Gradle policy must reject direct dynamic declarations in root, custom, subproject, " +
                "dependency-constraint, and buildscript configurations.\n${result.output}",
        )
        assertTrue(
            missingEvidence.isEmpty(),
            "Evaluated policy failure must identify every injected dynamic declaration; missing $missingEvidence.\n${result.output}",
        )
    }

    @Test
    fun evaluatedGradleAuthorityRejectsDependenciesAddedAfterConfigurationListeners() {
        val result = runEvaluatedDependencyPolicy(lateInitScript = lateDynamicDependencyInjection)
        val missingEvidence = lateDynamicDependencyEvidence.filterNot(result.output::contains)

        assertFalse(
            result.policyAccepted,
            "Evaluated Gradle policy must reject dependencies added by later projectsEvaluated and " +
                "taskGraph.whenReady listeners.\n${result.output}",
        )
        assertTrue(
            missingEvidence.isEmpty(),
            "Late dependency-policy failure must identify every injected declaration; missing $missingEvidence.\n${result.output}",
        )
    }

    @Test
    fun evaluatedGradleAuthorityFailsClosedOnReservedTaskNameCollision() {
        val result = runEvaluatedDependencyPolicy(taskCollisionFixture)

        assertFalse(
            result.policyAccepted,
            "A build that preclaims the reserved dependency-policy task name must fail closed.\n${result.output}",
        )
        assertTrue(
            result.output.contains("COCKPIT_DEPENDENCY_POLICY_TASK_COLLISION=cockpitVerifyEvaluatedDependencyPolicy"),
            "Task collision failure must carry deterministic policy evidence.\n${result.output}",
        )
    }

    @Test
    fun evaluatedGradleRunnerRejectsSkippedOrClearedAuthorityTask() {
        val outcomes = authorityTaskSuppressions.associate { suppression ->
            suppression.description to runEvaluatedDependencyPolicy(lateInitScript = suppression.script)
        }

        assertTrue(
            outcomes.all { (_, result) -> result.completed && result.exitCode == 0 },
            "Suppression fixtures must isolate an exit-zero task bypass: $outcomes",
        )
        assertTrue(
            outcomes.none { (_, result) -> result.policyAccepted },
            "A skipped or action-cleared authority task must not satisfy policy acceptance: $outcomes",
        )
    }

    @Test
    fun completionAttestationMustBeOneExactLineAlongsideSuccessfulExit() {
        val expected = "COCKPIT_DEPENDENCY_POLICY_COMPLETE=00112233445566778899aabbccddeeff"
        val invalidResults = listOf(
            GradleResult(completed = true, exitCode = 0, output = "", expectedCompletionSentinel = expected),
            GradleResult(completed = true, exitCode = 0, output = "$expected\n$expected\n", expectedCompletionSentinel = expected),
            GradleResult(completed = true, exitCode = 0, output = "prefix$expected\n", expectedCompletionSentinel = expected),
            GradleResult(completed = true, exitCode = 0, output = "$expected suffix\n", expectedCompletionSentinel = expected),
            GradleResult(completed = true, exitCode = 1, output = "$expected\n", expectedCompletionSentinel = expected),
            GradleResult(completed = false, exitCode = null, output = "$expected\n", expectedCompletionSentinel = expected),
        )

        invalidResults.forEach { result -> assertFalse(result.policyAccepted, "Invalid attestation was accepted: $result") }
        assertTrue(
            GradleResult(true, 0, "diagnostic\n$expected\n", expected).policyAccepted,
            "Exactly one exact completion line plus exit zero must be accepted.",
        )
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve(".git").exists() }

    private fun runEvaluatedDependencyPolicy(
        fixture: GradleFixture? = null,
        lateInitScript: String? = null,
    ): GradleResult {
        val root = projectRoot()
        val tempDirectory = Files.createTempDirectory("cockpit-dependency-policy-")
        val expectedCompletionSentinel = newCompletionSentinel()
        val fixtureRoot = fixture?.let { tempDirectory.resolve("fixture") }
        val policyScript = tempDirectory.resolve("dependency-policy.gradle")
        val lateScript = lateInitScript?.let { tempDirectory.resolve("late-mutation.gradle") }
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            mutableListOf("cmd", "/c", "gradlew.bat")
        } else {
            mutableListOf("./gradlew")
        }.apply {
            addAll(
                listOf(
                    "--no-daemon",
                    "--offline",
                    "--console=plain",
                    "--project-cache-dir",
                    tempDirectory.resolve("project-cache").toString(),
                    "-I",
                    policyScript.toString(),
                ),
            )
            if (lateScript != null) addAll(listOf("-I", lateScript.toString()))
            if (fixtureRoot != null) addAll(listOf("-p", fixtureRoot.toString()))
            add(evaluatedDependencyPolicyTaskName)
        }
        val processResult = BoundedProcessRunner.run(
            command = command,
            workingDirectory = root,
            timeout = 90,
            unit = TimeUnit.SECONDS,
            ownedTemporaryDirectory = tempDirectory,
            prepare = {
                Files.writeString(policyScript, evaluatedDependencyPolicyInitScript(expectedCompletionSentinel))
                if (lateScript != null) Files.writeString(lateScript, lateInitScript)
                if (fixtureRoot != null) {
                    Files.createDirectories(fixtureRoot.resolve("child"))
                    Files.writeString(fixtureRoot.resolve("settings.gradle"), fixture.settings)
                    Files.writeString(fixtureRoot.resolve("build.gradle"), fixture.rootBuild)
                    Files.writeString(fixtureRoot.resolve("child/build.gradle"), fixture.childBuild)
                }
            },
        )
        return GradleResult(
            completed = processResult.completed,
            exitCode = processResult.exitCode,
            output = processResult.output,
            expectedCompletionSentinel = expectedCompletionSentinel,
        )
    }

    private fun newCompletionSentinel(): String {
        val nonce = ByteArray(32).also(completionNonceRandom::nextBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$completionSentinelPrefix$nonce"
    }

    private fun validateCatalogPolicy(catalog: String, verifiedComponents: Set<String>) {
        val parsedCatalog = parseCatalog(catalog)
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
    }

    private fun validateGradlePropertiesPolicy(gradleProperties: String) {
        assertTrue(
            gradleProperties.lineSequence().any { it.trim() == "org.gradle.dependency.verification=strict" },
            "Gradle must reject dependencies that are not recorded in verification metadata.",
        )
    }

    private fun validateWrapperProperties(wrapperProperties: Properties) {
        assertTrue(
            wrapperProperties.getProperty("distributionSha256Sum") ==
                "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746",
            "The Gradle wrapper must pin Gradle 9.5.0's official SHA-256 distribution checksum.",
        )
        assertEquals(
            "https://services.gradle.org/distributions/gradle-9.5.0-bin.zip",
            wrapperProperties.getProperty("distributionUrl"),
            "The Gradle wrapper must use the exact official pinned binary distribution URL.",
        )
    }

    private fun assertCatalogDeclarationsRejected(catalog: String, verifiedComponents: Set<String>) {
        catalogAdversaries.forEach { adversary ->
            assertRejectedMutation(catalog, adversary.description, adversary.mutate) { mutatedCatalog ->
                validateCatalogPolicy(mutatedCatalog, verifiedComponents)
            }
        }
    }

    private fun assertWrapperUrlRejected(wrapperProperties: String) {
        assertRejectedMutation(
            original = wrapperProperties,
            description = "unofficial wrapper distribution URL",
            mutate = {
                it.replace(
                    "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.5.0-bin.zip",
                    "distributionUrl=https\\://mirror.example/gradle-9.5.0-bin.zip",
                )
            },
        ) { mutatedWrapperProperties ->
            validateWrapperProperties(loadProperties(mutatedWrapperProperties))
        }
    }

    private fun assertRejectedMutation(
        original: String,
        description: String,
        mutate: (String) -> String,
        validate: (String) -> Unit,
    ) {
        val normalized = normalizeLineEndings(original)
        val mutated = mutate(normalized)
        assertFalse(mutated == normalized, "Adversarial mutation '$description' must change the normalized input.")
        assertThrows(AssertionError::class.java) { validate(mutated) }
    }

    private fun normalizeLineEndings(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

    private fun loadProperties(value: String): Properties = Properties().apply { value.reader().use(::load) }

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

    private fun verifiedArtifactChecksums(
        metadata: Path,
        group: String,
        name: String,
        version: String,
    ): Map<String, Set<String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(metadata.toFile())
        val components = document.getElementsByTagName("component")
        val component = (0 until components.length)
            .map(components::item)
            .singleOrNull { candidate ->
                candidate.attributes.let { attributes ->
                    attributes.getNamedItem("group")?.nodeValue == group &&
                        attributes.getNamedItem("name")?.nodeValue == name &&
                        attributes.getNamedItem("version")?.nodeValue == version
                }
            } ?: throw AssertionError("Missing or duplicate verification component '$group:$name:$version'.")

        return (0 until component.childNodes.length)
            .map(component.childNodes::item)
            .filter { it.nodeName == "artifact" }
            .associate { artifact ->
                val artifactName = artifact.attributes.getNamedItem("name")?.nodeValue
                    ?: throw AssertionError("Verification artifact is missing its name.")
                val checksums = (0 until artifact.childNodes.length)
                    .map(artifact.childNodes::item)
                    .filter { it.nodeName == "sha256" }
                    .map { checksum ->
                        checksum.attributes.getNamedItem("value")?.nodeValue
                            ?: throw AssertionError("Artifact '$artifactName' has a SHA-256 entry without a value.")
                    }
                    .toSet()
                artifactName to checksums
            }
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private data class Catalog(val versions: Map<String, String>, val aliases: List<Alias>)

    private data class GradleResult(
        val completed: Boolean,
        val exitCode: Int?,
        val output: String,
        val expectedCompletionSentinel: String,
    ) {
        val policyAccepted: Boolean
            get() =
                completed &&
                    exitCode == 0 &&
                    output.lineSequence().count { line -> line == expectedCompletionSentinel } == 1
    }

    private data class GradleFixture(val settings: String, val rootBuild: String, val childBuild: String)

    private data class TaskSuppression(val description: String, val script: String)

    private data class CatalogAdversary(val description: String, val mutate: (String) -> String)

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
        const val evaluatedDependencyPolicyTaskName = "cockpitVerifyEvaluatedDependencyPolicy"
        const val completionSentinelPrefix = "COCKPIT_DEPENDENCY_POLICY_COMPLETE="
        val completionNonceRandom = SecureRandom()

        fun evaluatedDependencyPolicyInitScript(completionSentinel: String) =
            """
            gradle.projectsEvaluated {
                def root = gradle.rootProject
                def policyTaskName = "$evaluatedDependencyPolicyTaskName"
                if (root.tasks.findByName(policyTaskName) != null) {
                    println("COCKPIT_DEPENDENCY_POLICY_TASK_COLLISION=" + policyTaskName)
                    throw new GradleException("Reserved dependency-policy task already exists: " + policyTaskName)
                }

                root.tasks.register(policyTaskName) {
                    group = "verification"
                    description = "Rejects dynamic external dependency declarations in the final evaluated model."
                    doLast {
                        def violations = []
                        def dynamicSelector = { selector ->
                            if (selector == null) return false
                            def value = selector.toString().trim()
                            value.contains("+") ||
                                value.contains("*") ||
                                value ==~ /(?i).*latest\..*/ ||
                                value ==~ /.*[\[\]()].*/
                        }
                        def nonBlank = { value -> value != null && !value.toString().trim().isEmpty() }
                        def fixedSelector = { selector -> nonBlank(selector) && !dynamicSelector(selector) }
                        def recordViolation = { owner, scope, configuration, kind, group, name, label, value ->
                            violations.add(
                                owner.path + "|" + scope + "|" + configuration.name + "|" + kind + "|" +
                                    group + ":" + name + "|" + label + "=" + value,
                            )
                        }
                        def inspectConstraint = { owner, scope, configuration, kind, group, name, constraint ->
                            def selectors = [
                                required: constraint.requiredVersion,
                                strict: constraint.strictVersion,
                                preferred: constraint.preferredVersion,
                            ]
                            selectors.findAll { label, value -> dynamicSelector(value) }.each { label, value ->
                                recordViolation(owner, scope, configuration, kind, group, name, label, value)
                            }
                            if (constraint.hasProperty("branch") && nonBlank(constraint.branch)) {
                                recordViolation(owner, scope, configuration, kind, group, name, "branch", constraint.branch)
                            }
                            if (
                                nonBlank(constraint.preferredVersion) &&
                                    !fixedSelector(constraint.requiredVersion) &&
                                    !fixedSelector(constraint.strictVersion)
                            ) {
                                recordViolation(
                                    owner,
                                    scope,
                                    configuration,
                                    kind,
                                    group,
                                    name,
                                    "preferred-without-fixed-hard-bound",
                                    constraint.preferredVersion,
                                )
                            }
                        }
                        def inspectConfigurations = { owner, scope, configurations ->
                            configurations.each { configuration ->
                                configuration.dependencies.withType(org.gradle.api.artifacts.ExternalModuleDependency).each { dependency ->
                                    if (dependency.changing) {
                                        recordViolation(
                                            owner,
                                            scope,
                                            configuration,
                                            "dependency",
                                            dependency.group,
                                            dependency.name,
                                            "changing",
                                            true,
                                        )
                                    }
                                    inspectConstraint(
                                        owner,
                                        scope,
                                        configuration,
                                        "dependency",
                                        dependency.group,
                                        dependency.name,
                                        dependency.versionConstraint,
                                    )
                                }
                                configuration.dependencyConstraints.each { constraint ->
                                    inspectConstraint(
                                        owner,
                                        scope,
                                        configuration,
                                        "constraint",
                                        constraint.group,
                                        constraint.name,
                                        constraint.versionConstraint,
                                    )
                                }
                            }
                        }

                        root.allprojects.each { candidate ->
                            inspectConfigurations(candidate, "project", candidate.configurations)
                            inspectConfigurations(candidate, "buildscript", candidate.buildscript.configurations)
                        }
                        if (!violations.isEmpty()) {
                            violations.sort().each { println("COCKPIT_DYNAMIC_DEPENDENCY_POLICY=" + it) }
                            throw new GradleException(
                                "Dynamic external dependency declarations are forbidden; " + violations.size() + " violation(s).",
                            )
                        }
                        println("$completionSentinel")
                    }
                }
            }
            """.trimIndent()
        val dynamicDependencyEvidence = listOf(
            "example:plus",
            "example:latest",
            "example:range",
            "example:rich",
            "example:branch",
            "example:changing",
            "example:prefer-only",
        )
        val lateDynamicDependencyEvidence = listOf("example:late-projects", "example:late-graph")
        val lateDynamicDependencyInjection =
            """
            gradle.beforeProject { candidate ->
                if (candidate.parent == null) {
                    candidate.configurations.create("dependencyPolicyLateProjectsProbe")
                    candidate.configurations.create("dependencyPolicyLateGraphProbe")
                }
            }
            gradle.projectsEvaluated {
                gradle.rootProject.dependencies.add(
                    "dependencyPolicyLateProjectsProbe",
                    "example:late-projects:7.+",
                )
            }
            gradle.taskGraph.whenReady {
                gradle.rootProject.dependencies.add(
                    "dependencyPolicyLateGraphProbe",
                    "example:late-graph:8.+",
                )
            }
            """.trimIndent()
        val dynamicDependencyFixture = GradleFixture(
            settings =
                """
                rootProject.name = "dependency-policy-fixture"
                include(":child")
                """.trimIndent(),
            rootBuild =
                """
                buildscript {
                    dependencies {
                        constraints {
                            add("classpath", "example:rich") {
                                version {
                                    strictly("[3,4)")
                                    prefer("latest.integration")
                                }
                            }
                        }
                    }
                }

                configurations.create("dependencyPolicyRootProbe")
                configurations.create("dependencyPolicyConstraintProbe")
                configurations.create("dependencyPolicyBranchProbe")
                configurations.create("dependencyPolicyChangingProbe")
                configurations.create("dependencyPolicyPreferOnlyProbe")
                dependencies {
                    add("dependencyPolicyRootProbe", "example:plus:1.+")
                    add("dependencyPolicyBranchProbe", "example:branch:1.2.3") {
                        version {
                            branch = "main"
                        }
                    }
                    add("dependencyPolicyChangingProbe", "example:changing:1.2.3") {
                        changing = true
                    }
                    constraints {
                        add("dependencyPolicyConstraintProbe", "example:range:[1,2)")
                        add("dependencyPolicyPreferOnlyProbe", "example:prefer-only") {
                            version {
                                prefer("1.2.3")
                            }
                        }
                    }
                }
                """.trimIndent(),
            childBuild =
                """
                configurations.create("dependencyPolicyChildProbe")
                dependencies.add("dependencyPolicyChildProbe", "example:latest:latest.release")
                """.trimIndent(),
        )
        val taskCollisionFixture = GradleFixture(
            settings = "rootProject.name = \"dependency-policy-task-collision\"",
            rootBuild = "tasks.register(\"cockpitVerifyEvaluatedDependencyPolicy\")",
            childBuild = "",
        )
        val authorityTaskSuppressions = listOf(
            TaskSuppression(
                description = "disabled at taskGraph.whenReady",
                script =
                    """
                    gradle.taskGraph.whenReady {
                        gradle.rootProject.tasks.named("cockpitVerifyEvaluatedDependencyPolicy").get().enabled = false
                    }
                    """.trimIndent(),
            ),
            TaskSuppression(
                description = "actions cleared by a later projectsEvaluated listener",
                script =
                    """
                    gradle.projectsEvaluated {
                        gradle.rootProject.tasks.named("cockpitVerifyEvaluatedDependencyPolicy").get().setActions([])
                    }
                    """.trimIndent(),
            ),
        )
        val catalogAdversaries = listOf(
            CatalogAdversary("string library declaration") {
                it.replace("[libraries]\n", "[libraries]\nbypass-string = \"example:artifact:1.+\"\n")
            },
            CatalogAdversary("group and name library declaration") {
                it.replace(
                    "[libraries]\n",
                    "[libraries]\nbypass-group-name = { group = \"example\", name = \"artifact\", version = \"1.+\" }\n",
                )
            },
            CatalogAdversary("dotted library table") {
                "$it\n[libraries.bypass-dotted]\nmodule = \"example:artifact\"\nversion = \"1.+\"\n"
            },
            CatalogAdversary("rich version declaration") {
                it.replace("[versions]\n", "[versions]\nbypass-rich = { strictly = \"1.0\", prefer = \"1.+\" }\n")
            },
        )
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
