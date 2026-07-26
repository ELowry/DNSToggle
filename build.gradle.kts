// Top-level build file where you can add configuration options common to all subprojects/modules.
plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.kotlinx.serialization.plugin) apply false
}

tasks.register<Exec>("setupGitHooks") {
	group = "help"
	description = "Configures the local Git repository to use the shared .githooks folder."

	commandLine("git", "config", "core.hooksPath", ".githooks")

	isIgnoreExitValue = true
}

tasks.register("checkFastlaneMetadataLengths") {
	group = "verification"
	description = "Checks Fastlane metadata text files against character limits."

	doLast {
		val baseDir = file("$rootDir/fastlane/metadata/android")
		if (!baseDir.exists()) {
			logger.info("Fastlane metadata directory not found. Skipping.")
			return@doLast
		}

		var failed = false

		baseDir.listFiles()?.filter { it.isDirectory }?.forEach { localeDir ->
			val titleFile = File(localeDir, "title.txt")
			if (titleFile.exists() && titleFile.readText().length > 50) {
				logger.error("${titleFile.path} exceeds 50 characters.")
				failed = true
			}

			val shortDescFile = File(localeDir, "short_description.txt")
			if (shortDescFile.exists() && shortDescFile.readText().length > 80) {
				logger.error("${shortDescFile.path} exceeds 80 characters.")
				failed = true
			}

			val fullDescFile = File(localeDir, "full_description.txt")
			if (fullDescFile.exists() && fullDescFile.readText().length > 3700) {
				logger.error("${fullDescFile.path} exceeds 3700 characters.")
				failed = true
			}

			val changelogsDir = File(localeDir, "changelogs")
			if (changelogsDir.exists()) {
				changelogsDir.listFiles { f -> f.extension == "txt" }?.forEach { changelog ->
					if (changelog.readText().length > 500) {
						logger.error("${changelog.path} exceeds 500 characters.")
						failed = true
					}
				}
			}
		}

		if (failed) {
			throw GradleException("Fastlane metadata character limits exceeded.")
		}
	}
}

tasks.register<Delete>("clean") {
	group = "build"
	description = "Deletes the build directory and runs verification tasks."
	delete(rootProject.layout.buildDirectory)
	dependsOn("checkFastlaneMetadataLengths", "setupGitHooks")
}

allprojects {
	tasks.whenTaskAdded {
		if (name == "preBuild") {
			dependsOn(":setupGitHooks")
			dependsOn(":checkFastlaneMetadataLengths")
		}
	}
}