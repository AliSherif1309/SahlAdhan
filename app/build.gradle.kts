plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.sahl.adhan"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.sahl.adhan"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    // Fixed key so every new build installs over the old one
    signingConfigs {
        create("sahl") {
            storeFile = file("sahl.keystore")
            storePassword = "sahl1234"
            keyAlias = "sahl"
            keyPassword = "sahl1234"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("sahl") }
        getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("sahl") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
