// Origin fork of VulkaIris. Upstream: Potatochip-p/vulkairis @ 0e9d08f (UPSTREAM.md lists our changes).
// Output: build/libs/vulkairis-origin-<version>.jar, with sodium-compat nested.
plugins {
	id("net.fabricmc.fabric-loom-remap")
}

base.archivesName = "vulkairis-origin"

repositories {
	exclusiveContent {
		forRepository {
			maven {
				name = "Modrinth"
				url = uri("https://api.modrinth.com/maven")
			}
		}
		filter {
			includeGroup("maven.modrinth")
		}
	}
	mavenCentral()
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("vulkairis") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
		}
	}
}

fabricApi {
	configureDataGeneration {
		client = true
	}
}

val lwjglVersion = "3.3.3"

dependencies {
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")

	// Iris and VulkanMod come from Modrinth by version id. Upstream committed these jars (plus the whole
	// Sodium 0.8.12 jar, PolyForm Shield) into LIBS/; Origin's fork commits no third-party jars.
	modImplementation("maven.modrinth:iris:${providers.gradleProperty("iris_modrinth").get()}")
	modImplementation("maven.modrinth:vulkanmod:${providers.gradleProperty("vulkanmod_modrinth").get()}")
	modCompileOnly("maven.modrinth:modmenu:${providers.gradleProperty("modmenu_modrinth").get()}")
	// Upstream tests build the ModMenu screen factory; dev/test runtime only, never nested or required.
	modLocalRuntime("maven.modrinth:modmenu:${providers.gradleProperty("modmenu_modrinth").get()}")

	// The Sodium types Iris links against: Origin's own stand-in, compiled against here (so javac proves
	// VulkaIris uses nothing the stand-in lacks) and nested into the jar. No Sodium jar anywhere.
	implementation(project(path = ":sodium-compat", configuration = "namedElements"))
	include(project(":sodium-compat"))

	// Iris's own nested libraries (Loom does not put a mod's nested jars on the dev classpath).
	implementation("io.github.douira:glsl-transformer:3.0.0-pre3")
	implementation("org.anarres:jcpp:1.4.14")
	implementation("org.antlr:antlr4-runtime:4.13.1")

	// GLSL -> SPIR-V (shaderc) and Vulkan bindings. VulkanMod nests the natives for players; these are
	// for compiling and for the unit tests.
	implementation("org.lwjgl:lwjgl-shaderc:$lwjglVersion")
	implementation("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
	implementation("org.lwjgl:lwjgl-vma:$lwjglVersion")
	for (natives in listOf("natives-windows", "natives-linux")) {
		testRuntimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$natives")
		testRuntimeOnly("org.lwjgl:lwjgl-shaderc:$lwjglVersion:$natives")
	}

	testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets.test {
	// Upstream tests reach client classes (translation, render graph planning).
	compileClasspath += sourceSets.getByName("client").output + sourceSets.getByName("client").compileClasspath
	runtimeClasspath += sourceSets.getByName("client").output + sourceSets.getByName("client").runtimeClasspath
}

tasks.test {
	useJUnitPlatform()
}

tasks.matching { it.name == "downloadAssets" }.configureEach {
	enabled = false
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

java {
	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
	from("LICENSE")
	from("UPSTREAM.md")
}
