package br.senai.realsensemapper.domain

import java.util.Calendar
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixedDate(): Date =
        Calendar.getInstance().apply { set(2026, Calendar.JULY, 2, 10, 30, 0) }.time

    @Test fun new_scan_file_uses_timestamp_name() {
        val repo = ScanRepository(tmp.root, clock = ::fixedDate)
        val f = repo.newScanFile()
        assertEquals("scan_20260702_103000.bag", f.name)
        assertTrue(f.parentFile!!.isDirectory)
    }

    @Test fun list_scans_newest_first() {
        val repo = ScanRepository(tmp.root)
        val a = tmp.newFile("scan_20260701_090000.bag").apply { writeText("aa") }
        val b = tmp.newFile("scan_20260702_090000.bag").apply { writeText("bbbb") }
        a.setLastModified(1_000_000)
        b.setLastModified(2_000_000)

        val scans = repo.listScans()
        assertEquals(listOf(b.name, a.name), scans.map { it.name })
        assertEquals(4L, scans[0].sizeBytes)
    }

    @Test fun list_ignores_non_bag_files() {
        val repo = ScanRepository(tmp.root)
        tmp.newFile("notes.txt")
        assertEquals(0, repo.listScans().size)
    }

    @Test fun delete_removes_file() {
        val repo = ScanRepository(tmp.root)
        tmp.newFile("scan_20260702_090000.bag")
        val scan = repo.listScans().single()
        assertTrue(repo.delete(scan))
        assertFalse(scan.file.exists())
        assertEquals(0, repo.listScans().size)
    }
}
