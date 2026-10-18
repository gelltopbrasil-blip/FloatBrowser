plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Versão do GeckoView vem do gradle.properties (fixe-a depois do primeiro build; veja o README)
val geckoVersion = (project.findProperty("geckoVersion") as String?) ?: "[128.0,134.0)"

android {
    namespace = "com.floatbrowser.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.floatbrowser.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }

    // Assinatura de release opcional via variáveis de ambiente; sem elas usa a chave debug
    val hasKs = System.getenv("FB_KEYSTORE") != null
    signingConfigs {
        if (hasKs) create("release") {
            storeFile = file(System.getenv("FB_KEYSTORE"))
            storePassword = System.getenv("FB_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FB_KEY_ALIAS")
            keyPassword = System.getenv("FB_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false   // GeckoView usa JNI; ligue só depois de testar regras do R8
            signingConfig = signingConfigs.getByName(if (hasKs) "release" else "debug")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.mozilla.geckoview:geckoview:$geckoVersion")
    testImplementation("junit:junit:4.13.2")
}
