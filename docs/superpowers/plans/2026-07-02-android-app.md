# Android App (RealSense Mapper) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App Android (Kotlin) que conecta na RealSense D435i via USB-C, mostra preview ao vivo (depth colorizado + RGB) e grava streams brutos (`.bag` com depth, color e IMU) para pós-processamento.

**Architecture:** Duas Activities (Captura e Scans) sobre um núcleo em camadas: domínio puro testável em JVM (`CameraStateMachine`, `ScanRepository`, `StorageGuard`, `StreamProfiles`, `Formatters`) e uma classe de cola `RsCameraManager` que encapsula todo o librealsense (contexto USB, pipeline, gravação). A UI só conversa com o manager e o repositório.

**Tech Stack:** Kotlin, Views XML, AAR do librealsense (wrapper Java oficial), JUnit 4 para testes JVM, Gradle Kotlin DSL, minSdk 26.

## Global Constraints

- Diretório: `/home/felipe/realsense-mapper/android-app/`; SDK Android em `/home/felipe/Android/Sdk`.
- `applicationId`/`namespace`: `br.senai.realsensemapper`. minSdk 26, compileSdk 35, JVM target 17.
- Perfil de streams USB 3 (da spec): depth 848×480 @ 30 fps, color 1280×720 @ 30 fps, gyro 200 Hz, accel 250 Hz. Fallback USB 2: depth e color 640×480 @ 15 fps.
- Scans em `getExternalFilesDir(null)/scans/scan_yyyyMMdd_HHmmss.bag`.
- Limiar de disco (da spec): CRITICAL abaixo de 2 GiB livres (parar gravação), LOW abaixo de 4 GiB (avisar).
- Textos de UI em português (com acentuação correta); identificadores em inglês.
- Todo task termina com `./gradlew :app:testDebugUnitTest :app:assembleDebug` verde antes do commit (exceto onde indicado só assemble).
- Commits `feat:`/`test:`/`chore:` curtos, co-autoria `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Scaffold do projeto Gradle

**Files:**
- Create: `android-app/settings.gradle.kts`
- Create: `android-app/build.gradle.kts`
- Create: `android-app/gradle.properties`
- Create: `android-app/local.properties`
- Create: `android-app/app/build.gradle.kts`
- Create: `android-app/app/src/main/AndroidManifest.xml`
- Create: `android-app/app/src/main/res/values/strings.xml`
- Create: `android-app/app/src/main/res/values/themes.xml`
- Create: `android-app/app/src/main/java/br/senai/realsensemapper/CaptureActivity.kt` (placeholder)
- Create: `android-app/app/src/main/res/layout/activity_capture.xml` (placeholder)

**Interfaces:**
- Consumes: nada.
- Produces: projeto que compila com `./gradlew :app:assembleDebug`; Activity `CaptureActivity` como launcher.

- [ ] **Step 1: Criar arquivos raiz do Gradle**

`android-app/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RealSenseMapper"
include(":app")
```

`android-app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
```

`android-app/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2g
android.useAndroidX=true
```

`android-app/local.properties`:

```properties
sdk.dir=/home/felipe/Android/Sdk
```

- [ ] **Step 2: Criar módulo app**

`android-app/app/build.gradle.kts`:

```kotlin
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
        targetSdk = 35
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
}
```

`android-app/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.usb.host" android:required="true" />

    <application
        android:label="@string/app_name"
        android:theme="@style/Theme.RealSenseMapper">
        <activity
            android:name=".CaptureActivity"
            android:exported="true"
            android:screenOrientation="landscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android-app/app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">RealSense Mapper</string>
    <string name="status_disconnected">Câmera desconectada — conecte a D435i via USB</string>
    <string name="status_connected">Câmera conectada</string>
    <string name="status_streaming">Preview ativo</string>
    <string name="status_recording">Gravando…</string>
    <string name="warn_usb2">Porta USB 2.0 detectada — qualidade reduzida</string>
    <string name="warn_low_storage">Pouco espaço em disco</string>
    <string name="warn_storage_stop">Espaço crítico — gravação interrompida</string>
    <string name="warn_low_fps">Taxa de frames baixa — verifique cabo/porta USB</string>
    <string name="btn_record">Gravar</string>
    <string name="btn_stop">Parar</string>
    <string name="title_scans">Scans gravados</string>
    <string name="action_share">Compartilhar</string>
    <string name="action_delete">Apagar</string>
    <string name="msg_scan_saved">Scan salvo: %1$s</string>
    <string name="msg_scan_interrupted">Gravação interrompida — o arquivo foi salvo até este ponto</string>
</resources>
```

`android-app/app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.RealSenseMapper" parent="Theme.Material3.Dark.NoActionBar" />
</resources>
```

- [ ] **Step 3: Criar Activity e layout placeholder**

`android-app/app/src/main/java/br/senai/realsensemapper/CaptureActivity.kt`:

```kotlin
package br.senai.realsensemapper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class CaptureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
    }
}
```

`android-app/app/src/main/res/layout/activity_capture.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/statusText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:text="@string/status_disconnected" />
</FrameLayout>
```

- [ ] **Step 4: Gerar o Gradle wrapper e compilar**

Run:
```bash
cd /home/felipe/realsense-mapper/android-app
gradle wrapper --gradle-version 8.7 2>/dev/null || \
  (cd /home/felipe/AndroidStudioProjects/MyApplication && ./gradlew --version >/dev/null && \
   cp -r gradle gradlew gradlew.bat /home/felipe/realsense-mapper/android-app/)
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. (Se não houver `gradle` no PATH, o wrapper é copiado do projeto existente `MyApplication`; ajustar `distributionUrl` em `gradle/wrapper/gradle-wrapper.properties` para `gradle-8.7-bin.zip` se necessário.)

- [ ] **Step 5: Commit**

```bash
cd /home/felipe/realsense-mapper
git add android-app/
git commit -m "chore: scaffold do app Android (Gradle, manifest, tema)"
```

Nota: `local.properties` está no `.gitignore` — correto, não commitar.

---

### Task 2: Integrar o AAR do librealsense

**Files:**
- Create: `android-app/app/libs/librealsense.aar` (binário, obtido — não escrito)
- Modify: `android-app/app/build.gradle.kts` (dependência)
- Modify: `.gitignore` (raiz — exceção para o AAR)

**Interfaces:**
- Consumes: scaffold (Task 1).
- Produces: classes `com.intel.realsense.librealsense.*` (RsContext, Pipeline, Config, StreamType, StreamFormat, DeviceListener, GLRsSurfaceView, Colorizer, FrameReleaser, CameraInfo) disponíveis no classpath.

- [ ] **Step 1: Obter o AAR (tentativas em ordem)**

1. **Maven Central:** verificar se `com.intel.realsense:librealsense` existe em <https://central.sonatype.com/search?q=librealsense>. Se existir, usar a dependência Maven direto no `build.gradle.kts` (`implementation("com.intel.realsense:librealsense:<versão>@aar")`) e pular o arquivo local.
2. **Release do GitHub:** procurar asset `.aar` nas releases de <https://github.com/IntelRealSense/librealsense/releases> (versões 2.5x). Baixar para `android-app/app/libs/librealsense.aar`.
3. **Build do fonte (último recurso):**
   ```bash
   cd /tmp/claude-1000/-home-felipe/*/scratchpad
   git clone --depth 1 --branch v2.54.2 https://github.com/IntelRealSense/librealsense.git
   cd librealsense/wrappers/android
   # requer NDK: sdkmanager "ndk;25.1.8937393"
   ./gradlew :librealsense:assembleRelease
   cp librealsense/build/outputs/aar/librealsense-release.aar \
      /home/felipe/realsense-mapper/android-app/app/libs/librealsense.aar
   ```

- [ ] **Step 2: Adicionar a dependência**

Em `android-app/app/build.gradle.kts`, dentro de `dependencies { }`, adicionar (caso arquivo local):

```kotlin
    implementation(files("libs/librealsense.aar"))
```

No `.gitignore` da raiz, permitir versionar o AAR (arquivo grande ~5–15 MB, aceitável):

```gitignore
!android-app/app/libs/*.aar
```

- [ ] **Step 3: Smoke test de classpath**

Adicionar em `CaptureActivity.onCreate`, logo após `setContentView`:

```kotlin
        // Smoke test do AAR — substituído pelo RsCameraManager na Task 7
        android.util.Log.i("RSMapper", "librealsense: " +
            com.intel.realsense.librealsense.RsContext.getVersion())
```

- [ ] **Step 4: Compilar**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (a classe do AAR resolve; se falhar por minSdk do AAR, subir `minSdk` para o exigido e anotar no README).

- [ ] **Step 5: Commit**

```bash
git add android-app/app/build.gradle.kts android-app/app/libs/ .gitignore \
        android-app/app/src/main/java/br/senai/realsensemapper/CaptureActivity.kt
git commit -m "feat: integra AAR do librealsense"
```

---

### Task 3: Formatters (bytes e duração)

**Files:**
- Create: `android-app/app/src/main/java/br/senai/realsensemapper/domain/Formatters.kt`
- Test: `android-app/app/src/test/java/br/senai/realsensemapper/domain/FormattersTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `formatBytes(bytes: Long): String` (ex.: `"1,5 GB"`), `formatDuration(totalSeconds: Long): String` (ex.: `"02:35"`, `"1:02:35"`).

- [ ] **Step 1: Escrever testes que falham**

`android-app/app/src/test/java/br/senai/realsensemapper/domain/FormattersTest.kt`:

```kotlin
package br.senai.realsensemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    @Test fun bytes_small() = assertEquals("512 B", formatBytes(512))
    @Test fun bytes_kb() = assertEquals("2,0 KB", formatBytes(2048))
    @Test fun bytes_mb() = assertEquals("1,5 MB", formatBytes(1_572_864))
    @Test fun bytes_gb() = assertEquals("2,0 GB", formatBytes(2_147_483_648))

    @Test fun duration_minutes() = assertEquals("02:35", formatDuration(155))
    @Test fun duration_zero() = assertEquals("00:00", formatDuration(0))
    @Test fun duration_hours() = assertEquals("1:02:35", formatDuration(3755))
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./gradlew :app:testDebugUnitTest --tests '*FormattersTest*'`
Expected: FAIL (unresolved reference `formatBytes`)

- [ ] **Step 3: Implementar**

`android-app/app/src/main/java/br/senai/realsensemapper/domain/Formatters.kt`:

```kotlin
package br.senai.realsensemapper.domain

import java.util.Locale

private val PT_BR = Locale("pt", "BR")

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = ""
    for (u in units) {
        value /= 1024.0
        unit = u
        if (value < 1024) break
    }
    return String.format(PT_BR, "%.1f %s", value, unit)
}

fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(PT_BR, "%d:%02d:%02d", h, m, s)
    else String.format(PT_BR, "%02d:%02d", m, s)
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./gradlew :app:testDebugUnitTest --tests '*FormattersTest*'`
Expected: `BUILD SUCCESSFUL`, 7 testes verdes

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src
git commit -m "feat: formatters de bytes e duração"
```

---

### Task 4: CameraStateMachine

**Files:**
- Create: `android-app/app/src/main/java/br/senai/realsensemapper/domain/CameraStateMachine.kt`
- Test: `android-app/app/src/test/java/br/senai/realsensemapper/domain/CameraStateMachineTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces: `enum class CameraState { DISCONNECTED, CONNECTED, STREAMING, RECORDING }`, `enum class CameraEvent { ATTACHED, DETACHED, STREAM_STARTED, STREAM_STOPPED, RECORD_STARTED, RECORD_STOPPED }`, `fun nextState(state: CameraState, event: CameraEvent): CameraState`.

- [ ] **Step 1: Escrever testes que falham**

`android-app/app/src/test/java/br/senai/realsensemapper/domain/CameraStateMachineTest.kt`:

```kotlin
package br.senai.realsensemapper.domain

import br.senai.realsensemapper.domain.CameraEvent.*
import br.senai.realsensemapper.domain.CameraState.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStateMachineTest {
    @Test fun happy_path() {
        var s = DISCONNECTED
        s = nextState(s, ATTACHED); assertEquals(CONNECTED, s)
        s = nextState(s, STREAM_STARTED); assertEquals(STREAMING, s)
        s = nextState(s, RECORD_STARTED); assertEquals(RECORDING, s)
        s = nextState(s, RECORD_STOPPED); assertEquals(STREAMING, s)
        s = nextState(s, STREAM_STOPPED); assertEquals(CONNECTED, s)
    }

    @Test fun detach_from_any_state_disconnects() {
        for (s in CameraState.entries) {
            assertEquals(DISCONNECTED, nextState(s, DETACHED))
        }
    }

    @Test fun invalid_events_keep_state() {
        assertEquals(DISCONNECTED, nextState(DISCONNECTED, RECORD_STARTED))
        assertEquals(CONNECTED, nextState(CONNECTED, RECORD_STOPPED))
        assertEquals(RECORDING, nextState(RECORDING, ATTACHED))
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./gradlew :app:testDebugUnitTest --tests '*CameraStateMachineTest*'`
Expected: FAIL (unresolved references)

- [ ] **Step 3: Implementar**

`android-app/app/src/main/java/br/senai/realsensemapper/domain/CameraStateMachine.kt`:

```kotlin
package br.senai.realsensemapper.domain

enum class CameraState { DISCONNECTED, CONNECTED, STREAMING, RECORDING }

enum class CameraEvent {
    ATTACHED, DETACHED, STREAM_STARTED, STREAM_STOPPED, RECORD_STARTED, RECORD_STOPPED
}

/** Transições válidas; evento inválido mantém o estado (robustez a callbacks USB duplicados). */
fun nextState(state: CameraState, event: CameraEvent): CameraState = when (event) {
    CameraEvent.DETACHED -> CameraState.DISCONNECTED
    CameraEvent.ATTACHED ->
        if (state == CameraState.DISCONNECTED) CameraState.CONNECTED else state
    CameraEvent.STREAM_STARTED ->
        if (state == CameraState.CONNECTED) CameraState.STREAMING else state
    CameraEvent.STREAM_STOPPED ->
        if (state == CameraState.STREAMING) CameraState.CONNECTED else state
    CameraEvent.RECORD_STARTED ->
        if (state == CameraState.STREAMING) CameraState.RECORDING else state
    CameraEvent.RECORD_STOPPED ->
        if (state == CameraState.RECORDING) CameraState.STREAMING else state
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./gradlew :app:testDebugUnitTest --tests '*CameraStateMachineTest*'`
Expected: 3 testes verdes

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src
git commit -m "feat: máquina de estados da câmera"
```

---

### Task 5: StorageGuard e StreamProfiles

**Files:**
- Create: `android-app/app/src/main/java/br/senai/realsensemapper/domain/StorageGuard.kt`
- Create: `android-app/app/src/main/java/br/senai/realsensemapper/domain/StreamProfiles.kt`
- Test: `android-app/app/src/test/java/br/senai/realsensemapper/domain/StorageGuardTest.kt`
- Test: `android-app/app/src/test/java/br/senai/realsensemapper/domain/StreamProfilesTest.kt`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `StorageGuard.Level { OK, LOW, CRITICAL }`, `StorageGuard.check(freeBytes: Long): Level`
  - `data class StreamProfile(depthWidth: Int, depthHeight: Int, colorWidth: Int, colorHeight: Int, fps: Int)`
  - `StreamProfiles.USB3`, `StreamProfiles.USB2`, `StreamProfiles.forUsbDescriptor(descriptor: String?): StreamProfile`

- [ ] **Step 1: Escrever testes que falham**

`android-app/app/src/test/java/br/senai/realsensemapper/domain/StorageGuardTest.kt`:

```kotlin
package br.senai.realsensemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageGuardTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun plenty_is_ok() = assertEquals(StorageGuard.Level.OK, StorageGuard.check(10 * gib))
    @Test fun below_4gib_is_low() = assertEquals(StorageGuard.Level.LOW, StorageGuard.check(3 * gib))
    @Test fun below_2gib_is_critical() =
        assertEquals(StorageGuard.Level.CRITICAL, StorageGuard.check(1 * gib))
    @Test fun boundary_2gib_is_low() =
        assertEquals(StorageGuard.Level.LOW, StorageGuard.check(2 * gib))
}
```

`android-app/app/src/test/java/br/senai/realsensemapper/domain/StreamProfilesTest.kt`:

```kotlin
package br.senai.realsensemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamProfilesTest {
    @Test fun usb3_descriptor() =
        assertEquals(StreamProfiles.USB3, StreamProfiles.forUsbDescriptor("3.2"))

    @Test fun usb2_descriptor() =
        assertEquals(StreamProfiles.USB2, StreamProfiles.forUsbDescriptor("2.1"))

    @Test fun unknown_descriptor_uses_safe_profile() =
        assertEquals(StreamProfiles.USB2, StreamProfiles.forUsbDescriptor(null))

    @Test fun usb3_profile_matches_spec() {
        val p = StreamProfiles.USB3
        assertEquals(848, p.depthWidth); assertEquals(480, p.depthHeight)
        assertEquals(1280, p.colorWidth); assertEquals(720, p.colorHeight)
        assertEquals(30, p.fps)
    }

    @Test fun usb2_profile_matches_spec() {
        val p = StreamProfiles.USB2
        assertEquals(640, p.depthWidth); assertEquals(480, p.colorHeight)
        assertEquals(15, p.fps)
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./gradlew :app:testDebugUnitTest --tests '*StorageGuardTest*' --tests '*StreamProfilesTest*'`
Expected: FAIL (unresolved references)

- [ ] **Step 3: Implementar**

`android-app/app/src/main/java/br/senai/realsensemapper/domain/StorageGuard.kt`:

```kotlin
package br.senai.realsensemapper.domain

/** Limiar da spec: parar gravação abaixo de 2 GiB; avisar abaixo de 4 GiB. */
object StorageGuard {
    enum class Level { OK, LOW, CRITICAL }

    private const val GIB = 1024L * 1024 * 1024
    const val CRITICAL_BYTES = 2 * GIB
    const val LOW_BYTES = 4 * GIB

    fun check(freeBytes: Long): Level = when {
        freeBytes < CRITICAL_BYTES -> Level.CRITICAL
        freeBytes < LOW_BYTES -> Level.LOW
        else -> Level.OK
    }
}
```

`android-app/app/src/main/java/br/senai/realsensemapper/domain/StreamProfiles.kt`:

```kotlin
package br.senai.realsensemapper.domain

data class StreamProfile(
    val depthWidth: Int, val depthHeight: Int,
    val colorWidth: Int, val colorHeight: Int,
    val fps: Int,
)

object StreamProfiles {
    val USB3 = StreamProfile(848, 480, 1280, 720, 30)
    val USB2 = StreamProfile(640, 480, 640, 480, 15)

    const val GYRO_FPS = 200
    const val ACCEL_FPS = 250

    /** Descriptor vem de CameraInfo.USB_TYPE_DESCRIPTOR (ex.: "3.2", "2.1"). */
    fun forUsbDescriptor(descriptor: String?): StreamProfile =
        if (descriptor?.startsWith("3") == true) USB3 else USB2
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./gradlew :app:testDebugUnitTest --tests '*StorageGuardTest*' --tests '*StreamProfilesTest*'`
Expected: 9 testes verdes

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src
git commit -m "feat: StorageGuard e perfis de stream USB2/USB3"
```

---

### Task 6: ScanRepository

**Files:**
- Create: `android-app/app/src/main/java/br/senai/realsensemapper/domain/ScanRepository.kt`
- Test: `android-app/app/src/test/java/br/senai/realsensemapper/domain/ScanRepositoryTest.kt`

**Interfaces:**
- Consumes: nada (recebe `File` e clock injetável — testável em JVM puro).
- Produces:
  - `data class ScanInfo(val file: File, val name: String, val sizeBytes: Long, val modifiedAt: Long)`
  - `class ScanRepository(scansDir: File, clock: () -> Date = { Date() })` com `newScanFile(): File`, `listScans(): List<ScanInfo>` (mais recente primeiro), `delete(scan: ScanInfo): Boolean`

- [ ] **Step 1: Escrever testes que falham**

`android-app/app/src/test/java/br/senai/realsensemapper/domain/ScanRepositoryTest.kt`:

```kotlin
package br.senai.realsensemapper.domain

import java.util.Calendar
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixedDate(): Date =
        Calendar.getInstance().apply { set(2026, Calendar.JULY, 2, 10, 30, 0) }.time

    @Test fun new_scan_file_uses_timestamp_name() {
        val repo = ScanRepository(tmp.root, clock = ::fixedDate)
        val f = repo.newScanFile()
        assertEquals("scan_20260702_103000.bag", f.name)
        assertTrue(f.parentFile!!.isDirectory)
    }

    @Test fun list_scans_newest_first() {
        val repo = ScanRepository(tmp.root)
        val a = tmp.newFile("scan_20260701_090000.bag").apply { writeText("aa") }
        val b = tmp.newFile("scan_20260702_090000.bag").apply { writeText("bbbb") }
        a.setLastModified(1_000_000)
        b.setLastModified(2_000_000)

        val scans = repo.listScans()
        assertEquals(listOf(b.name, a.name), scans.map { it.name })
        assertEquals(4L, scans[0].sizeBytes)
    }

    @Test fun list_ignores_non_bag_files() {
        val repo = ScanRepository(tmp.root)
        tmp.newFile("notes.txt")
        assertEquals(0, repo.listScans().size)
    }

    @Test fun delete_removes_file() {
        val repo = ScanRepository(tmp.root)
        tmp.newFile("scan_20260702_090000.bag")
        val scan = repo.listScans().single()
        assertTrue(repo.delete(scan))
        assertFalse(scan.file.exists())
        assertEquals(0, repo.listScans().size)
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScanRepositoryTest*'`
Expected: FAIL (unresolved references)

- [ ] **Step 3: Implementar**

`android-app/app/src/main/java/br/senai/realsensemapper/domain/ScanRepository.kt`:

```kotlin
package br.senai.realsensemapper.domain

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScanInfo(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/** Gerencia os arquivos .bag em <externalFilesDir>/scans (a UI passa o diretório). */
class ScanRepository(
    private val scansDir: File,
    private val clock: () -> Date = { Date() },
) {
    private val nameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun newScanFile(): File {
        scansDir.mkdirs()
        return File(scansDir, "scan_${nameFormat.format(clock())}.bag")
    }

    fun listScans(): List<ScanInfo> =
        (scansDir.listFiles { f -> f.isFile && f.extension == "bag" } ?: emptyArray())
            .map { ScanInfo(it, it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedAt }

    fun delete(scan: ScanInfo): Boolean = scan.file.delete()
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScanRepositoryTest*'`
Expected: 4 testes verdes

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src
git commit -m "feat: ScanRepository (nomes, listagem, exclusão)"
```

---

### Task 7: RsCameraManager (cola com o librealsense)

**Files:**
- Create: `android-app/app/src/main/java/br/senai/realsensemapper/camera/RsCameraManager.kt`

**Interfaces:**
- Consumes: `CameraState`, `CameraEvent`, `nextState` (Task 4); `StreamProfile`, `StreamProfiles` (Task 5); AAR (Task 2).
- Produces:
  - `interface RsCameraManager.Listener { fun onStateChanged(state: CameraState); fun onUsbProfile(profile: StreamProfile, descriptor: String?); fun onFps(fps: Float) }`
  - `class RsCameraManager(listener: Listener)`: `init(context: Context)`, `attachPreview(view: GLRsSurfaceView)`, `startRecording(file: File)`, `stopRecording()`, `shutdown()`, `val state: CameraState`
  - Comportamento: com câmera conectada faz streaming contínuo para o preview; `startRecording` reinicia o pipeline com `enableRecordToFile`; desconexão em qualquer estado fecha o pipeline com segurança (o `.bag` permanece válido — o gravador fecha no `pipeline.stop()`).

Sem teste JVM (depende de hardware/JNI); verificação é compilar. O código completo:

- [ ] **Step 1: Implementar**

`android-app/app/src/main/java/br/senai/realsensemapper/camera/RsCameraManager.kt`:

```kotlin
package br.senai.realsensemapper.camera

import android.content.Context
import android.util.Log
import br.senai.realsensemapper.domain.CameraEvent
import br.senai.realsensemapper.domain.CameraState
import br.senai.realsensemapper.domain.StreamProfile
import br.senai.realsensemapper.domain.StreamProfiles
import br.senai.realsensemapper.domain.nextState
import com.intel.realsense.librealsense.CameraInfo
import com.intel.realsense.librealsense.Colorizer
import com.intel.realsense.librealsense.Config
import com.intel.realsense.librealsense.DeviceListener
import com.intel.realsense.librealsense.FrameReleaser
import com.intel.realsense.librealsense.GLRsSurfaceView
import com.intel.realsense.librealsense.Pipeline
import com.intel.realsense.librealsense.RsContext
import com.intel.realsense.librealsense.StreamFormat
import com.intel.realsense.librealsense.StreamType
import java.io.File

private const val TAG = "RsCameraManager"

class RsCameraManager(private val listener: Listener) {

    interface Listener {
        fun onStateChanged(state: CameraState)
        fun onUsbProfile(profile: StreamProfile, descriptor: String?)
        fun onFps(fps: Float)
    }

    var state: CameraState = CameraState.DISCONNECTED
        private set

    private var rsContext: RsContext? = null
    private var pipeline: Pipeline? = null
    private var previewView: GLRsSurfaceView? = null
    private var streamThread: Thread? = null
    @Volatile private var streaming = false
    private var profile: StreamProfile = StreamProfiles.USB2
    private var recordFile: File? = null

    private val deviceListener = object : DeviceListener {
        override fun onDeviceAttach() = onEvent(CameraEvent.ATTACHED)
        override fun onDeviceDetach() = onEvent(CameraEvent.DETACHED)
    }

    fun init(context: Context) {
        RsContext.init(context.applicationContext)
        rsContext = RsContext().also { it.setDevicesChangedCallback(deviceListener) }
        rsContext?.queryDevices()?.use { devices ->
            if (devices.deviceCount > 0) onEvent(CameraEvent.ATTACHED)
        }
    }

    fun attachPreview(view: GLRsSurfaceView) {
        previewView = view
        if (state == CameraState.CONNECTED) startStreaming(record = false)
    }

    fun startRecording(file: File) {
        if (state != CameraState.STREAMING) return
        recordFile = file
        restartPipeline(record = true)
        onEvent(CameraEvent.RECORD_STARTED)
    }

    fun stopRecording() {
        if (state != CameraState.RECORDING) return
        recordFile = null
        restartPipeline(record = false)
        onEvent(CameraEvent.RECORD_STOPPED)
    }

    fun shutdown() {
        stopStreaming()
        rsContext?.close()
        rsContext = null
    }

    private fun onEvent(event: CameraEvent) {
        val newState = nextState(state, event)
        if (newState == state) return
        state = newState
        listener.onStateChanged(state)
        when {
            event == CameraEvent.ATTACHED && previewView != null ->
                startStreaming(record = false)
            event == CameraEvent.DETACHED -> stopStreaming()
        }
    }

    private fun detectProfile() {
        val descriptor = rsContext?.queryDevices()?.use { devices ->
            if (devices.deviceCount > 0)
                devices.createDevice(0)?.use { it.getInfo(CameraInfo.USB_TYPE_DESCRIPTOR) }
            else null
        }
        profile = StreamProfiles.forUsbDescriptor(descriptor)
        listener.onUsbProfile(profile, descriptor)
    }

    private fun buildConfig(record: Boolean): Config = Config().apply {
        enableStream(StreamType.DEPTH, -1, profile.depthWidth, profile.depthHeight,
            StreamFormat.Z16, profile.fps)
        enableStream(StreamType.COLOR, -1, profile.colorWidth, profile.colorHeight,
            StreamFormat.RGB8, profile.fps)
        enableStream(StreamType.GYRO, -1, 0, 0,
            StreamFormat.MOTION_XYZ32F, StreamProfiles.GYRO_FPS)
        enableStream(StreamType.ACCEL, -1, 0, 0,
            StreamFormat.MOTION_XYZ32F, StreamProfiles.ACCEL_FPS)
        if (record) recordFile?.let { enableRecordToFile(it.absolutePath) }
    }

    private fun startStreaming(record: Boolean) {
        if (streaming) return
        detectProfile()
        try {
            pipeline = Pipeline().also { it.start(buildConfig(record)) }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar pipeline", e)
            pipeline = null
            return
        }
        streaming = true
        if (state == CameraState.CONNECTED) onEvent(CameraEvent.STREAM_STARTED)
        streamThread = Thread(::streamLoop, "rs-stream").also { it.start() }
    }

    private fun stopStreaming() {
        streaming = false
        streamThread?.join(3000)
        streamThread = null
        try {
            pipeline?.stop()  // fecha também o gravador do .bag, mantendo-o válido
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao parar pipeline", e)
        }
        pipeline = null
    }

    private fun restartPipeline(record: Boolean) {
        stopStreaming()
        startStreaming(record)
    }

    private fun streamLoop() {
        val colorizer = Colorizer()
        var frames = 0
        var windowStart = System.currentTimeMillis()
        while (streaming) {
            try {
                FrameReleaser().use { fr ->
                    val frameSet = pipeline?.waitForFrames()?.releaseWith(fr) ?: return
                    val colorized = frameSet.applyFilter(colorizer).releaseWith(fr)
                    previewView?.upload(colorized)
                }
                frames++
                val now = System.currentTimeMillis()
                if (now - windowStart >= 1000) {
                    listener.onFps(frames * 1000f / (now - windowStart))
                    frames = 0
                    windowStart = now
                }
            } catch (e: Exception) {
                if (streaming) Log.w(TAG, "Frame perdido: ${e.message}")
            }
        }
        colorizer.close()
    }
}
```

- [ ] **Step 2: Remover o smoke test da Task 2**

Em `CaptureActivity.kt`, apagar as duas linhas do log `"librealsense: "` adicionadas na Task 2 (o manager assume o papel na próxima task).

- [ ] **Step 3: Compilar**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. Se algum nome de método do AAR divergir (a API Java muda pouco, mas muda — ex.: `deviceCount` vs `getDeviceCount()`), ajustar o call site consultando as classes dentro do AAR: `unzip -p app/libs/librealsense.aar classes.jar > /tmp/claude-1000/-home-felipe/*/scratchpad/rs.jar && unzip -l /tmp/.../rs.jar`.

- [ ] **Step 4: Commit**

```bash
git add android-app/app/src
git commit -m "feat: RsCameraManager — ciclo de vida do librealsense"
```

---

### Task 8: Tela de Captura

**Files:**
- Modify: `android-app/app/src/main/java/br/senai/realsensemapper/CaptureActivity.kt` (reescrever)
- Modify: `android-app/app/src/main/res/layout/activity_capture.xml` (reescrever)

**Interfaces:**
- Consumes: `RsCameraManager` (Task 7), `ScanRepository` (Task 6), `StorageGuard` (Task 5), `formatBytes`/`formatDuration` (Task 3), strings (Task 1).
- Produces: tela funcional com preview, botão gravar/parar, cronômetro, tamanho do arquivo, status e avisos (USB 2.0, storage, fps baixo). Menu para abrir a tela de Scans (`ScansActivity`, criada na Task 9 — o Intent usa o nome da classe; nesta task o botão fica `View.GONE` até a Task 9 ligá-lo).

- [ ] **Step 1: Reescrever o layout**

`android-app/app/src/main/res/layout/activity_capture.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000">

    <com.intel.realsense.librealsense.GLRsSurfaceView
        android:id="@+id/preview"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <TextView
        android:id="@+id/statusText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:background="#88000000"
        android:padding="8dp"
        android:textColor="#FFFFFF"
        android:text="@string/status_disconnected"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:id="@+id/warnText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:background="#88AA0000"
        android:padding="8dp"
        android:textColor="#FFFFFF"
        android:visibility="gone"
        app:layout_constraintTop_toBottomOf="@id/statusText"
        app:layout_constraintStart_toStartOf="parent" />

    <TextView
        android:id="@+id/recordInfo"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:background="#88000000"
        android:padding="8dp"
        android:textColor="#FF5252"
        android:textStyle="bold"
        android:visibility="gone"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/recordButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_margin="24dp"
        android:contentDescription="@string/btn_record"
        app:srcCompat="@android:drawable/ic_media_play"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/scansButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_margin="24dp"
        android:visibility="gone"
        android:contentDescription="@string/title_scans"
        app:srcCompat="@android:drawable/ic_menu_agenda"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

Adicionar em `app/build.gradle.kts` (dependencies):

```kotlin
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
```

- [ ] **Step 2: Reescrever a Activity**

`android-app/app/src/main/java/br/senai/realsensemapper/CaptureActivity.kt`:

```kotlin
package br.senai.realsensemapper

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import br.senai.realsensemapper.camera.RsCameraManager
import br.senai.realsensemapper.domain.CameraState
import br.senai.realsensemapper.domain.ScanRepository
import br.senai.realsensemapper.domain.StorageGuard
import br.senai.realsensemapper.domain.StreamProfile
import br.senai.realsensemapper.domain.StreamProfiles
import br.senai.realsensemapper.domain.formatBytes
import br.senai.realsensemapper.domain.formatDuration
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.intel.realsense.librealsense.GLRsSurfaceView
import java.io.File

class CaptureActivity : AppCompatActivity(), RsCameraManager.Listener {

    private lateinit var camera: RsCameraManager
    private lateinit var repo: ScanRepository
    private lateinit var statusText: TextView
    private lateinit var warnText: TextView
    private lateinit var recordInfo: TextView
    private lateinit var recordButton: FloatingActionButton

    private val ui = Handler(Looper.getMainLooper())
    private var recordingFile: File? = null
    private var recordingStartMs = 0L
    private var usbWarning: String? = null
    private var lowFps = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        statusText = findViewById(R.id.statusText)
        warnText = findViewById(R.id.warnText)
        recordInfo = findViewById(R.id.recordInfo)
        recordButton = findViewById(R.id.recordButton)

        repo = ScanRepository(File(getExternalFilesDir(null), "scans"))
        camera = RsCameraManager(this)
        camera.init(this)
        camera.attachPreview(findViewById<GLRsSurfaceView>(R.id.preview))

        recordButton.setOnClickListener { toggleRecording() }
        ui.post(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        camera.shutdown()
        super.onDestroy()
    }

    private fun toggleRecording() {
        when (camera.state) {
            CameraState.STREAMING -> {
                val free = StatFs(getExternalFilesDir(null)!!.path).availableBytes
                if (StorageGuard.check(free) == StorageGuard.Level.CRITICAL) {
                    Snackbar.make(recordButton, R.string.warn_storage_stop,
                        Snackbar.LENGTH_LONG).show()
                    return
                }
                val file = repo.newScanFile()
                recordingFile = file
                recordingStartMs = System.currentTimeMillis()
                camera.startRecording(file)
            }
            CameraState.RECORDING -> {
                camera.stopRecording()
                recordingFile?.let {
                    Snackbar.make(recordButton,
                        getString(R.string.msg_scan_saved, it.name),
                        Snackbar.LENGTH_LONG).show()
                }
                recordingFile = null
            }
            else -> Unit
        }
    }

    // Atualização periódica: cronômetro, tamanho do arquivo e guarda de disco.
    private val ticker = object : Runnable {
        override fun run() {
            if (camera.state == CameraState.RECORDING) {
                val elapsed = (System.currentTimeMillis() - recordingStartMs) / 1000
                val size = recordingFile?.length() ?: 0
                recordInfo.text = "● ${formatDuration(elapsed)}  ${formatBytes(size)}"
                recordInfo.visibility = View.VISIBLE

                val free = StatFs(getExternalFilesDir(null)!!.path).availableBytes
                when (StorageGuard.check(free)) {
                    StorageGuard.Level.CRITICAL -> {
                        camera.stopRecording()
                        recordingFile = null
                        Snackbar.make(recordButton, R.string.warn_storage_stop,
                            Snackbar.LENGTH_LONG).show()
                    }
                    StorageGuard.Level.LOW -> showWarnings(getString(R.string.warn_low_storage))
                    StorageGuard.Level.OK -> showWarnings(null)
                }
            } else {
                recordInfo.visibility = View.GONE
            }
            ui.postDelayed(this, 500)
        }
    }

    private fun showWarnings(extra: String?) {
        val parts = listOfNotNull(
            usbWarning,
            if (lowFps) getString(R.string.warn_low_fps) else null,
            extra,
        )
        warnText.text = parts.joinToString("\n")
        warnText.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    // --- RsCameraManager.Listener (chamados fora da UI thread) ---

    override fun onStateChanged(state: CameraState) = runOnUiThread {
        statusText.setText(when (state) {
            CameraState.DISCONNECTED -> R.string.status_disconnected
            CameraState.CONNECTED -> R.string.status_connected
            CameraState.STREAMING -> R.string.status_streaming
            CameraState.RECORDING -> R.string.status_recording
        })
        recordButton.setImageResource(
            if (state == CameraState.RECORDING) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play)
        if (state == CameraState.DISCONNECTED && recordingFile != null) {
            recordingFile = null
            Snackbar.make(recordButton, R.string.msg_scan_interrupted,
                Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onUsbProfile(profile: StreamProfile, descriptor: String?) = runOnUiThread {
        usbWarning = if (profile == StreamProfiles.USB2)
            getString(R.string.warn_usb2) else null
        showWarnings(null)
    }

    override fun onFps(fps: Float) = runOnUiThread {
        lowFps = fps < 20f && camera.state >= CameraState.STREAMING
        showWarnings(null)
    }
}
```

Nota: `camera.state >= CameraState.STREAMING` compara pela ordem do enum (STREAMING e RECORDING vêm depois de CONNECTED) — vale porque a ordem de declaração no enum é DISCONNECTED, CONNECTED, STREAMING, RECORDING.

- [ ] **Step 3: Compilar e rodar testes**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add android-app/app
git commit -m "feat: tela de captura com preview, gravação e avisos"
```

---

### Task 9: Tela de Scans (lista, apagar, compartilhar)

**Files:**
- Create: `android-app/app/src/main/java/br/senai/realsensemapper/ScansActivity.kt`
- Create: `android-app/app/src/main/res/layout/activity_scans.xml`
- Create: `android-app/app/src/main/res/layout/item_scan.xml`
- Create: `android-app/app/src/main/res/xml/file_paths.xml`
- Modify: `android-app/app/src/main/AndroidManifest.xml` (ScansActivity + FileProvider)
- Modify: `android-app/app/src/main/java/br/senai/realsensemapper/CaptureActivity.kt` (ligar botão)

**Interfaces:**
- Consumes: `ScanRepository`, `ScanInfo` (Task 6); `formatBytes` (Task 3).
- Produces: tela de lista acessível pelo botão da captura; compartilhar via `FileProvider` (authority `br.senai.realsensemapper.fileprovider`).

- [ ] **Step 1: Layouts**

`android-app/app/src/main/res/layout/activity_scans.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.recyclerview.widget.RecyclerView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/scanList"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="8dp" />
```

`android-app/app/src/main/res/layout/item_scan.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:padding="12dp">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/scanName"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/scanMeta"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="12sp" />
    </LinearLayout>

    <ImageButton
        android:id="@+id/shareButton"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/action_share"
        android:src="@android:drawable/ic_menu_share" />

    <ImageButton
        android:id="@+id/deleteButton"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/action_delete"
        android:src="@android:drawable/ic_menu_delete" />
</LinearLayout>
```

`android-app/app/src/main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="scans" path="scans/" />
</paths>
```

- [ ] **Step 2: Manifest**

Em `AndroidManifest.xml`, dentro de `<application>`, adicionar:

```xml
        <activity
            android:name=".ScansActivity"
            android:exported="false"
            android:label="@string/title_scans" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="br.senai.realsensemapper.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 3: Activity + adapter**

`android-app/app/src/main/java/br/senai/realsensemapper/ScansActivity.kt`:

```kotlin
package br.senai.realsensemapper

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import br.senai.realsensemapper.domain.ScanInfo
import br.senai.realsensemapper.domain.ScanRepository
import br.senai.realsensemapper.domain.formatBytes
import java.io.File

class ScansActivity : AppCompatActivity() {

    private lateinit var repo: ScanRepository
    private lateinit var adapter: ScanAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scans)
        repo = ScanRepository(File(getExternalFilesDir(null), "scans"))
        adapter = ScanAdapter(::shareScan, ::confirmDelete)
        findViewById<RecyclerView>(R.id.scanList).apply {
            layoutManager = LinearLayoutManager(this@ScansActivity)
            adapter = this@ScansActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.submit(repo.listScans())
    }

    private fun shareScan(scan: ScanInfo) {
        val uri = FileProvider.getUriForFile(
            this, "br.senai.realsensemapper.fileprovider", scan.file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.action_share)))
    }

    private fun confirmDelete(scan: ScanInfo) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.action_delete) + " ${scan.name}?")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repo.delete(scan)
                adapter.submit(repo.listScans())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

private class ScanAdapter(
    val onShare: (ScanInfo) -> Unit,
    val onDelete: (ScanInfo) -> Unit,
) : RecyclerView.Adapter<ScanAdapter.Holder>() {

    private var scans: List<ScanInfo> = emptyList()

    fun submit(items: List<ScanInfo>) {
        scans = items
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.scanName)
        val meta: TextView = view.findViewById(R.id.scanMeta)
        val share: ImageButton = view.findViewById(R.id.shareButton)
        val delete: ImageButton = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_scan, parent, false))

    override fun getItemCount() = scans.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val scan = scans[position]
        holder.name.text = scan.name
        val date = DateFormat.format("dd/MM/yyyy HH:mm", scan.modifiedAt)
        holder.meta.text = "$date — ${formatBytes(scan.sizeBytes)}"
        holder.share.setOnClickListener { onShare(scan) }
        holder.delete.setOnClickListener { onDelete(scan) }
    }
}
```

- [ ] **Step 4: Ligar o botão na CaptureActivity**

Em `CaptureActivity.onCreate`, após `recordButton.setOnClickListener { ... }`, adicionar:

```kotlin
        findViewById<FloatingActionButton>(R.id.scansButton).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                startActivity(android.content.Intent(this@CaptureActivity,
                    ScansActivity::class.java))
            }
        }
```

- [ ] **Step 5: Compilar e rodar testes**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add android-app/app
git commit -m "feat: tela de scans com compartilhar e apagar"
```

---

### Task 10: Roteiro de teste manual + README do app

**Files:**
- Create: `docs/roteiro-teste-app.md`
- Create: `android-app/README.md`

**Interfaces:**
- Consumes: app completo (Tasks 1–9).
- Produces: documentação de validação com hardware real.

- [ ] **Step 1: Escrever o roteiro de teste manual**

`docs/roteiro-teste-app.md`:

```markdown
# Roteiro de teste manual — RealSense Mapper (app)

Pré-requisitos: D435i com firmware atualizado (via `rs-fw-update` no PC),
celular com USB-C 3.x, cabo USB-C↔USB-C de dados, APK debug instalado
(`./gradlew :app:installDebug`).

## 1. Conexão
- [ ] Abrir o app sem câmera: status "Câmera desconectada".
- [ ] Conectar a D435i: Android pergunta permissão USB → conceder.
- [ ] Status muda para "Preview ativo" e o preview mostra depth colorizado + RGB.
- [ ] Sem aviso de USB 2.0 (se aparecer, trocar cabo/porta).

## 2. Gravação básica
- [ ] Tocar em Gravar: status "Gravando…", cronômetro e tamanho crescem.
- [ ] Gravar ~30 s varrendo a sala devagar e parar.
- [ ] Snackbar "Scan salvo: scan_<data>.bag".
- [ ] Tela de Scans lista o arquivo com tamanho > 100 MB.

## 3. Desconexão durante gravação
- [ ] Iniciar gravação e desconectar o cabo após ~10 s.
- [ ] App mostra "Gravação interrompida" e volta para "Câmera desconectada".
- [ ] Reconectar: preview volta sozinho.
- [ ] O arquivo aparece na lista de Scans (tamanho > 0).

## 4. Validação do .bag no PC
- [ ] Copiar o scan via MTP (pasta Android/data/br.senai.realsensemapper/files/scans/).
- [ ] `python extract.py scan_<data>.bag` roda sem erro e reporta frames > 0.
- [ ] Frames em out/<scan>/color/ têm imagem coerente.
- [ ] O bag do teste 3 (interrompido) também extrai sem erro.

## 5. Ponta a ponta
- [ ] Gravar ~2 min de uma sala, andando devagar, câmera sempre apontando
      para superfícies com textura (evitar paredes lisas de perto).
- [ ] `python reconstruct.py out/<scan>` termina e gera scene.glb.
- [ ] scene.glb abre no Blender com cores e a sala é reconhecível.
```

- [ ] **Step 2: Escrever o README do app**

`android-app/README.md`:

```markdown
# android-app — RealSense Mapper

App de captura para RealSense D435i via USB OTG: preview ao vivo
(depth + RGB) e gravação de streams brutos em `.bag` (depth, color, IMU)
para reconstrução posterior no `pc-pipeline/`.

## Build

    ./gradlew :app:assembleDebug        # APK em app/build/outputs/apk/debug/
    ./gradlew :app:installDebug         # instala via adb
    ./gradlew :app:testDebugUnitTest    # testes JVM

Requer `local.properties` com `sdk.dir` apontando para o Android SDK e o
AAR do librealsense em `app/libs/librealsense.aar` (ver plano da Task 2).

## Perfis de captura

| Porta | Depth | Color | FPS |
|---|---|---|---|
| USB 3.x | 848×480 | 1280×720 | 30 |
| USB 2.0 (fallback) | 640×480 | 640×480 | 15 |

IMU sempre: gyro 200 Hz, accel 250 Hz. ~1–2 GB por minuto em USB 3.

## Onde ficam os scans

`Android/data/br.senai.realsensemapper/files/scans/` — acessível por MTP
ao plugar o celular no PC, ou pelo botão Compartilhar na tela de Scans.

Roteiro de validação com hardware: `../docs/roteiro-teste-app.md`.
```

- [ ] **Step 3: Commit**

```bash
git add docs/roteiro-teste-app.md android-app/README.md
git commit -m "docs: roteiro de teste manual e README do app"
```
