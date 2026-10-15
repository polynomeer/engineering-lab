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
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.apache.poi:poi:5.3.0")
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    runtimeOnly("com.mysql:mysql-connector-j")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation(project(":worker-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.testcontainers:junit-jupiter:1.20.2")
    testImplementation("org.testcontainers:mysql:1.20.2")
    testImplementation("org.awaitility:awaitility:4.2.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // 메모리 벤치마크성 테스트(대량 시드 데이터 삽입 + Job 2회 실행)가 있어
    // 기본 타임아웃을 넉넉히 둔다 (신규 결정 — lab 전용)
    testLogging {
        events("passed", "skipped", "failed", "standardOut")
        showStandardStreams = true
    }
    // [NEW-DESIGN] 힙 사용량을 직접 비교하는 벤치마크이므로, Gradle 데몬/워커의
    // 다른 테스트와 힙을 공유하지 않도록 매 테스트 클래스를 새 JVM 포크로 실행한다.
    forkEvery = 1
    maxHeapSize = "2g"
}

// External endpoints are provisioned by the controller; no Docker client in this worker.
val labRuntime by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[labRuntime.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[labRuntime.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
dependencies { add(labRuntime.implementationConfigurationName, project(":worker-support")) }
val memoryWorkerJar by tasks.registering(org.springframework.boot.gradle.tasks.bundling.BootJar::class) {
    dependsOn(tasks.named(labRuntime.classesTaskName))
    archiveClassifier.set("memory-worker")
    targetJavaVersion.set(JavaVersion.VERSION_21)
    mainClass.set("com.portfolio.batchlab.worker.MemoryWorker")
    classpath = labRuntime.runtimeClasspath
}
sourceSets.test.get().compileClasspath += labRuntime.output
tasks.test {
    dependsOn(memoryWorkerJar)
    inputs.file(memoryWorkerJar.flatMap { it.archiveFile })
    systemProperty("memory.worker.jar", memoryWorkerJar.get().archiveFile.get().asFile.absolutePath)
    classpath += labRuntime.output + configurations[labRuntime.runtimeClasspathConfigurationName]
}
tasks.register("memoryWorkerManifest") {
    dependsOn(memoryWorkerJar)
    val jar = memoryWorkerJar.flatMap { it.archiveFile }
    val descriptor = layout.buildDirectory.file("libs/memory-worker-manifest.json")
    inputs.file(jar); outputs.file(descriptor)
    doLast {
        val artifact = jar.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n == -1) break; digest.update(buffer, 0, n) }
        }
        descriptor.get().asFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf(
            "schemaVersion" to 1, "protocolVersion" to 1, "moduleId" to "batch-excel",
            "experimentIds" to listOf("career.batch-excel.batch-memory", "career.batch-excel.excel-memory"),
            "javaMajor" to 21, "jarFile" to artifact.name,
            "artifactSha256" to HexFormat.of().formatHex(digest.digest()), "requiresDocker" to false
        ))) + "\n")
    }
}
