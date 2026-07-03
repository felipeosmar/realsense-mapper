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
    private var activeProfile: StreamProfile? = null

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
        findViewById<FloatingActionButton>(R.id.scansButton).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                startActivity(android.content.Intent(this@CaptureActivity,
                    ScansActivity::class.java))
            }
        }
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
        activeProfile = profile
        usbWarning = if (profile == StreamProfiles.USB2)
            getString(R.string.warn_usb2) else null
        showWarnings(null)
    }

    override fun onFps(fps: Float) = runOnUiThread {
        // Limiar relativo ao fps alvo do perfil ativo (USB3@30 ou USB2@15), não
        // um valor fixo — senão o fallback USB2 (15 fps por design) sempre
        // disparava o aviso de "taxa de frames baixa".
        val target = activeProfile
        lowFps = target != null && camera.state >= CameraState.STREAMING &&
            fps < target.fps * 0.7f
        showWarnings(null)
    }
}
