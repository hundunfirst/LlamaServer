plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 正式签名（keystore.properties 不入库；缺失时回退 debug）
import java.io.File
import java.util.Properties
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val ksFile = ksProps.getProperty("storeFile")?.let { File(it) }
val hasReleaseKs = ksFile?.exists() == true

android {
    namespace = "com.localllm.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.localllm.server"
        minSdk = 31
        // targetSdk 必须保持 28：untrusted_app_34（target≥34）被 SELinux 禁止
        // execve app 数据目录的文件（execute_no_trans denied），llama-server 子进程方案依赖此执行权；
        // untrusted_app_28 仍允许，Termux（targetSdk=28）在 Android 17 上同样依赖该域。
        // 若未来改用 JNI/fork 进程内方案，可再提升 targetSdk。
        targetSdk = 28
        versionCode = 4
        versionName = "0.3.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseKs) {
            create("release") {
                storeFile = ksFile
                storeType = ksProps.getProperty("storeType", "PKCS12")
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKs) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // 必须解压 .so：llama-server 以子进程方式 exec 这些库
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.documentfile:documentfile:1.0.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // OCR：ML Kit 中文文字识别（bundled 离线模型，无需 GMS 在线下载）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
