import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "no.prosjektkamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.prosjektkamera"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
    }

    // Release signing key lives outside the repo. Point ONSITE_SIGNING at a properties
    // file (storeFile, storePassword, keyAlias, keyPassword); defaults to ~/.android-keys.
    val signingProps = File(
        System.getenv("ONSITE_SIGNING") ?: "${System.getProperty("user.home")}/.android-keys/onsite-release.properties"
    )
    signingConfigs {
        if (signingProps.exists()) {
            create("release") {
                val p = Properties().apply { signingProps.inputStream().use { load(it) } }
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val camerax = "1.4.2"
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // 16 KB page-size aligned native lib (older version pulled in by Compose is not)
    implementation("androidx.graphics:graphics-path:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
