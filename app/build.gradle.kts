plugins {
    id("com.android.application")
}

android {
    namespace = "com.carmaps"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.carmaps"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-only app: sign release with the debug key so it installs without extra setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}
