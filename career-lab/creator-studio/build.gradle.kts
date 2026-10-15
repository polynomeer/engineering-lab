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
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.retry:spring-retry")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation(project(":worker-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.2")
    testImplementation("org.testcontainers:mysql:1.20.2")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
    testImplementation("org.awaitility:awaitility:4.2.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 벤치마크·통합 테스트가 섞여 있어 기본 타임아웃을 넉넉히 둔다 (신규 결정 — lab 전용)
    testLogging {
        events("passed", "skipped", "failed", "standardOut")
        showStandardStreams = true
    }
}

// External endpoints are provisioned by the controller; no Docker client in this worker.
val labRuntime by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[labRuntime.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[labRuntime.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
dependencies { add(labRuntime.implementationConfigurationName, project(":worker-support")) }
val creatorWorkerJar by tasks.registering(org.springframework.boot.gradle.tasks.bundling.BootJar::class) {
    dependsOn(tasks.named(labRuntime.classesTaskName))
    archiveClassifier.set("creator-worker")
    targetJavaVersion.set(JavaVersion.VERSION_21)
    mainClass.set("com.portfolio.creatorlab.worker.CreatorWorker")
    classpath = labRuntime.runtimeClasspath
}
sourceSets.test.get().compileClasspath += labRuntime.output
tasks.test {
    dependsOn(creatorWorkerJar)
    inputs.file(creatorWorkerJar.flatMap { it.archiveFile })
    systemProperty("creator.worker.jar", creatorWorkerJar.get().archiveFile.get().asFile.absolutePath)
    classpath += labRuntime.output + configurations[labRuntime.runtimeClasspathConfigurationName]
}
tasks.register("creatorWorkerManifest") {
    dependsOn(creatorWorkerJar)
    val jar = creatorWorkerJar.flatMap { it.archiveFile }
    val descriptor = layout.buildDirectory.file("libs/creator-worker-manifest.json")
    inputs.file(jar); outputs.file(descriptor)
    doLast {
        val artifact = jar.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) }
        }
        descriptor.get().asFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf(
            "schemaVersion" to 1, "protocolVersion" to 1, "moduleId" to "creator-studio",
            "experimentIds" to listOf("career.creator-studio.session-rotation", "career.creator-studio.mapping-integrity", "career.creator-studio.post-commit"),
            "javaMajor" to 21, "jarFile" to artifact.name,
            "artifactSha256" to HexFormat.of().formatHex(digest.digest()), "requiresDocker" to false
        ))) + "\n")
    }
}
