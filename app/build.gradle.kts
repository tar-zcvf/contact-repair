import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.contactrepair.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.contactrepair.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            // debug also signs with the upload key so the APK is installable/unattended
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

kotlin {
    jvmToolchain(17)
}

// Load upload key credentials from secrets/keystore.properties
val keystorePropertiesFile = rootProject.file("secrets/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

android {
    signingConfigs {
        create("upload") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile", "secrets/upload-keystore.jks"))
            storePassword = keystoreProperties.getProperty("storePassword", "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "upload")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("upload")
        }
        debug {
            signingConfig = signingConfigs.getByName("upload")
        }
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
