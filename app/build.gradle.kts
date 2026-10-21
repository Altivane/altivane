plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.altivane.app"
    compileSdk = 36
    // build-tools 沿用已装的 34.0.0（AGP 8.9 默认要 35，离线环境显式钉住免下载）
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.altivane.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 51
        versionName = "0.8.42"
        // 只留 arm64：so 体积主因（华为机 arm64）
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("altivane-release.keystore")
            storePassword = "altivane2026"
            keyAlias = "altivane"
            keyPassword = "altivane2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes.addAll(listOf("META-INF/AL2.0", "META-INF/LGPL2.1"))
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    // MapLibre 开源矢量地图（v0.8.28 起替代高德渲染/离线；OpenFreeMap 免瓦片源）
    // v0.8.29: 换 OpenGL 渲染后端（默认 Vulkan 变体实机黑屏——瓦片纹理不上屏）
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    testImplementation("junit:junit:4.13.2")
}
