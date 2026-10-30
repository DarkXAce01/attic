plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.darkxace01.attic"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.darkxace01.attic"
        // Android 10: scoped storage and MediaStore RELATIVE_PATH for saving prints to the gallery.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-spike"

        ndk {
            // OpenCV ships native code for every ABI (~120 MB). Phones are arm64; the emulator is x86_64.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            it.maxHeapSize = "1g"
            // The detector benchmark is heavy; run it on purpose with ./gradlew testDebugUnitTest -Pbenchmark
            it.systemProperty("attic.benchmark", project.hasProperty("benchmark").toString())
            it.systemProperty("attic.seed", (project.findProperty("seed") ?: "1000").toString())
            project.findProperty("debugScene")?.let { s -> it.systemProperty("attic.debugScene", s.toString()) }
            project.findProperty("explain")?.let { s -> it.systemProperty("attic.explain", s.toString()) }
            project.findProperty("zoom")?.let { s -> it.systemProperty("attic.zoom", s.toString()) }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.opencv)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Geometry and tracking are plain Kotlin, so they're tested on the computer.
    testImplementation(libs.junit)
    // Desktop build of OpenCV so the detector runs in unit tests on synthetic album pages.
    testImplementation(libs.opencv.desktop)
}
