import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing (PRD §14). The keystore and its passwords never enter the repo: they are read from
// a properties file the owner keeps, ~/.home-inspection/release-signing.properties (or -Phome-inspection.signing=<path>):
//   storeFile=/absolute/path/home-inspection-release.jks
//   storePassword=…
//   keyAlias=home-inspection
//   keyPassword=…
// Without it, release builds are debug-signed for local testing only — never distribute those:
// a phone that installed one can't update to a properly signed build without losing its data.
val releaseSigning = Properties().apply {
    val path = providers.gradleProperty("home-inspection.signing").orNull ?: "${System.getProperty("user.home")}/.home-inspection/release-signing.properties"
    file(path).takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.homeinspection.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.homeinspection.app"
        minSdk = 26
        targetSdk = 37
        // Bump versionCode for every APK handed out; Android only installs updates with a higher one.
        versionCode = 11
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", "\"https://api.homeinspection.example\"")
        // OpenCV (face check-in) is native code: ship only the ARM builds real phones use (no emulator in use).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (!releaseSigning.isEmpty) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release app ("Home Inspection (debug)", own data), so testing never touches real work.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseSigning.isEmpty) {
                logger.warn("Home Inspection: no release signing properties found; release APK is DEBUG-signed (local testing only).")
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Only English resources from libraries; trims the APK.
    androidResources {
        localeFilters += listOf("en")
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "/kotlin/**")
    }


    // Exported Room schemas, so MigrationTestHelper can build the old versions in tests.
    sourceSets.getByName("androidTest") { assets.srcDir("$projectDir/schemas") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)

    implementation(libs.coil.compose)
    implementation(libs.opencv)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
}
