plugins {
	alias(libs.plugins.android.application)
}

android {
	namespace = "com.ericlowry.dnstoggle"
	compileSdk = 37

	defaultConfig {
		applicationId = "com.ericlowry.dnstoggle"
		minSdk = 28
		targetSdk = 36
		versionCode = 13
		versionName = "1.4.2"
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
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
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
}

dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.appcompat)
	implementation(libs.material)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.lifecycle.viewmodel.ktx)
}