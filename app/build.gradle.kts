plugins {
	alias(libs.plugins.android.application)
}

android {
	namespace = "com.ericlowry.dnstoggle"
	compileSdk {
		version = release(36) {
			minorApiLevel = 1
		}
	}

	defaultConfig {
		applicationId = "com.ericlowry.dnstoggle"
		minSdk = 28
		targetSdk = 36
		versionCode = 6
		versionName = "1.0.6"
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro"
			)
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
	dependenciesInfo {
		includeInApk = false
		includeInBundle = false
	}
}

dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.appcompat)
	implementation(libs.material)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.constraintlayout)
}