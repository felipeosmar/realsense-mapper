package br.senai.realsensemapper.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger de diagnóstico para os testes com a câmera real. Cada evento vai para
 * dois destinos ao mesmo tempo:
 *
 *  - **Logcat**, sob a tag única [LOGCAT_TAG] — visível por `adb logcat` quando
 *    há adb sem fio ou um hub USB (ver `capture-logcat.sh`).
 *  - **Arquivo** em `getExternalFilesDir("logs")/session_<data>.log` — o caminho
 *    de recuperação quando a única porta USB do celular está ocupada pela D435i:
 *    basta copiar o arquivo por MTP depois do teste.
 *
 * Thread-safe: é chamado da UI thread, da lifecycleExecutor, da thread rs-stream
 * e do callback USB. As escritas no arquivo são serializadas por um lock; cada
 * linha é gravada e liberada na hora (append + flush implícito), de modo que o
 * log sobrevive a um crash ou à retirada abrupta do cabo.
 */
class AppLogger(context: Context) {

    /** Arquivo desta sessão; null se o armazenamento externo não estiver disponível. */
    val logFile: File? = createLogFile(context)

    private val lock = Any()
    private val lineClock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, message: String) = write('I', tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write('W', tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write('E', tag, message, error)

    private fun write(level: Char, tag: String, message: String, error: Throwable?) {
        val body = "[$tag] $message"
        when (level) {
            'E' -> Log.e(LOGCAT_TAG, body, error)
            'W' -> Log.w(LOGCAT_TAG, body, error)
            else -> Log.i(LOGCAT_TAG, body)
        }

        val file = logFile ?: return
        val line = buildString {
            append('[').append(lineClock.format(Date())).append("] ")
            append(level).append(" [").append(tag).append("] ").append(message)
            if (error != null) append("\n").append(Log.getStackTraceString(error).trimEnd())
        }
        synchronized(lock) {
            try {
                file.appendText(line + "\n")
            } catch (io: IOException) {
                Log.e(LOGCAT_TAG, "Falha ao escrever no arquivo de log", io)
            }
        }
    }

    private fun createLogFile(context: Context): File? = try {
        context.getExternalFilesDir("logs")?.let { dir ->
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "session_$stamp.log")
        }
    } catch (e: Exception) {
        Log.e(LOGCAT_TAG, "Não foi possível criar o arquivo de log", e)
        null
    }

    companion object {
        /** Tag única no Logcat. Filtre com: `adb logcat -s RSMapper:V`. */
        const val LOGCAT_TAG = "RSMapper"
    }
}
