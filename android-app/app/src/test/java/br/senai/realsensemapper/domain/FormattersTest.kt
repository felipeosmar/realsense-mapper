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
