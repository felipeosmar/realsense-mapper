# android-app — RealSense Mapper

App de captura para RealSense D435i via USB OTG: preview ao vivo (depth + RGB) e gravação de streams brutos em `.bag` (depth, color, IMU) para reconstrução posterior no `pc-pipeline/`.

## Requisitos

- **JDK 17–21**: O build com Gradle requer uma dessas versões. JDK 25 EA e posteriores quebram a compatibilidade com Kotlin 2.0.20.
- `local.properties` com `sdk.dir` apontando para o Android SDK.
- AAR do librealsense em `app/libs/librealsense.aar` (ver plano da Task 2).

## Build

    ./gradlew :app:assembleDebug        # APK em app/build/outputs/apk/debug/
    ./gradlew :app:installDebug         # instala via adb
    ./gradlew :app:testDebugUnitTest    # testes JVM

## Perfis de captura

| Porta | Depth | Color | FPS |
|---|---|---|---|
| USB 3.x | 848×480 | 1280×720 | 30 |
| USB 2.0 (fallback) | 640×480 | 640×480 | 15 |

IMU sempre: gyro 200 Hz, accel 250 Hz. ~1–2 GB por minuto em USB 3.

## Onde ficam os scans

`Android/data/br.senai.realsensemapper/files/scans/` — acessível por MTP ao plugar o celular no PC, ou pelo botão Compartilhar na tela de Scans.

Roteiro de validação com hardware: `../docs/roteiro-teste-app.md`.
