import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.novelreader"
    compileSdk = 35

    // release 签名密钥：项目根 keystore.properties（与 keystore/ 一起被 .gitignore）
    // 缺失时退化为不签名（CI/他人克隆不会因此构建失败）。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) {
            FileInputStream(keystorePropsFile).use { load(it) }
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.novelreader"
        minSdk = 24
        targetSdk = 35
        // 版本规范：四段式 顶端版本.大版本.小版本.修订
        // 小版本每版 +1；小版本满 10 进大版本；大版本满 10 进顶端版本
        // versionCode = 顶端版本 * 1000000 + 大版本 * 10000 + 小版本 * 100 + 修订
        versionCode = 1080106
        versionName = "1.8.1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // v1.8.1.4：ONNX Runtime 的 native 库很大（每个 ABI 约 18MB），四合一包会到 88MB。
    // 按 ABI 拆分：真机用户装 arm64 包（~40MB），模拟器用 x86_64 包；
    // universalApk 兜底（含 v7a + arm64 + x86_64，给 32 位老设备），发版默认上传 arm64 包。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 本机单元测试：允许 Robolectric 读取 Android 资源
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // ---- Compose 核心 ----
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // ---- 生命周期 / Activity ----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // ---- 导航 ----
    implementation("androidx.navigation:navigation-compose:2.8.2")

    // ---- 网络 ----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // ---- 协程 ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- JS 引擎（Rhino，兼容 legado 阅读源）----
    implementation("org.mozilla:rhino:1.7.15")

    // ---- HTML 解析 ----
    implementation("org.jsoup:jsoup:1.18.1")

    // ---- 数据库 ----
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // ---- 图片加载 ----
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ---- 端侧推理（v1.8.1.4 封面视觉打标：WD14 tagger ONNX）----
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // ---- 视频播放 ----
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // HLS（m3u8）流播放必需：ExoPlayer 播放 .m3u8 时通过反射加载
    // androidx.media3.exoplayer.hls.HlsMediaSource$Factory，缺此模块会
    // ClassNotFoundException 直接闪退（采集站视频几乎都是 HLS 流）。
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")

    // ---- DataStore 偏好设置 ----
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---- 测试 ----
    testImplementation("junit:junit:4.13.2")
    // 本机 Compose UI 测试（Robolectric，无需模拟器/真机）
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// 真实源测试（LegadoRealSourceTest / SourceHealthProbeTest / SourceTocDiagTest）靠环境变量门控。
// 环境变量在部分场景下传不进测试 worker，因此同时支持 `-PKEY=VALUE` 项目属性。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    listOf(
        "LEIMAO_REAL_TESTS",
        "LEIMAO_HEALTH_FILE",
        "LEIMAO_HEALTH_LIMIT",
        "LEIMAO_HEALTH_CONCURRENCY",
        "LEIMAO_HEALTH_KEYWORD",
        "LEIMAO_HEALTH_OUT",
        "LEIMAO_DIAG",
        "LEIMAO_DIAG_FILE",
        "LEIMAO_DIAG_ONLY",
        "LEIMAO_DIAG_KEYWORD",
        "LEIMAO_DIAG_DIR",
        "LEIMAO_DIAG_BOOKS",
        "LEIMAO_SOURCE_FILE",
        "LEIMAO_SRC_NOVEL_A",
        "LEIMAO_SRC_NOVEL_B",
        "LEIMAO_SRC_NOVEL_C",
    ).forEach { key ->
        val value = providers.gradleProperty(key).orNull ?: System.getenv(key)
        if (value != null) environment(key, value)
    }
}
