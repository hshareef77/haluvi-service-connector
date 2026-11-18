plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "mv.haluvi.serviceconnector"
    compileSdk = 35
    defaultConfig { applicationId = "mv.haluvi.serviceconnector"; minSdk = 26; targetSdk = 35; versionCode = 5; versionName = "1.2.1" }

    val keyStorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    if (!keyStorePath.isNullOrBlank()) {
        signingConfigs {
            create("permanentRelease") {
                storeFile = file(keyStorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") { signingConfig = signingConfigs.getByName("permanentRelease") }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
