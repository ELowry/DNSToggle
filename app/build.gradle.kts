plugins {
	alias(libs.plugins.android.application)
}

android {
	namespace = "com.ericlowry.dnstoggle"
	compileSdk = 37

	defaultConfig {
		applicationId = "com.ericlowry.dnstoggle"
		minSdk = 28
		targetSdk = 37
		versionCode = 16
		versionName = "1.5.2"
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
}

dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.appcompat)
	implementation(libs.material)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.lifecycle.viewmodel.ktx)
	implementation(libs.shizuku.api)
	implementation(libs.shizuku.provider)
	testImplementation(libs.junit)
	testImplementation(libs.robolectric)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.androidx.test.core)
}