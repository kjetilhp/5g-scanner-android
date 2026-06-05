plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

val releaseNotesAssetDir = layout.buildDirectory.dir("generated/releaseNotes/assets").get().asFile

android {
    namespace = "no.politiet.pit"
    compileSdk = 36

    defaultConfig {
        applicationId = "no.politiet.pit.fivegscanner"
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

    sourceSets {
        getByName("main") {
            assets.srcDir(releaseNotesAssetDir)
        }
    }
}

val syncReleaseNotesAsset by tasks.registering(Copy::class) {
    from(rootProject.file("RELEASE_NOTES.md"))
    into(releaseNotesAssetDir)
}

tasks.named("preBuild") {
    dependsOn(syncReleaseNotesAsset)
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("junit:junit:4.13.2")
}
