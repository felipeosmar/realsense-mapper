package br.senai.realsensemapper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class CaptureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        // Smoke test do AAR — substituído pelo RsCameraManager na Task 7
        android.util.Log.i("RSMapper", "librealsense: " +
            com.intel.realsense.librealsense.RsContext.getVersion())
    }
}
