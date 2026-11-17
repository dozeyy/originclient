// Probe scene: a dev-only Fabric mod for vulkan-probe comparisons (OpenGL vs Vulkan, same spot).
// Built with the VulkaIris fork's wrapper: src/mods/vendor/vulkairis/gradlew.bat -p tools/vulkan-probe/scene build
pluginManagement {
	repositories {
		maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
		mavenCentral()
		gradlePluginPortal()
	}
	plugins { id("net.fabricmc.fabric-loom-remap") version providers.gradleProperty("loom_version") }
}
rootProject.name = "probe-scene"
