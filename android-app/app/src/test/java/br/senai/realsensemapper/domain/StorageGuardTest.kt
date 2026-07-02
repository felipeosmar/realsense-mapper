package br.senai.realsensemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageGuardTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun plenty_is_ok() = assertEquals(StorageGuard.Level.OK, StorageGuard.check(10 * gib))
    @Test fun below_4gib_is_low() = assertEquals(StorageGuard.Level.LOW, StorageGuard.check(3 * gib))
    @Test fun below_2gib_is_critical() =
        assertEquals(StorageGuard.Level.CRITICAL, StorageGuard.check(1 * gib))
    @Test fun boundary_2gib_is_low() =
        assertEquals(StorageGuard.Level.LOW, StorageGuard.check(2 * gib))
}
