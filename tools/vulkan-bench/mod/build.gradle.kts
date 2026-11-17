plugins { id("net.fabricmc.fabric-loom-remap") }
base.archivesName = "vk-bench"
version = "0.1.0"
dependencies {
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
}
java { toolchain.languageVersion = JavaLanguageVersion.of(21) }
