import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.kotlin.dsl.KotlinClosure2
import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.tasks.TaskProvider

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

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(resolvedBundledPlugins.split(',').filter { it.isNotBlank() })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.JUnit5)
    }

    // 24.x aligns with OkHttp 4.12 (Java CacheControl); 25+ pulls OkHttp 5.x which needs stdlib 2.2+.
    implementation("io.kubernetes:client-java:24.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.22.3")

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
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/deployment.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            // Gateway internal builds break verifier — DO NOT use recommended()
            // This list of IDE versions ia to be updated manually when:
            // - A new IDE version gets published (whatever it is: a minor, a major or a build number)
            // - We decide to switch to a newer "since build number" (is to be converted into corresponding exact version number)
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

// Base ref compared by the 'detektBranch' task; override with -PdetektBaseRef=origin/main
val detektBaseRef = providers.gradleProperty("detektBaseRef").getOrElse("main")

// Configuration-cache compatible git reads:
// committed changes since the branch diverged from the base ref, uncommitted tracked changes and untracked files
val committedChanges = providers.exec {
    commandLine("git", "diff", "--name-only", "-z", "--diff-filter=ACMR", "$detektBaseRef...HEAD", "--", "src")
}.standardOutput.asText
val uncommittedChanges = providers.exec {
    commandLine("git", "diff", "--name-only", "-z", "--diff-filter=ACMR", "HEAD", "--", "src")
}.standardOutput.asText
val untrackedFiles = providers.exec {
    commandLine("git", "ls-files", "--others", "--exclude-standard", "-z", "--", "src")
}.standardOutput.asText

val changedKotlinFiles = committedChanges
    .zip(uncommittedChanges) { committed, uncommitted -> committed + uncommitted }
    .zip(untrackedFiles) { tracked, untracked -> tracked + untracked }
    .map { output ->
        // NUL-separated paths from 'git diff -z' / 'git ls-files -z'
        output.split('\u0000')
            .filter { it.endsWith(".kt") }
            .distinct()
    }

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

    // Runs detekt on all Kotlin files changed on the current branch vs. 'detektBaseRef':
    // committed branch changes, staged, unstaged and untracked files. 'detekt' runs on all files.
    register<Detekt>("detektBranch") {
        // local copy: keeps the onlyIf spec free of build script references for the configuration cache
        val baseRef = detektBaseRef
        group = "verification"
        description =
            "Runs detekt on Kotlin files changed on the current branch vs. '$detektBaseRef' (committed and uncommitted)."
        notCompatibleWithConfigurationCache("reads git working tree state at execution time")
        setSource(files(changedKotlinFiles))
        onlyIf {
            val hasChanges = source.files.isNotEmpty()
            if (!hasChanges) {
                logger.lifecycle("No Kotlin changes vs. '$baseRef' - skipping detektBranch.")
            }
            hasChanges
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

            events(
                "failed",
                "skipped",
                *if (showPassed) arrayOf("passed") else emptyArray() // Use''-PshowPassed' option to see PASSED tests in output
            )

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
