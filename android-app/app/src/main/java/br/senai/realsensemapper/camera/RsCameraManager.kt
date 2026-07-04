package br.senai.realsensemapper.camera

import android.content.Context
import android.util.Log
import br.senai.realsensemapper.domain.CameraEvent
import br.senai.realsensemapper.domain.CameraState
import br.senai.realsensemapper.domain.StreamProfile
import br.senai.realsensemapper.domain.StreamProfiles
import br.senai.realsensemapper.domain.nextState
import br.senai.realsensemapper.util.AppLogger
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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val TAG = "RsCameraManager"

// Tempo limite de waitForFrames(): evita que a thread rs-stream fique bloqueada
// indefinidamente num frame nativo enquanto stopStreaming() tenta stop()/close()
// no mesmo Pipeline a partir da lifecycleExecutor.
private const val WAIT_FOR_FRAMES_TIMEOUT_MS = 1000

class RsCameraManager(
    private val listener: Listener,
    private val log: AppLogger? = null,
) {

    interface Listener {
        fun onStateChanged(state: CameraState)
        fun onUsbProfile(profile: StreamProfile, descriptor: String?)
        fun onFps(fps: Float)
    }

    // Lido tanto pela UI thread (CaptureActivity) quanto por esta classe;
    // mutado a partir de threads diferentes (callback USB, lifecycleExecutor).
    @Volatile
    var state: CameraState = CameraState.DISCONNECTED
        private set

    // Todas as operações que tocam o pipeline nativo (start/stop/restart) são
    // serializadas nesta única thread. Isso remove o risco de ANR (pipeline.start()
    // bloqueante rodando na UI thread a partir de um clique) e evita que duas
    // chamadas de ciclo de vida (ex.: attach + record) rodem entrelaçadas sobre o
    // mesmo Pipeline/Config nativos.
    private val lifecycleExecutor = Executors.newSingleThreadExecutor()

    private var rsContext: RsContext? = null
    private var pipeline: Pipeline? = null
    private var previewView: GLRsSurfaceView? = null
    private var streamThread: Thread? = null
    @Volatile private var streaming = false
    private var profile: StreamProfile = StreamProfiles.USB2
    private var recordFile: File? = null

    private val deviceListener = object : DeviceListener {
        override fun onDeviceAttach() {
            logI("USB: dispositivo conectado (callback)")
            onEvent(CameraEvent.ATTACHED)
        }
        override fun onDeviceDetach() {
            logI("USB: dispositivo desconectado (callback)")
            onEvent(CameraEvent.DETACHED)
        }
    }

    // Encaminham para o AppLogger (arquivo + Logcat) quando presente; caso
    // contrário, caem no Logcat direto. Assim os testes e usos sem logger
    // continuam funcionando sem NPE.
    private fun logI(message: String) = log?.i(TAG, message) ?: run { Log.i(TAG, message); Unit }
    private fun logW(message: String, e: Throwable? = null) =
        log?.w(TAG, message, e) ?: run { if (e != null) Log.w(TAG, message, e) else Log.w(TAG, message); Unit }
    private fun logE(message: String, e: Throwable? = null) =
        log?.e(TAG, message, e) ?: run { if (e != null) Log.e(TAG, message, e) else Log.e(TAG, message); Unit }

    fun init(context: Context) {
        RsContext.init(context.applicationContext)
        rsContext = RsContext().also { it.setDevicesChangedCallback(deviceListener) }
        rsContext?.queryDevices()?.use { devices ->
            val count = devices.deviceCount
            logI("init: $count dispositivo(s) RealSense na inicialização")
            if (count > 0) onEvent(CameraEvent.ATTACHED)
        }
    }

    fun attachPreview(view: GLRsSurfaceView) {
        previewView = view
        runOnLifecycle {
            if (state == CameraState.CONNECTED) startStreaming(record = false)
        }
    }

    fun startRecording(file: File) {
        if (state != CameraState.STREAMING) return
        recordFile = file
        runOnLifecycle {
            restartPipeline(record = true)
            onEvent(CameraEvent.RECORD_STARTED)
        }
    }

    fun stopRecording() {
        if (state != CameraState.RECORDING) return
        recordFile = null
        runOnLifecycle {
            restartPipeline(record = false)
            onEvent(CameraEvent.RECORD_STOPPED)
        }
    }

    fun shutdown() {
        runOnLifecycle {
            stopStreaming()
            rsContext?.close()
            rsContext = null
        }
        // Novas submissões passam a ser rejeitadas; as já enfileiradas (a de cima)
        // ainda rodam até o fim antes da thread da executor encerrar.
        lifecycleExecutor.shutdown()
    }

    /** Agenda [block] na lifecycleExecutor; nunca bloqueia a thread chamadora. */
    private fun runOnLifecycle(block: () -> Unit) {
        try {
            lifecycleExecutor.submit {
                try {
                    block()
                } catch (e: Exception) {
                    logE("Erro no ciclo de vida do pipeline", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            logW("lifecycleExecutor encerrada, ignorando operação de ciclo de vida")
        }
    }

    // Todo o corpo roda na lifecycleExecutor (via runOnLifecycle), incluindo a
    // leitura-modificação-escrita de `state`. Isso serializa onEvent com as
    // demais operações de ciclo de vida (start/stop/restart) numa única thread,
    // eliminando a corrida entre a thread de callback USB, a UI thread (init)
    // e a própria executor (STREAM_STARTED/RECORD_*). Chamadas que já estão na
    // executor (ex.: startStreaming -> onEvent(STREAM_STARTED)) apenas reenfileiram
    // via submit (fire-and-forget, sem espera bloqueante), o que preserva a ordem
    // FIFO dos eventos sem risco de deadlock.
    private fun onEvent(event: CameraEvent) {
        runOnLifecycle {
            val newState = nextState(state, event)
            if (newState == state) return@runOnLifecycle
            logI("Estado: $state --($event)--> $newState")
            state = newState
            listener.onStateChanged(state)
            when {
                event == CameraEvent.ATTACHED && previewView != null -> startStreaming(record = false)
                event == CameraEvent.DETACHED -> stopStreaming()
            }
        }
    }

    private fun detectProfile() {
        val descriptor = rsContext?.queryDevices()?.use { devices ->
            if (devices.deviceCount > 0)
                devices.createDevice(0)?.use { it.getInfo(CameraInfo.USB_TYPE_DESCRIPTOR) }
            else null
        }
        profile = StreamProfiles.forUsbDescriptor(descriptor)
        logI("Perfil USB: descriptor=$descriptor -> depth ${profile.depthWidth}x${profile.depthHeight}, " +
            "color ${profile.colorWidth}x${profile.colorHeight} @ ${profile.fps}fps")
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

    // Só deve ser chamada a partir da lifecycleExecutor.
    private fun startStreaming(record: Boolean) {
        if (streaming) return
        detectProfile()
        logI("Iniciando pipeline (record=$record)")
        try {
            val newPipeline = Pipeline()
            // Config só é necessário durante o start(); fechamos o handle nativo
            // dele assim que o pipeline já absorveu a configuração.
            buildConfig(record).use { config -> newPipeline.start(config) }
            pipeline = newPipeline
        } catch (e: Exception) {
            logE("Falha ao iniciar pipeline", e)
            pipeline = null
            return
        }
        streaming = true
        logI("Pipeline iniciado com sucesso (record=$record)")
        if (state == CameraState.CONNECTED) onEvent(CameraEvent.STREAM_STARTED)
        streamThread = Thread(::streamLoop, "rs-stream").also { it.start() }
    }

    // Só deve ser chamada a partir da lifecycleExecutor.
    private fun stopStreaming() {
        streaming = false
        // waitForFrames() tem timeout limitado (ver streamLoop), então a thread
        // rs-stream sai do wait nativo em no máximo WAIT_FOR_FRAMES_TIMEOUT_MS;
        // o join abaixo garante que ela já saiu do loop antes de stop()/close().
        streamThread?.join(3000)
        streamThread = null
        try {
            pipeline?.stop() // fecha também o gravador do .bag, mantendo-o válido
        } catch (e: Exception) {
            logW("Erro ao parar pipeline", e)
        }
        try {
            pipeline?.close() // libera o handle nativo do Pipeline
        } catch (e: Exception) {
            logW("Erro ao fechar pipeline", e)
        }
        pipeline = null
    }

    // Só deve ser chamada a partir da lifecycleExecutor.
    private fun restartPipeline(record: Boolean) {
        stopStreaming()
        startStreaming(record)
    }

    private fun streamLoop() {
        val colorizer = Colorizer()
        var frames = 0
        var firstFrame = true
        var windowStart = System.currentTimeMillis()
        try {
            while (streaming) {
                try {
                    FrameReleaser().use { fr ->
                        val frameSet = pipeline?.waitForFrames(WAIT_FOR_FRAMES_TIMEOUT_MS)
                            ?.releaseWith(fr) ?: return
                        val colorized = frameSet.applyFilter(colorizer).releaseWith(fr)
                        previewView?.upload(colorized)
                    }
                    if (firstFrame) {
                        logI("Primeiro frame recebido — fluxo de dados ativo")
                        firstFrame = false
                    }
                    frames++
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= 1000) {
                        listener.onFps(frames * 1000f / (now - windowStart))
                        frames = 0
                        windowStart = now
                    }
                } catch (e: Exception) {
                    // Inclui timeouts de waitForFrames(), esperados e benignos.
                    if (streaming) logW("Frame perdido: ${e.message}")
                }
            }
        } finally {
            colorizer.close() // fecha o recurso JNI mesmo com return non-local
        }
    }
}
