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
