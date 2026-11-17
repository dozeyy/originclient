pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		mavenCentral()
		gradlePluginPortal()
	}

	plugins {
		id("net.fabricmc.fabric-loom-remap") version providers.gradleProperty("loom_version")
	}
}

// Output jar: build/libs/vulkairis-origin-<version>.jar (archivesName in build.gradle.kts).
rootProject.name = "vulkairis"

// Origin's own Sodium stand-in (see sodium-compat/README.md). Both are nested into the VulkaIris jar.
include("sodium-compat")
include("sodium-provider")
