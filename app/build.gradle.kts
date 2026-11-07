plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.botdue.nearby"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.botdue.nearby"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 에뮬레이터에서 내 PC의 중계 서버 주소 (10.0.2.2 = 호스트 PC)
        buildConfigField("String", "HUB_URL", "\"ws://10.0.2.2:8080/nearby\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 심사용: 별도 키 없이 설치되도록 디버그 키로 서명
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
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
