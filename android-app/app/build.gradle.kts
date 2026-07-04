plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.senai.realsensemapper"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.senai.realsensemapper"
        minSdk = 26
        // targetSdk 33 (não 35) de propósito: o AAR do librealsense 2.54.2
        // registra um BroadcastReceiver interno (detecção de plug/unplug USB)
        // sem a flag RECEIVER_EXPORTED/NOT_EXPORTED. Com targetSdk >= 34 o
        // Android 14 exige essa flag e lança SecurityException em RsContext.init.
        // Como o receiver está dentro do AAR (binário), a saída é ficar em 33.
        targetSdk = 33
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(files("libs/librealsense.aar"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
}
