package br.senai.realsensemapper.domain

/** Limiar da spec: parar gravação abaixo de 2 GiB; avisar abaixo de 4 GiB. */
object StorageGuard {
    enum class Level { OK, LOW, CRITICAL }

    private const val GIB = 1024L * 1024 * 1024
    const val CRITICAL_BYTES = 2 * GIB
    const val LOW_BYTES = 4 * GIB

    fun check(freeBytes: Long): Level = when {
        freeBytes < CRITICAL_BYTES -> Level.CRITICAL
        freeBytes < LOW_BYTES -> Level.LOW
        else -> Level.OK
    }
}
