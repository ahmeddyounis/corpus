import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.0"
}

group = "dev.ahmeddyounis"
version = "0.1.0-SNAPSHOT"
description = "Corpus — AI document assistant: RAG service + MCP server"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))

    // Web, ops, persistence
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Security
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.19.0")
    implementation("com.bucket4j:bucket4j_jdk17-postgresql:8.19.0")

    // Spring AI: models (selected per profile via spring.ai.model.*), vector store, memory, MCP
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    implementation("org.springframework.ai:spring-ai-starter-model-transformers")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    implementation("org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // Ingestion
    implementation("org.apache.tika:tika-core:3.3.2")
    implementation("org.apache.tika:tika-parsers-standard-package:3.3.2")
    implementation("com.knuddels:jtokkit:1.1.0")

    // Resilience: core Resilience4j used programmatically. The Spring Boot 4
    // starter is missing from Resilience4j's BOM, and explicit call-site wrapping
    // avoids proxying/self-invocation pitfalls.
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.4.0")
    implementation("io.github.resilience4j:resilience4j-micrometer:2.4.0")

    // Observability & API docs
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.awaitility:awaitility")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("nightly")
    }
    maxHeapSize = "1g"
}

jacoco {
    toolVersion = "0.8.15"
}

// Coverage is measured on the core packages; configuration carriers and the
// launcher are excluded so the gate tracks logic, not boilerplate.
val coverageClassDirs = fun(): FileTree {
    return fileTree(layout.buildDirectory.dir("classes/java/main")) {
        include(
            "dev/ahmeddyounis/corpus/ingestion/**",
            "dev/ahmeddyounis/corpus/retrieval/**",
            "dev/ahmeddyounis/corpus/chat/**",
            "dev/ahmeddyounis/corpus/security/**",
        )
        exclude("**/*Properties*", "**/*Config*")
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(coverageClassDirs())
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(coverageClassDirs())
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// Judge-based answer-quality evals against a real model. Needs ANTHROPIC_API_KEY;
// without it the tagged tests are skipped and the task succeeds quietly.
tasks.register<Test>("nightlyEval") {
    description = "Runs LLM-as-judge answer quality evals (faithfulness, relevance)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("nightly")
    }
    maxHeapSize = "1g"
    shouldRunAfter(tasks.test)
}
