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
import com.intel.realsense.librealsense.Frame
import com.intel.realsense.librealsense.FrameReleaser
import com.intel.realsense.librealsense.FrameSet
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
    // Modo de exibição do preview: false = imagem 2D; true = nuvem de pontos 3D.
    @Volatile private var pointcloudMode = false
    // Serializa upload() (thread de streaming) com clear()/showPointcloud() (toggle):
    // o GLRenderer tem um Pointcloud interno que o upload usa e o clear libera — sem
    // esta exclusão mútua as duas threads causam use-after-free (SIGSEGV nativo).
    private val previewLock = Any()

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

    /**
     * Alterna a visualização entre imagem 2D e nuvem de pontos 3D. No modo 3D o
     * próprio GLRsSurfaceView renderiza os pontos e trata o giro pelo toque.
     */
    fun setPointcloudMode(enabled: Boolean) {
        pointcloudMode = enabled
        val view = previewView ?: return
        // clear()/showPointcloud() fazem chamadas OpenGL (glDeleteTextures) que exigem o
        // contexto GL: queueEvent as executa na GL thread. O previewLock impede que rodem
        // concomitantes ao upload() da thread de streaming (que usa o mesmo Pointcloud
        // interno) — sem isso há use-after-free. clear() descarta os GLFrames do modo
        // anterior (senão o frame 2D antigo fica ladrilhado ao lado da nuvem) e vem ANTES
        // de showPointcloud(): clear() zera o pointcloud interno, que o showPointcloud
        // recria em seguida.
        view.queueEvent {
            synchronized(previewLock) {
                view.clear()
                view.showPointcloud(enabled)
            }
        }
        logI("Visualização: ${if (enabled) "nuvem de pontos 3D" else "imagem 2D"}")
    }

    /** Modo de visualização atual (true = nuvem de pontos 3D). */
    fun isPointcloudMode(): Boolean = pointcloudMode

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

    // Uma "receita" de streams a tentar. Da mais rica (depth+color+IMU) para a mais
    // simples (só depth): sobre USB 2.0 a banda não comporta o conjunto completo e o
    // start() falha com "Couldn't resolve requests"; caímos para a próxima até uma
    // resolver. Em USB 3.0 a primeira já vinga, preservando color e IMU.
    private data class StreamRecipe(val name: String, val color: Boolean, val imu: Boolean)

    private val streamRecipes = listOf(
        StreamRecipe("depth+color+IMU", color = true, imu = true),
        StreamRecipe("depth+color", color = true, imu = false),
        StreamRecipe("depth", color = false, imu = false),
    )

    // Receita que vingou no último start bem-sucedido. Usada para PRIORIZAR a mesma
    // config na gravação: assim o start com enableRecordToFile acerta na primeira
    // tentativa e o .bag é aberto uma única vez. Sem isso, a escada tentava
    // depth+color+IMU (já apontando o mesmo .bag), falhava no USB2 e só a 2ª receita
    // gravava — o duplo-open deixava o rosbag sem índice (ilegível no PC).
    @Volatile private var lastGoodRecipe: StreamRecipe? = null

    private fun buildConfig(recipe: StreamRecipe, record: Boolean): Config = Config().apply {
        enableStream(StreamType.DEPTH, -1, profile.depthWidth, profile.depthHeight,
            StreamFormat.Z16, profile.fps)
        if (recipe.color) enableStream(StreamType.COLOR, -1, profile.colorWidth, profile.colorHeight,
            StreamFormat.RGB8, profile.fps)
        if (recipe.imu) {
            enableStream(StreamType.GYRO, -1, 0, 0,
                StreamFormat.MOTION_XYZ32F, StreamProfiles.GYRO_FPS)
            enableStream(StreamType.ACCEL, -1, 0, 0,
                StreamFormat.MOTION_XYZ32F, StreamProfiles.ACCEL_FPS)
        }
        if (record) recordFile?.let { enableRecordToFile(it.absolutePath) }
    }

    // Só deve ser chamada a partir da lifecycleExecutor.
    private fun startStreaming(record: Boolean) {
        if (streaming) return
        detectProfile()
        logI("Iniciando pipeline (record=$record)")
        var started: Pipeline? = null
        var lastError: Exception? = null
        // Tenta a última receita boa primeiro (sobretudo na gravação, p/ abrir o .bag
        // uma vez só), depois as demais na ordem padrão, sem repetir.
        val ordered = (listOfNotNull(lastGoodRecipe) + streamRecipes).distinct()
        for (recipe in ordered) {
            // Uma Pipeline nova por tentativa: se start() falhar o handle nativo fica
            // inutilizável, então descartamos e criamos outro para a próxima receita.
            val candidate = Pipeline()
            try {
                buildConfig(recipe, record).use { config -> candidate.start(config) }
                started = candidate
                lastGoodRecipe = recipe
                logI("Pipeline iniciado com a config '${recipe.name}' (record=$record)")
                break
            } catch (e: Exception) {
                lastError = e
                logW("Config '${recipe.name}' não resolveu — tentando a próxima", e)
                try { candidate.close() } catch (_: Exception) {}
            }
        }
        if (started == null) {
            logE("Falha ao iniciar pipeline — nenhuma config resolveu", lastError)
            pipeline = null
            return
        }
        pipeline = started
        streaming = true
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
                        // O lock serializa com o toggle de modo (clear/showPointcloud):
                        // o Pointcloud interno do renderer não pode ser usado aqui
                        // enquanto o clear() o libera na outra thread.
                        synchronized(previewLock) {
                            if (pointcloudMode) {
                                // Nuvem de pontos 3D: envia o frameset BRUTO. Com
                                // showPointcloud(true) o GLRsSurfaceView gera os pontos
                                // internamente (color como textura) e desenha SÓ a nuvem —
                                // frames de vídeo são ignorados. O giro é pelo toque. Não
                                // pré-filtramos aqui para não duplicar o pipeline interno.
                                previewView?.upload(frameSet)
                            } else {
                                // Exibe UM único stream 2D preenchendo o preview. Enviar o
                                // frameset inteiro faz o GLRsSurfaceView ladrilhar todos os
                                // frames (color + depth) lado a lado. Preferimos o color
                                // (enquadramento natural); sem ele, o depth colorizado
                                // (Z16 cru não é exibível diretamente pelo renderer).
                                val colorized = frameSet.applyFilter(colorizer).releaseWith(fr)
                                val single = (firstOrNull(colorized, StreamType.COLOR)
                                    ?: firstOrNull(colorized, StreamType.DEPTH))?.releaseWith(fr)
                                if (single != null) previewView?.upload(single)
                            }
                        }
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

    /** Extrai o primeiro frame de um tipo, ou null se o stream não estiver presente. */
    private fun firstOrNull(set: FrameSet, type: StreamType): Frame? =
        try {
            set.first(type)
        } catch (e: Exception) {
            null
        }
}
