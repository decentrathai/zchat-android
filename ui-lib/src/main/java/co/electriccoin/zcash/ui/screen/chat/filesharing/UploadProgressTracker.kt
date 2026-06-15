package co.electriccoin.zcash.ui.screen.chat.filesharing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks progress of an image upload through discrete stages.
 *
 * Stage fractions:
 *   start       0.05
 *   compressed  0.15
 *   encrypted   0.25
 *   uploading   0.25 → 0.9 (linear in the bytes-uploaded fraction)
 *   uploaded    0.9
 *   sent        1.0
 *
 * `null` means idle.
 *
 * Concurrency: use [tryStart] to claim the slot atomically. The non-atomic [start] is kept
 * for tests that exercise stage transitions without contention.
 */
class UploadProgressTracker {
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    /**
     * Atomically transition idle → start. Returns false if another upload already holds the
     * slot — caller must abort to honor the concurrent-upload guard.
     */
    fun tryStart(): Boolean = _progress.compareAndSet(null, START)

    fun start() { _progress.value = START }
    fun compressed() { _progress.value = COMPRESSED }
    fun encrypted() { _progress.value = ENCRYPTED }

    fun uploading(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        _progress.value = ENCRYPTED + clamped * (UPLOADED - ENCRYPTED)
    }

    fun uploaded() { _progress.value = UPLOADED }
    fun sent() { _progress.value = SENT }
    fun reset() { _progress.value = null }

    companion object {
        const val START = 0.05f
        const val COMPRESSED = 0.15f
        const val ENCRYPTED = 0.25f
        const val UPLOADED = 0.9f
        const val SENT = 1.0f
    }
}
