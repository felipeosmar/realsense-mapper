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
