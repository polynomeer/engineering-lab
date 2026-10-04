import java.util.Properties

rootProject.name = "career-lab"

// Only explicitly declared, checked-in modules participate in this build.
val moduleDirectories = rootDir.listFiles().orEmpty()
    .filter { it.isDirectory && it.resolve("lab-module.properties").isFile }
    .sortedBy { it.name }
require(moduleDirectories.isNotEmpty()) { "No declared Career Lab modules found" }

moduleDirectories.forEach { directory ->
    val metadata = Properties().apply {
        directory.resolve("lab-module.properties").reader(Charsets.UTF_8).use { load(it) }
    }
    val required = setOf("schemaVersion", "id", "title", "collection")
    require(metadata.stringPropertyNames() == required) {
        "Invalid module metadata fields: ${directory.name} (expected $required)"
    }
    require(metadata.getProperty("schemaVersion") == "1") {
        "Unsupported module schema: ${directory.name}"
    }
    val id = metadata.getProperty("id")
    require(id.matches(Regex("[a-z][a-z0-9-]{0,63}")) && id == directory.name) {
        "Module id must match its directory name: ${directory.name}"
    }
    require(metadata.getProperty("title").isNotBlank() && metadata.getProperty("title").length <= 200
            && metadata.getProperty("collection").matches(Regex("[a-z][a-z0-9-]{0,49}"))) {
        "Invalid module title or collection: $id"
    }
    require(directory.resolve("build.gradle.kts").isFile || directory.resolve("build.gradle").isFile) {
        "Declared module has no Gradle build: $id"
    }
    include(id)
}
