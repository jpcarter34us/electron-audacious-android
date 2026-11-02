plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jpcarter34us.audacious"
    compileSdk = 35
    // r23 is the last NDK that targets Android 4.1-4.3 (API 16-18).
    ndkVersion = "23.2.8568313"

    defaultConfig {
        applicationId = "io.github.jpcarter34us.audacious"
        minSdk = 16
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_ARM_NEON=TRUE")
                cppFlags += listOf("-std=c++17", "-fexceptions")
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK installs as is;
            // set up your own signing config to publish.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // 3.12.x is the last OkHttp that runs on Android 4.x (API 9+).
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
