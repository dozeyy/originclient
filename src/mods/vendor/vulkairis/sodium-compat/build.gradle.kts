// Origin's minimal stand-in for the Sodium API classes Iris 1.8.14-beta.1 links against.
// Nested into the VulkaIris jar; its own mod id, no "provides" (that lives in sodium-provider).
plugins {
	id("net.fabricmc.fabric-loom-remap")
}

version = "1.0.0"
base.archivesName = "vulkairis-sodium-compat"

dependencies {
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
}

tasks.processResources {
	val version = project.version
	inputs.property("version", version)
	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 21
}

tasks.jar {
	from(rootProject.file("LICENSE"))
}
