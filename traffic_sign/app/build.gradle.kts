plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.traffic_sign"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.traffic_sign"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(project(":opencv"))
    implementation(libs.litert)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("org.bytedeco:tensorflow-lite:2.18.0-1.5.11")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Google Maps
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    // Location (GPS)
    implementation("com.google.android.gms:play-services-location:21.0.1")
    // NanoHTTPD cho máy chủ web cục bộ
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // Gson để xử lý JSON
    implementation("com.google.code.gson:gson:2.10.1")
}