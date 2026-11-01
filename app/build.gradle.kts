plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Local single-ABI gate (`-PciAbi=x86_64`, used by scripts/emutest.sh):
// splits are disabled so the build keeps emitting one plain app-debug.apk.
// Every other build ships one APK per ABI plus a universal fallback, since
// libonnxruntime.so alone is ~34 MB (arm64-v8a) / ~40 MB (x86_64).
val localAbi: String = (project.findProperty("ciAbi") as String?)?.trim().orEmpty()

// Issue #32 §1 — release signing.
// The keystore never enters the repository. CI decodes the
// CALQUE_KEYSTORE_BASE64 secret into $RUNNER_TEMP/calque.jks and exports
// CALQUE_KEYSTORE_FILE / CALQUE_KEYSTORE_PASSWORD / CALQUE_KEY_ALIAS /
// CALQUE_KEY_PASSWORD; a maintainer points the same variables at the local
// copy. Without them (forks, pull requests, plain `assembleDebug`) the
// release variant falls back to the debug key so builds stay installable
// and CI never fails for a stranger. See docs/release-signing.md.
val releaseKeystorePath: String =
    System.getenv("CALQUE_KEYSTORE_FILE")?.trim().orEmpty()
val releaseKeystorePassword: String =
    System.getenv("CALQUE_KEYSTORE_PASSWORD")?.trim().orEmpty()
val releaseKeystoreFile: java.io.File? = releaseKeystorePath.takeIf { it.isNotEmpty() }
    ?.let { file(it) }
val hasReleaseSigning: Boolean = releaseKeystoreFile?.isFile == true &&
    releaseKeystorePassword.isNotEmpty()

if (releaseKeystorePath.isNotEmpty() && !hasReleaseSigning) {
    logger.warn(
        "CALQUE_KEYSTORE_FILE is set ($releaseKeystorePath) but the file " +
            "or CALQUE_KEYSTORE_PASSWORD is unusable — the release variant " +
            "falls back to debug signing."
    )
}

android {
    namespace = "dev.openclip"
    // Compose 1.12 (BOM 2026.09.00) requires compileSdk 37. targetSdk stays
    // at 34 on purpose: this is a build-time bump only, no runtime
    // behaviour change for the device/emulator gate.
    compileSdk = 37
    // AGP 9.5 defaults to NDK 28.2; the shipped native contract (JNI
    // surface + engine build) was validated on r27, so keep it pinned
    // instead of silently moving the C++ toolchain with the plugin.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "dev.openclip"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.4.0-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Device builds keep arm64 phones + x86_64 emulators. When ABI
            // splits are enabled they drive the native ABI set as well; the
            // filters only matter for the unsplit local single-ABI gate
            // (scripts/emutest.sh, -PciAbi=x86_64).
            if (localAbi.isNotEmpty()) abiFilters += localAbi
            else abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    // Issue #2: publish one APK per ABI (~-35 MB each) and keep the
    // universal APK for anyone who does not care. Disabled for the local
    // gate so `app-debug.apk` keeps its name and path.
    splits {
        abi {
            isEnable = localAbi.isEmpty()
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        // Release APKs fall back to debug signing so headless CI and forks
        // without the keystore still produce installable builds.
        getByName("debug") {}
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = System.getenv("CALQUE_KEY_ALIAS")
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: "calque"
                keyPassword = System.getenv("CALQUE_KEY_PASSWORD")
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            // Rebrand P7: R8 + resource shrinking on the published APK.
            // Keep rules in app/proguard-rules.pro (JNI EngineHost +
            // ONNX Runtime); validated by scripts/check_jni_symbols.sh +
            // emulator boot + full suite after every rule change.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Issue #32 §1: the real key when the environment provides it
            // (CI tag build / maintainer), debug key otherwise.
            signingConfig = signingConfigs.getByName(
                if (hasReleaseSigning) "release" else "debug"
            )
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

    // Brand-aligned, stable artifact names instead of AGP's default
    // `app-<abi>-release.apk`: calque-<version>-<abi>.apk, plus
    // calque-<version>-universal.apk for the unsplit fallback. The local
    // single-ABI gate keeps AGP's default app-debug.apk on purpose:
    // scripts/emutest.sh installs it by that exact path.
    // AGP 9 removed the old `applicationVariants` hook; naming now goes
    // through the stable `androidComponents.onVariants` API.
    if (localAbi.isEmpty()) {
        androidComponents {
            onVariants { variant ->
                variant.outputs.forEach { output ->
                    val abi = output.filters
                        .firstOrNull {
                            it.filterType ==
                                com.android.build.api.variant.FilterConfiguration
                                    .FilterType.ABI
                        }
                        ?.identifier
                        ?: "universal"
                    output.outputFileName.set(
                        output.versionName.orElse("0")
                            .map { "calque-$it-$abi.apk" }
                    )
                }
            }
        }
    }
}

// Kotlin 2.4 (KGP 2.3+) removed the `android.kotlinOptions` block; the
// compiler options now live in the Kotlin extension.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
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
