import java.io.File

fun msdkKey(): String {
    val f = File(System.getProperty("user.home"), ".config/dji/api_key")
    if (!f.exists()) return ""
    return f.readLines().firstOrNull { it.trim().startsWith("msdk_app_key") }
        ?.substringAfter('=')?.trim()?.trim('\'', '"') ?: ""
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.grizzly.droneroute"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.grizzly.droneroute"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "1.0"
    }

    flavorDimensions += "target"
    productFlavors {
        create("controller") {
            dimension = "target"
            ndk { abiFilters += listOf("arm64-v8a") }
            manifestPlaceholders["DJI_MSDK_KEY"] = msdkKey()
        }
        create("emu") {
            dimension = "target"
            ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
            manifestPlaceholders["DJI_MSDK_KEY"] = ""
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("lib/arm64-v8a/libc++_shared.so", "lib/armeabi-v7a/libc++_shared.so")

            keepDebugSymbols += listOf("*/*/libconstants.so", "*/*/libdji_innertools.so", "*/*/libdjibase.so",
                "*/*/libDJICSDKCommon.so", "*/*/libDJIFlySafeCore-CSDK.so", "*/*/libdjifs_jni-CSDK.so",
                "*/*/libDJIRegister.so", "*/*/libdjisdk_jni.so", "*/*/libDJIUpgradeCore.so", "*/*/libDJIUpgradeJNI.so",
                "*/*/libDJIWaypointV2Core-CSDK.so", "*/*/libdjiwpv2-CSDK.so", "*/*/libFlightRecordEngine.so",
                "*/*/libvideo-framing.so", "*/*/libwaes.so", "*/*/libagora-rtsa-sdk.so", "*/*/libc++.so",
                "*/*/libc++_shared.so", "*/*/libmrtc_28181.so", "*/*/libmrtc_agora.so", "*/*/libmrtc_core.so",
                "*/*/libmrtc_core_jni.so", "*/*/libmrtc_data.so", "*/*/libmrtc_log.so", "*/*/libmrtc_onvif.so",
                "*/*/libmrtc_rtmp.so", "*/*/libmrtc_rtsp.so")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    "controllerImplementation"("com.dji:dji-sdk-v5-aircraft:5.18.0")
    "controllerCompileOnly"("com.dji:dji-sdk-v5-aircraft-provided:5.18.0")
    "controllerRuntimeOnly"("com.dji:dji-sdk-v5-networkImp:5.18.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
