import java.util.Base64

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.moread.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moread.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.5.3"
    }

    signingConfigs {
        // 本地：从 keystore.properties 读（已 gitignore）
        // CI：从环境变量读（GitHub Secrets 注入）
        val ks = rootProject.file("keystore.properties")
        val envStore = System.getenv("KEYSTORE_BASE64")
        if (envStore != null) {
            // CI 模式：解码 base64 证书到临时文件
            val tmpKs = rootProject.file("build/tmp_keystore.jks")
            tmpKs.parentFile.mkdirs()
            tmpKs.writeBytes(Base64.getDecoder().decode(envStore))
            create("release") {
                storeFile = tmpKs
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        } else if (ks.exists()) {
            val props = Properties().apply { ks.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.juniversalchardet)
    implementation(libs.okhttp)
    implementation(libs.pdfbox.android)
    implementation(libs.junrar)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // JVM 单测使用真实 org.json（Android 桩会抛 not mocked）
    testImplementation(libs.org.json)
}
