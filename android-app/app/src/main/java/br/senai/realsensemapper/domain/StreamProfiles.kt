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
