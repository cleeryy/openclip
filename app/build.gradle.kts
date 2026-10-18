plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.openclip"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.openclip"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.0-alpha.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Device builds keep arm64 phones + x86_64 emulators. The local
            // emulator gate (scripts/emutest.sh) narrows to x86_64 with
            // -PciAbi=x86_64 to halve the native build time.
            val ciAbi = (project.findProperty("ciAbi") as String?)?.trim().orEmpty()
            if (ciAbi.isNotEmpty()) abiFilters += ciAbi
            else abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    signingConfigs {
        // Release APKs are debug-signed so headless CI produces installable builds.
        getByName("debug") {}
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    val testRunnerVersion = "1.6.1"
    // M8: ONNX Runtime (MIT) — app-side AI tools (super-resolution).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    androidTestImplementation("androidx.test:runner:$testRunnerVersion")
    androidTestImplementation("androidx.test:rules:$testRunnerVersion")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestUtil("androidx.test:orchestrator:1.5.0")
}
