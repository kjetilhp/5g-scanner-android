plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.fivegscanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.fivegscanner"
        minSdk = 29
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

dependencies {
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
}
