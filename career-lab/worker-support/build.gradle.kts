plugins { `java-library` }
group = "com.pnomeer.lab"
version = "0.1.0"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories { mavenCentral() }
dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.networknt:json-schema-validator:1.5.8")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    inputs.dir(project.file("../../contracts/worker/v1"))
    systemProperty("worker.contracts", project.file("../../contracts/worker/v1").absolutePath)
}

val processFixtureJar by tasks.registering(Jar::class) {
    dependsOn(tasks.testClasses)
    archiveClassifier.set("process-fixture")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.pnomeer.lab.worker.ProcessFixture" }
    from(sourceSets.test.get().output)
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.test {
    dependsOn(processFixtureJar)
    systemProperty("worker.fixture.jar", processFixtureJar.get().archiveFile.get().asFile.absolutePath)
}
