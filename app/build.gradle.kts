plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kar.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kar.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // The speech engine ships large native libraries; phones and tablets are ARM (64- and 32-bit).
        // CI's emulator test builds x86_64 instead (-PkarAbis=x86_64).
        val abis = providers.gradleProperty("karAbis").orNull?.split(',')?.map { it.trim() } ?: listOf("arm64-v8a", "armeabi-v7a")
        ndk { abiFilters += abis }
    }

    // Release signing comes only from environment variables set by the release workflow. No key or password is
    // ever committed; without them, release builds are produced unsigned.
    val releaseKeystore = System.getenv("KAR_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KAR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KAR_KEY_ALIAS")
                keyPassword = System.getenv("KAR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Code shrinking stays off until a shrunk build has been checked on a device: R8 can strip classes used
            // through reflection or JNI (serialization, Room, the speech engine) and that only shows at run time.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // "standard" is the offline build with no network permission at all.
    // "sync" adds direct Wi-Fi sync and therefore needs the INTERNET permission; it installs side by side.
    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
            isDefault = true
        }
        create("sync") {
            dimension = "distribution"
            applicationIdSuffix = ".sync"
            versionNameSuffix = "-sync"
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
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = false }
    }
    androidResources {
        // Speech models are read straight from the APK; storing them uncompressed avoids inflating ~200 MB at start.
        noCompress += listOf("onnx", "txt")
    }
    sourceSets {
        // Room's exported schemas, for the migration test (Robolectric reads the debug variant's assets).
        // Debug builds only; release APKs do not contain them.
        getByName("debug").assets.srcDir("$projectDir/schemas")
        // Test recordings for the on-device speech test (downloaded by scripts/fetch-speech-models.sh --test-audio).
        getByName("androidTest").assets.srcDir("$projectDir/build/test-audio")
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        disable += "GradleDependency"
        disable += "AndroidGradlePluginVersion"
        disable += "NewerVersionAvailable"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// APKs are named "KAR-READER-<version>-<buildType>.apk".
base {
    archivesName.set("KAR-READER-${android.defaultConfig.versionName}")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.navsuite)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.documentfile)
    implementation(libs.mlkit.text.recognition)
    // On-device speech recognition and speaker diarization (sherpa-onnx, Apache-2.0). The AAR is downloaded by
    // scripts/fetch-speech-models.sh, pinned by version and checksum; it is not committed.
    implementation(files("libs/sherpa-onnx-${libs.versions.sherpaOnnx.get()}.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
