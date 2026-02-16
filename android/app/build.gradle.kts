plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "moe.nullsoft.scan"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "moe.nullsoft.scan"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // 【新增 1】防止 OpenCV 架构冲突，只保留通用架构
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // 【新增 2】解决 OpenCV 重复库文件冲突
    packaging {
        resources {
            pickFirsts.add("lib/arm64-v8a/libopencv_java4.so")
            pickFirsts.add("lib/armeabi-v7a/libopencv_java4.so")
        }
    }
}

flutter {
    source = "../.."
}

// 【新增 3】核心修复：手动添加原生依赖
dependencies {
    // 强制引入 Google MLKit 中文 OCR 模型
    // 解决 java.lang.NoClassDefFoundError: ChineseTextRecognizerOptions$Builder
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
}