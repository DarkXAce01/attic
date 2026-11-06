import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "io.github.darkxace01.attic"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.darkxace01.attic"
        // Android 10: scoped storage and MediaStore RELATIVE_PATH for saving prints to the gallery.
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.0"

        ndk {
            // OpenCV ships native code for every ABI (~120 MB together). Most phones are arm64; some
            // budget Android 10 phones are 32-bit ARM; the emulator is x86_64. Google Play splits the
            // bundle so each phone downloads only its own, and Android Studio's Run builds only the
            // connected phone's. -Pabi=arm64-v8a builds just one, for an APK to install by hand.
            abiFilters += (project.findProperty("abi") as String?)?.split(",") ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Release signing reads keystore.properties at the project root (never committed). Without it,
    // release builds are simply unsigned, so anyone can still build from source.
    val keystoreFile = rootProject.file("keystore.properties")
    val releaseSigning = if (keystoreFile.exists()) {
        val keys = Properties().apply { keystoreFile.inputStream().use(::load) }
        signingConfigs.create("release") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Screens are rendered on the computer (Robolectric + Roborazzi) to check the design.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "1536m"
            // The print-finder benchmark is heavy; run it on purpose with ./gradlew testDebugUnitTest -Pbenchmark
            it.systemProperty("attic.benchmark", project.hasProperty("benchmark").toString())
            it.systemProperty("attic.seed", (project.findProperty("seed") ?: "1000").toString())
            project.findProperty("explain")?.let { s -> it.systemProperty("attic.explain", s.toString()) }
            project.findProperty("dustLog")?.let { s -> it.systemProperty("dust.log", s.toString()) }
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
    implementation(libs.androidx.documentfile)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Geometry and tracking are plain Kotlin, so they're tested on the computer.
    testImplementation(libs.junit)
    // Desktop build of OpenCV so the detector runs in unit tests on synthetic album pages.
    testImplementation(libs.opencv.desktop)
    // Screenshots of screens with made-up albums, to look at the design: ./gradlew recordRoborazziDebug
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
