plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlinx.serialization.plugin)
}

abstract class CopyChangelogsTask @Inject constructor(
	private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {

	@get:Input
	abstract val versionCode: Property<Int>

	@get:InputDirectory
	@get:PathSensitive(PathSensitivity.RELATIVE)
	abstract val metadataDir: DirectoryProperty

	@get:OutputDirectory
	abstract val destinationDirectory: DirectoryProperty

	@TaskAction
	fun action() {
		val vc = versionCode.get()
		fileSystemOperations.sync {
			from(metadataDir) {
				include("**/changelogs/$vc.txt")
			}
			into(destinationDirectory)
		}
	}
}

android {
	namespace = "com.ericlowry.dnstoggle"
	compileSdk = 37

	defaultConfig {
		applicationId = "com.ericlowry.dnstoggle"
		minSdk = 28
		targetSdk = 37
		versionCode = 26
		versionName = "2.1.3"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}
	}
	testOptions {
		unitTests {
			isIncludeAndroidResources = true
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
	buildFeatures {
		aidl = true
		buildConfig = true
	}
	dependenciesInfo {
		includeInApk = false
		includeInBundle = false
	}

	packaging {
		dex {
			useLegacyPackaging = true
		}
	}

	lint {
		disable.add("MissingTranslation")
	}
}

val copyChangelogs = tasks.register<CopyChangelogsTask>("copyChangelogs") {
	description =
		"Copies the current version's changelog files from fastlane metadata to generated assets"
	versionCode.set(android.defaultConfig.versionCode ?: 0)
	metadataDir.set(layout.projectDirectory.dir("../fastlane/metadata/android"))
}

androidComponents {
	onVariants { variant ->
		variant.sources.assets?.addGeneratedSourceDirectory(
			copyChangelogs,
			CopyChangelogsTask::destinationDirectory
		)
	}
}

dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.androidx.appcompat)
	implementation(libs.material)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.lifecycle.viewmodel.ktx)
	implementation(libs.shizuku.api)
	implementation(libs.shizuku.provider)
	implementation(libs.androidx.dynamicanimation)
	testImplementation(libs.junit)
	testImplementation(libs.robolectric)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.androidx.test.core)
	testImplementation(libs.androidx.arch.core.testing)
	androidTestImplementation(libs.androidx.test.ext.junit)
	androidTestImplementation(libs.androidx.test.runner)
}