plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

group = "dev.ajkneisl"

version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.slf4j:slf4j-api:2.0.19")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(20)
}

application {
    mainClass.set("dev.ajkneisl.acts.MainKt")
    applicationName = "acts"
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
