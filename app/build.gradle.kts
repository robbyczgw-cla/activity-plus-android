plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "xyz.activityplus.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.activityplus.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.0"
    }

    // Release key from ~/.gradle/gradle.properties; without it the release build stays unsigned
    // (F-Droid builds and signs on its own).
    val storeFile = providers.gradleProperty("activityplus.storeFile").orNull
    signingConfigs {
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("activityplus.storePassword").get()
                keyAlias = providers.gradleProperty("activityplus.keyAlias").get()
                keyPassword = providers.gradleProperty("activityplus.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (storeFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // F-Droid and IzzyOnDroid reject the encrypted Play dependency metadata block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    androidResources {
        // The Mac app's eight languages; English is the default in values/.
        localeFilters += listOf("en", "de", "fr", "es", "it", "pt-rBR", "ja", "zh-rCN")
        // Lists the languages for Android 13's per-app language setting.
        generateLocaleConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Pro mode: runs three read-only system reports through Shizuku (github.com/RikkaApps/Shizuku).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests.
    testImplementation("org.json:json:20240303")
}
