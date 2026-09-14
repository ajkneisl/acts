plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

group = "dev.ajkneisl"

version = "1.1"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.2"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.slf4j:slf4j-api:2.0.19")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")

    // CIO rather than Netty: this serves a webhook and a healthcheck, and nothing else.
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(20)
}

val actsVersion = version.toString()

tasks.processResources {
    inputs.property("version", actsVersion)
    filesMatching("acts.properties") { filter { it.replace("@version@", actsVersion) } }
}

application {
    mainClass.set("dev.ajkneisl.acts.MainKt")
    applicationName = "acts"
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
