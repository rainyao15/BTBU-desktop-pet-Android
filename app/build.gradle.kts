plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dshpet.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dshpet.android"
        minSdk = 28
        targetSdk = 34
        versionCode = 31
        versionName = "1.9.11-btbu"
    }

    // 固定签名：CI 每次都用同一个 keystore，这样后续版本能直接覆盖安装
    // （Gradle 默认的 debug keystore 是每台机器/每次 runner 现生成的，签名不一致 → 装不上）
    signingConfigs {
        create("btbu") {
            storeFile = file("../keystore/btbu-debug.jks")
            storePassword = "btbu2026"
            keyAlias = "btbu"
            keyPassword = "btbu2026"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("btbu")
        }
        release {
            isMinifyEnabled = false
            // 用同一个固定签名，保证任何机器上都能直接安装、且能覆盖升级
            signingConfig = signingConfigs.getByName("btbu")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // 动画 WebP 已经压过，构建时不要再二次处理
        noCompress += listOf("webp")
    }
}

dependencies {
    // 刻意零第三方依赖：只用 Android 框架 API（minSdk 28 足够）
}






