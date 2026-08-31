import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.22.2"))
        constraints {
            classpath("org.apache.httpcomponents.client5:httpclient5:5.6.4")
            classpath("org.apache.httpcomponents.core5:httpcore5:5.4.3")
            classpath("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3")
            classpath("org.apache.commons:commons-lang3:3.20.0")
        }
    }
}

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    kotlin("plugin.jpa") version "2.4.10"
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.github.ben-manes.versions") version "0.61.0"
}

group = "no.novari"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenLocal()
    maven(url = "https://repo.fintlabs.no/releases")
    mavenCentral()
}

extra["commons-lang3.version"] = "3.20.0"
extra["httpclient5.version"] = "5.6.3"
extra["httpcore5.version"] = "5.4.3"
extra["jackson-bom.version"] = "2.22.2"
extra["log4j2.version"] = "2.26.1"
extra["postgresql.version"] = "42.7.12"

dependencies {
    constraints {
        implementation("at.yawk.lz4:lz4-java:1.11.2") {
            because("Fixes CVE-2026-59949 in the kafka-clients transitive dependency")
        }
    }

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")

    implementation("no.novari:flyt-gateway-starter:4.1.0-rc-3")
    implementation("no.novari:flyt-cache:3.0.0")
    implementation("no.novari:telemetry-starter:0.0.4")

    runtimeOnly("net.logstash.logback:logstash-logback-encoder:9.0")

    // Springdoc 3.x targets Spring Boot 4.x; keep 2.x for Boot 3.5.x compatibility.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.9.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(listOf("-Xjsr305=strict"))
    }
}

ktlint {
    version.set("1.8.0")
}

tasks.test {
    useJUnitPlatform()
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return !isStable
}

tasks.named<DependencyUpdatesTask>("dependencyUpdates") {
    rejectVersionIf {
        isNonStable(candidate.version)
    }
}
