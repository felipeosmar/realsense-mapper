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
        try {
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
        } finally {
            colorizer.close()  // fecha o recurso JNI mesmo com return non-local
        }
    }
}
