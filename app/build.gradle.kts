import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.github.voxqon.xposed.autoflow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.github.voxqon.xposed.autoflow"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "com.github.voxqon.xposed.autoflow.ConnectionCatalogDeviceVerification"
        resourceConfigurations += listOf("en", "zh")
    }

    val signingPropertiesPath = providers.environmentVariable("AUTOFLOW_SIGNING_PROPERTIES").orNull
    val signingStorePath = providers.environmentVariable("AUTOFLOW_SIGNING_STORE").orNull
    require((signingPropertiesPath == null) == (signingStorePath == null)) {
        "Set both AUTOFLOW_SIGNING_PROPERTIES and AUTOFLOW_SIGNING_STORE, or neither."
    }
    if (signingPropertiesPath != null && signingStorePath != null) {
        signingConfigs.create("release") {
            val signingProps = Properties().apply {
                FileInputStream(rootProject.file(signingPropertiesPath)).use { load(it) }
            }
            storeFile = rootProject.file(signingStorePath)
            storePassword = requireNotNull(signingProps.getProperty("STORE_PASSWORD"))
            keyAlias = requireNotNull(signingProps.getProperty("KEY_ALIAS"))
            keyPassword = requireNotNull(signingProps.getProperty("KEY_PASSWORD"))
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's Android 14 file-descriptor bridge needs access on JDK 17.
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
        }
    }
}

dependencies {
    // 传统 Xposed API：仅编译期，运行时由 LSPosed 提供
    compileOnly("de.robv.android.xposed:api:82")

    // 模块 App 自身 UI（Hook 侧不依赖这些库，避免注入到系统进程的代码引用不存在的类）
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
