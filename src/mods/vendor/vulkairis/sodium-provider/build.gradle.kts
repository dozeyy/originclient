// A metadata-only mod that provides the "sodium" id at version 0.8.0 so Iris's "sodium 0.8.x"
// dependency resolves. Split from sodium-compat on purpose: if the launcher's
// -Dfabric.debug.disableModIds=sodium also drops mods that PROVIDE sodium, only this marker goes and
// the classes stay; the launcher then removes Iris's sodium dependency with a loader dependency
// override instead. 0.8.0 (not 0.8.13) keeps Origin's version-gated Sodium hooks switched off.
plugins {
	java
}

version = "0.8.0+vulkairis.compat.1"
base.archivesName = "vulkairis-sodium-provider"

tasks.processResources {
	val version = project.version
	inputs.property("version", version)
	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.jar {
	from(rootProject.file("LICENSE"))
}
