plugins {
    id("com.android.application")
}

android {
    namespace = "no.politiet.pit"
    compileSdk = 36

    defaultConfig {
        applicationId = "no.politiet.pit"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }
}
