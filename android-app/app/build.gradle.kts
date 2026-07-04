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
        // targetSdk 30 (não 35) de propósito: o AAR do librealsense 2.54.2 tem
        // duas incompatibilidades com Android moderno, em portões de API distintos:
        //   - registra um BroadcastReceiver sem RECEIVER_EXPORTED/NOT_EXPORTED
        //     -> SecurityException em RsContext.init com targetSdk >= 34;
        //   - cria um PendingIntent sem FLAG_IMMUTABLE/MUTABLE ao pedir permissão
        //     USB (UsbUtilities.grantUsbPermissions) -> IllegalArgumentException
        //     ao conectar a câmera com targetSdk >= 31.
        // Ambos estão dentro do AAR (binário); ficar em targetSdk 30 evita os dois.
        // Correção definitiva: reconstruir o AAR de um librealsense mais novo.
        targetSdk = 30
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
