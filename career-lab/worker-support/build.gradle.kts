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
    inputs.dir(rootProject.file("../contracts/worker/v1"))
    systemProperty("worker.contracts", rootProject.file("../contracts/worker/v1").absolutePath)
}
