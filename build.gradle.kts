import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.kotlin.dsl.KotlinClosure2
import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.provider.ProviderFactory

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.detekt) // Detekt static code analysis
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
    kotlin("plugin.serialization") version libs.versions.kotlin.get() // Serialization needed for RedHat Auth
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// -Pidea switches from Gateway (GW) to IntelliJ IDEA (IU)
val isIdea = providers.gradleProperty("idea").isPresent
val resolvedPlatformType = if (isIdea) {
    "IU"
} else {
    providers.gradleProperty("platformType").get()
}
val resolvedBundledPlugins = if (isIdea) {
    "com.jetbrains.gateway"
} else {
    providers.gradleProperty("platformBundledPlugins").get()
}

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/platforms.html#sub:version-catalog
dependencies {
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockk)
    testImplementation(libs.mockk.agent.jvm)

    // Do NOT bundle kotlinx-coroutines: IntelliJ/Gateway provides its own version.
    // Adding another copy causes classloader conflicts, broken cancellation, and runtime errors.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") {
        // prevent coroutines-core from ending up inside the plugin
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
    configurations.all {
        // prevent coroutines-core from ending up inside the plugin
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(resolvedPlatformType, providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file
        // for bundled IntelliJ Platform plugins.
        bundledPlugins(resolvedBundledPlugins.split(',').filter { it.isNotBlank() })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file
        // for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.JUnit5)
    }

    // 24.x aligns with OkHttp 4.12 (Java CacheControl); 25+ pulls OkHttp 5.x which needs stdlib 2.2+.
    implementation("io.kubernetes:client-java:24.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.4")

    // RedHat Auth dependencies
    implementation("com.nimbusds:oauth2-oidc-sdk:11.38.2")
    implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")   // JWT processing

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${libs.versions.kotlinxSerialization.get()}")
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels,
        // like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            // Gateway internal builds break verifier — DO NOT use recommended()
            // This list of IDE versions ia to be updated manually when:
            // - A new IDE version gets published (whatever it is: a minor, a major or a build number)
            // - We decide to switch to a newer "since build number" (is to be converted into
            //   corresponding exact version number)
            create("GW", "2025.1.6")
            create("GW", "2025.2.5")
            create("GW", "2025.3.2")
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

// Base ref compared by the 'detektBranch' task: 'main' when available, else 'origin/main'.
// Override with -PdetektBaseRef=<ref>.
private val requestedBaseRef = providers.gradleProperty("detektBaseRef").orNull

/**
 * Lazily reads the git working tree state to detect changed Kotlin files.
 *
 * Fails with the command, exit code and git's own stderr instead of a bare 'exit value 128', keeping
 * every git invocation in one place. Each [query] runs one 'providers.exec' process, executed lazily
 * and cached for the build.
 */
private class GitChanges(
    private val providers: ProviderFactory,
    private val requestedBaseRef: String?,
) {
    private companion object {
        const val DEFAULT_BASE_REF = "main"
        const val WORKING_TREE_HINT = "Run inside a valid git repository working tree."
        val KOTLIN_FILE_EXTENSIONS = listOf(".kt", ".kts")
        val DEFAULT_BASE_REF_CANDIDATES = listOf(
            "refs/heads/$DEFAULT_BASE_REF",
            "refs/remotes/origin/$DEFAULT_BASE_REF",
        )
    }

    private data class GitQuery(val output: String, val error: String, val exitCode: Int)

    /** Changed Kotlin files per source, so callers can report where they came from. */
    data class ChangedFiles(
        val baseRef: String,
        val committed: List<String>,
        val uncommitted: List<String>,
        val untracked: List<String>,
    ) {
        /** Committed branch changes, uncommitted tracked changes and untracked files, deduplicated. */
        val all: List<String> = (committed + uncommitted + untracked).distinct()
    }

    /**
     * A git invocation failed; typed so callers can distinguish git failures from other build failures.
     * Not 'private' because the script's 'onlyIf' catch references it — the private enclosing class
     * already limits its visibility to this script.
     */
    class GitQueryException(message: String) : GradleException(message)

    /** Kotlin files to lint, fetched lazily from git; see [ChangedFiles]. */
    fun changedKotlinFiles(): Provider<ChangedFiles> = baseRef().flatMap { baseRef ->
        val committed = query(listOf("diff", "--name-only", "-z", "--diff-filter=ACMRT", "$baseRef...HEAD"))
            .requireSuccess("diff $baseRef...HEAD", missingBaseRefHint(baseRef))
        val uncommitted = query(listOf("diff", "--name-only", "-z", "--diff-filter=ACMRT", "HEAD"))
            .requireSuccess("diff HEAD", WORKING_TREE_HINT)
        val untracked = query(listOf("ls-files", "--others", "--exclude-standard", "-z"))
            .requireSuccess("ls-files --others --exclude-standard", WORKING_TREE_HINT)

        committed.zip(uncommitted) { committedFiles, uncommittedFiles -> committedFiles to uncommittedFiles }
            .zip(untracked) { (committedFiles, uncommittedFiles), untrackedFiles ->
                ChangedFiles(
                    baseRef = baseRef,
                    committed = kotlinFiles(committedFiles),
                    uncommitted = kotlinFiles(uncommittedFiles),
                    untracked = kotlinFiles(untrackedFiles),
                )
            }
    }

    /** Explicit '-PdetektBaseRef', else 'main', else 'origin/main'. */
    private fun baseRef(): Provider<String> =
        requestedBaseRef?.let(::explicitBaseRef) ?: defaultBaseRef()

    private fun explicitBaseRef(baseRef: String): Provider<String> =
        query(listOf("rev-parse", "--verify", "--quiet", "$baseRef^{commit}")).map { query ->
            if (query.exitCode == 0) {
                baseRef
            } else {
                throw GitQueryException(
                    buildString {
                        appendLine("Base ref '$baseRef' does not exist (git rev-parse exited with ${query.exitCode}).")
                        append(
                            "Ensure it exists locally ('git fetch origin') or " +
                                "pass another ref with -PdetektBaseRef.",
                        )
                    }
                )
            }
        }

    private fun defaultBaseRef(): Provider<String> =
        query(listOf("for-each-ref", "--format=%(refname)") + DEFAULT_BASE_REF_CANDIDATES)
            .requireSuccess("for-each-ref", WORKING_TREE_HINT)
            .map { output ->
                val existingRefs = output.lines().filter { it.isNotBlank() }
                val existingBaseRef = DEFAULT_BASE_REF_CANDIDATES.firstOrNull { it in existingRefs }
                    ?: throw GitQueryException(
                        buildString {
                            appendLine("No base ref found. Tried ${DEFAULT_BASE_REF_CANDIDATES.joinToString()}.")
                            append(
                                "Fetch it ('git fetch origin $DEFAULT_BASE_REF') or " +
                                    "pass another ref with -PdetektBaseRef.",
                            )
                        }
                    )
                existingBaseRef.removePrefix("refs/heads/").removePrefix("refs/remotes/")
            }

    private fun kotlinFiles(output: String): List<String> =
        // NUL-separated paths from 'git diff -z' / 'git ls-files -z'
        output.split('\u0000')
            .filter { path -> KOTLIN_FILE_EXTENSIONS.any { path.endsWith(it) } }
            .distinct()

    private fun query(args: List<String>): Provider<GitQuery> {
        val execution = providers.exec {
            commandLine(listOf("git") + args)
            isIgnoreExitValue = true
        }
        return execution.result.zip(execution.standardOutput.asText) { result, stdout ->
            result.exitValue to stdout
        }.zip(execution.standardError.asText) { (exitCode, stdout), stderr ->
            GitQuery(stdout, stderr.trim(), exitCode)
        }
    }

    private fun Provider<GitQuery>.requireSuccess(command: String, hint: String): Provider<String> = map { query ->
        if (query.exitCode != 0) {
            throw GitQueryException(failureMessage(command, query, hint))
        }
        query.output
    }

    private fun missingBaseRefHint(baseRef: String): String = buildString {
        appendLine("Changed-file detection compares against '$baseRef'.")
        append("Ensure '$baseRef' exists locally ('git fetch origin') or pass another ref with -PdetektBaseRef.")
    }

    private fun failureMessage(command: String, query: GitQuery, hint: String): String = buildString {
        appendLine("'git $command' failed with exit code ${query.exitCode}: ${query.error.ifEmpty { "<no stderr>" }}")
        append(hint)
    }
}

private val gitChanges = GitChanges(providers, requestedBaseRef)

private val changedKotlinFiles = gitChanges.changedKotlinFiles()

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    // The detekt plugin adds 'detekt' to 'check' by default, which would lint the whole codebase
    // in CI. Lint runs are started explicitly via 'detekt' or 'detektBranch' instead.
    named("check") {
        setDependsOn(dependsOn.filterNot { it is TaskProvider<*> && it.name == "detekt" })
    }

    // Runs detekt on all Kotlin files (.kt, .kts) changed, staged, unstaged and untracked files.
    // 'detekt' runs on all files.
    register<Detekt>("detektBranch") {
        // local copy: keeps the onlyIf spec free of build script references for the configuration cache
        val changedFiles = changedKotlinFiles
        group = "verification"
        description =
            "Runs detekt on Kotlin files (.kt, .kts) changed on the current branch vs. the base ref " +
                "(default: main, falling back to origin/main; override with -PdetektBaseRef)."
        notCompatibleWithConfigurationCache("reads git working tree state at execution time")
        setSource(files(changedFiles.map { changed -> changed.all }))
        onlyIf {
            // explicitly handling git errors
            val changed = try {
                changedFiles.get()
            } catch (gitFailure: GitChanges.GitQueryException) {
                logger.info(
                    "Git query failed, running detektBranch to report it against the task: " +
                        "${gitFailure.message}",
                )
                return@onlyIf true
            }
            if (changed.all.isEmpty()) {
                logger.lifecycle("No Kotlin files (.kt, .kts) changed vs '${changed.baseRef}' - skipping detektBranch.")
                false
            } else {
                logger.lifecycle(
                    "detektBranch: ${changed.all.size} Kotlin file(s) changed vs '${changed.baseRef}' " +
                        "(committed: ${changed.committed.size}, uncommitted: ${changed.uncommitted.size}, " +
                        "untracked: ${changed.untracked.size})",
                )
                true
            }
        }
        reports {
            html.required.set(true)
            html.outputLocation.set(layout.buildDirectory.file("reports/detekt/detektBranch.html"))
            xml.required.set(false)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }

    withType<Test> {
        useJUnitPlatform()
          // Force tests to always run
        outputs.upToDateWhen { false }
        jvmArgs(
            "-Dnet.bytebuddy.experimental=true",
            "-Dmockk.agent.global=false",
            // Suppress dynamic agent loading trace warnings
            "-Djdk.instrument.traceUsage=false"
        )
        testLogging {
            // Exclude standardError, standardOut
            val showPassed = providers.gradleProperty("showPassed").isPresent
            if (!showPassed) {
                println(
                    """
                    ℹ️  Passed test output is hidden.
                       Re-run with -PshowPassed to include PASSED tests in the output.
                    """.trimIndent()
                )
            }

            events("failed", "skipped")
            if (showPassed) {
                // Use -PshowPassed to include PASSED tests in the output
                events("passed")
            }

            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
        afterSuite(
            KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
                if (desc.parent == null) {
                    println(
                        """
                    ===============================
                    Test summary:
                      Total:   ${result.testCount}
                      Passed:  ${result.successfulTestCount}
                      Failed:  ${result.failedTestCount}
                      Skipped: ${result.skippedTestCount}
                    ===============================
                    """.trimIndent()
                    )
                }
            })
        )
    }
}

intellijPlatformTesting {
    runIde {
        // Visible next to runIde under "intellij platform" in the Gradle tool window
        register("runIdeIdea") {
            type = IntelliJPlatformType.IntellijIdeaUltimate
            version = providers.gradleProperty("platformVersion")

            plugins {
                bundledPlugin("com.jetbrains.gateway")
            }
        }
    }
}
