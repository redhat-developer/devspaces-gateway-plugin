import org.gradle.api.tasks.Copy
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
}

group = "com.redhat.devtools.gateway"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
}

val gatewayHome = file(
    providers.gradleProperty("gatewayHome")
        .orElse(providers.environmentVariable("GATEWAY_HOME"))
        .getOrElse("${System.getProperty("user.home")}/AppData/Local/Programs/Gateway")
)

dependencies {
    compileOnly(fileTree(gatewayHome.resolve("lib")) { include("*.jar") })
    compileOnly(fileTree(gatewayHome.resolve("plugins")) { include("**/*.jar") })

    implementation("io.kubernetes:client-java:24.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.3")
    implementation("com.nimbusds:oauth2-oidc-sdk:11.15")
    implementation("com.nimbusds:nimbus-jose-jwt:10.9")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set("devspaces-gateway-plugin")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<ProcessResources>().configureEach {
    inputs.property("pluginVersion", project.version.toString())
    filesMatching("META-INF/plugin.xml") {
        expand("pluginVersion" to project.version.toString())
    }
}

val localPluginDir = layout.buildDirectory.dir("localPlugin/devspaces-gateway-plugin")

val assembleLocalPlugin by tasks.registering(Copy::class) {
    dependsOn(tasks.jar)
    into(localPluginDir)
    into("lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Zip>("buildLocalPlugin") {
    dependsOn(assembleLocalPlugin)
    archiveFileName.set("devspaces-gateway-plugin-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("localPlugin"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
