plugins {
    java
    id("org.springframework.boot") version "4.2.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.14.0"
}

group = "pl.fradomski"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Needed by the generated API code (@Generated annotation).
    implementation("jakarta.annotation:jakarta.annotation-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Validates real HTTP exchanges against the spec in ContractIT (README §11).
    testImplementation("com.atlassian.oai:openapi-request-validator-core:3.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// --- API contract: interfaces + DTOs generated at build time from the spec --------------------
// The spec is the source of truth; generated code lives in build/ and is never committed.
val openApiOutputDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName = "spring"
    inputSpec = layout.projectDirectory.file("devtask/trouble-ticket-api.yaml").asFile.path
    outputDir = openApiOutputDir.get().asFile.path
    apiPackage = "pl.fradomski.troubleticket.api"
    modelPackage = "pl.fradomski.troubleticket.api.model"
    generateApiTests = false
    generateModelTests = false
    generateApiDocumentation = false
    generateModelDocumentation = false
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "skipDefaultInterface" to "true",
        "useSpringBoot3" to "true", // jakarta.* namespace; compatible with Boot 4
        "useTags" to "true",
        "openApiNullable" to "false",
        "documentationProvider" to "none",
        "sourceFolder" to "src/main/java",
    )
    // Only the API interfaces and models; no generated Application class, pom, etc.
    globalProperties = mapOf("apis" to "", "models" to "", "supportingFiles" to "ApiUtil.java")
}

sourceSets {
    main {
        java.srcDir(openApiOutputDir.map { it.dir("src/main/java") })
    }
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
