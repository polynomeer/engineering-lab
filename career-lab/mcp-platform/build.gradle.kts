import java.security.MessageDigest
import java.util.HexFormat

plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.portfolio"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation(project(":worker-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.2")
    testImplementation("org.testcontainers:mysql:1.20.2")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 동시성 벤치마크/통합 테스트가 많아 기본 타임아웃을 넉넉히 둔다 (신규 결정 — lab 전용)
    testLogging {
        events("passed", "skipped", "failed", "standardOut")
        showStandardStreams = true
    }
}

// Testcontainers belongs only to this worker deployment, not the domain application's runtime.
val labRuntime by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[labRuntime.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[labRuntime.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
dependencies {
    add(labRuntime.implementationConfigurationName, project(":worker-support"))
    add(labRuntime.implementationConfigurationName, "org.testcontainers:mysql:1.20.2")
}
val sequenceWorkerJar by tasks.registering(org.springframework.boot.gradle.tasks.bundling.BootJar::class) {
    dependsOn(tasks.named(labRuntime.classesTaskName))
    archiveClassifier.set("sequence-worker")
    targetJavaVersion.set(JavaVersion.VERSION_21)
    mainClass.set("com.portfolio.mcplab.worker.SequenceWorker")
    // Testcontainers extracts this directory through a regular jar:file URL.
    requiresUnpack("**/mysql-*.jar")
    classpath = labRuntime.runtimeClasspath
}
sourceSets.test.get().compileClasspath += labRuntime.output
tasks.test {
    dependsOn(sequenceWorkerJar)
    inputs.file(sequenceWorkerJar.flatMap { it.archiveFile })
    systemProperty("sequence.worker.jar", sequenceWorkerJar.get().archiveFile.get().asFile.absolutePath)
    classpath += labRuntime.output + configurations[labRuntime.runtimeClasspathConfigurationName]
}

// Build-relative deployment descriptor; the server selects its own trusted Java executable.
tasks.register("sequenceWorkerManifest") {
    dependsOn(sequenceWorkerJar)
    val jar = sequenceWorkerJar.flatMap { it.archiveFile }
    val descriptor = layout.buildDirectory.file("libs/sequence-worker-manifest.json")
    inputs.file(jar)
    outputs.file(descriptor)
    doLast {
        val artifact = jar.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) }
        }
        descriptor.get().asFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf(
            "schemaVersion" to 1, "protocolVersion" to 1, "moduleId" to "mcp-platform",
            "experimentIds" to listOf("career.mcp-platform.sequence"), "javaMajor" to 21,
            "jarFile" to artifact.name, "artifactSha256" to HexFormat.of().formatHex(digest.digest()),
            "heapMiB" to 256, "requiresDocker" to true
        ))) + "\n")
    }
}

// Shared by JUnit and the isolated checks deployment; never on the application classpath.
val checkRuntime by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[checkRuntime.implementationConfigurationName].extendsFrom(configurations.implementation.get())
dependencies {
    add(checkRuntime.implementationConfigurationName, project(":worker-support"))
    add(checkRuntime.implementationConfigurationName, "com.tngtech.archunit:archunit:1.3.0")
}
val checksWorkerJar by tasks.registering(org.springframework.boot.gradle.tasks.bundling.BootJar::class) {
    dependsOn(tasks.named(checkRuntime.classesTaskName))
    archiveClassifier.set("checks-worker")
    targetJavaVersion.set(JavaVersion.VERSION_21)
    mainClass.set("com.portfolio.mcplab.worker.ChecksWorker")
    classpath = checkRuntime.runtimeClasspath
}
sourceSets.test.get().compileClasspath += checkRuntime.output
tasks.test {
    dependsOn(checksWorkerJar)
    inputs.file(checksWorkerJar.flatMap { it.archiveFile })
    systemProperty("checks.worker.jar", checksWorkerJar.get().archiveFile.get().asFile.absolutePath)
    classpath += checkRuntime.output + configurations[checkRuntime.runtimeClasspathConfigurationName]
}
tasks.register("checksWorkerManifest") {
    dependsOn(checksWorkerJar)
    val jar = checksWorkerJar.flatMap { it.archiveFile }
    val descriptor = layout.buildDirectory.file("libs/checks-worker-manifest.json")
    inputs.file(jar)
    outputs.file(descriptor)
    doLast {
        val artifact = jar.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) }
        }
        descriptor.get().asFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf(
            "schemaVersion" to 1, "protocolVersion" to 1, "moduleId" to "mcp-platform",
            "experimentIds" to listOf("career.mcp-platform.architecture", "career.mcp-platform.validation"),
            "javaMajor" to 21, "jarFile" to artifact.name,
            "artifactSha256" to HexFormat.of().formatHex(digest.digest()), "requiresDocker" to false
        ))) + "\n")
    }
}
