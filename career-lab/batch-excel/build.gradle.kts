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
