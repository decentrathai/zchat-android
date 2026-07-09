package co.electriccoin.zcash.ui.screen.chat.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import co.electriccoin.zcash.spackle.Twig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-scoped, in-memory holder for a share that is in flight through the app:
 *
 *  1. An external app (or the AI tab / a forward action) hands us content — either plain text or one or
 *     more images. For images we COPY the bytes into our own cache while the caller's URI grant is still
 *     alive, because that grant dies the moment the source Activity is gone.
 *  2. We stash the resulting [PendingShare] here and route to the [SharePicker] screen.
 *  3. The user picks a contact/group. The picker sets [armedDelivery] and navigates to the chat, whose
 *     Android* composable consumes the armed delivery (once) and actually sends.
 *
 * This is deliberately a plain singleton object rather than a ViewModel: a share can arrive on a COLD
 * start (before Koin graph / any VM exists) via onNewIntent, and it must survive the pick navigation
 * without being tied to a single screen's lifecycle. It is process-scoped only — a genuine process death
 * loses the pending share (the incoming intent is also gone), which is the correct, safe behaviour.
 */
object PendingShareStore {
    /** Max images accepted from a single SEND_MULTIPLE. Extra images are dropped with a toast. */
    const val MAX_IMAGES = 10

    /** Hard cap on a single copied image (25 MB). Larger streams are rejected with a toast. */
    private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024

    private val seq = AtomicLong(0)

    sealed interface PendingShare {
        /** Plain text to land as a composer DRAFT (never auto-sent). */
        data class Text(val text: String) : PendingShare

        /** One or more images already copied into our cache as [files]. */
        data class Images(val files: List<File>) : PendingShare
    }

    /**
     * An image share that the user has already routed to [peerAddress] via the picker, waiting for the
     * chat screen to pick it up and send. Text shares don't need this — they land straight as a draft.
     */
    data class ArmedImageDelivery(val peerAddress: String, val files: List<File>)

    private val _pending = MutableStateFlow<PendingShare?>(null)
    val pending: StateFlow<PendingShare?> = _pending

    private val _armedDelivery = MutableStateFlow<ArmedImageDelivery?>(null)
    val armedDelivery: StateFlow<ArmedImageDelivery?> = _armedDelivery

    fun setPending(share: PendingShare) {
        _pending.value = share
    }

    /** Read-and-clear the pending share (consumed by the picker screen when it appears). */
    fun consumePending(): PendingShare? {
        val v = _pending.value
        _pending.value = null
        return v
    }

    fun clearPending() {
        _pending.value = null
    }

    /**
     * Discard the pending share, DELETING any image bytes we copied into cache (unlike [clearPending],
     * which only drops the reference). Call this on the cancel / Back and null-bail paths so images the
     * user chose NOT to send don't linger as plaintext remnants in app cache. Must NOT be used on the
     * deliver path: deliverTo() hands the SAME File list to armImages() before clearing, so a blanket
     * delete would break the armed delivery.
     */
    fun discardPending() {
        (_pending.value as? PendingShare.Images)?.files?.forEach { runCatching { it.delete() } }
        _pending.value = null
    }

    /** Arm an image delivery for [peerAddress]; the target chat screen consumes it. */
    fun armImages(peerAddress: String, files: List<File>) {
        _armedDelivery.value = ArmedImageDelivery(peerAddress, files)
    }

    /**
     * Read-and-clear the armed delivery IF it targets [peerAddress]. Returns null when nothing is armed
     * or it's for a different chat, so an unrelated chat open never accidentally sends someone else's
     * shared images.
     */
    fun consumeArmedFor(peerAddress: String): List<File>? {
        val v = _armedDelivery.value ?: return null
        if (v.peerAddress != peerAddress) return null
        _armedDelivery.value = null
        return v.files
    }

    fun clearArmed() {
        _armedDelivery.value = null
    }

    /**
     * Build a [PendingShare] from an incoming ACTION_SEND / ACTION_SEND_MULTIPLE intent, copying any image
     * streams into cache WHILE the grant is alive. Returns null when nothing usable was found. [onWarn] is
     * called (on the calling thread) with a user-facing message for oversized/unreadable/capped inputs so
     * the caller can toast it. Must run off the main thread when copying images (does disk I/O).
     */
    fun fromSendIntent(context: Context, intent: Intent, onWarn: (String) -> Unit): PendingShare? {
        val action = intent.action
        val isMultiple = action == Intent.ACTION_SEND_MULTIPLE
        val mime = intent.type ?: ""

        // Text share (single ACTION_SEND, text/plain). EXTRA_TEXT can be a CharSequence.
        if (!isMultiple && mime.startsWith("text/")) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!text.isNullOrBlank()) {
                return PendingShare.Text(text)
            }
            onWarn("Nothing to share — the text was empty.")
            return null
        }

        // Image share(s).
        val uris: List<Uri> = if (isMultiple) {
            @Suppress("DEPRECATION")
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            list.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val single: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            listOfNotNull(single)
        }

        if (uris.isEmpty()) {
            onWarn("This share didn't include an image we can send.")
            return null
        }

        // Reject our OWN FileProvider authority — a hostile intent could point EXTRA_STREAM back at one of
        // our private files to try to exfiltrate it into a chat. Only accept foreign content.
        val ownAuthority = "${context.packageName}.provider"

        val cacheDir = File(context.cacheDir, "share-inbox").apply { mkdirs() }
        val copied = ArrayList<File>(uris.size)
        var skipped = 0
        var overCap = false

        for (uri in uris) {
            if (copied.size >= MAX_IMAGES) {
                overCap = true
                break
            }
            if (uri.authority == ownAuthority) {
                Twig.warn { "Share: refusing our own FileProvider URI" }
                skipped++
                continue
            }
            // SF-5 — only read content:// URIs. A hostile file:///data/data/<pkg>/... EXTRA_STREAM
            // would otherwise be openable under ZCHAT's own UID (the authority check above doesn't
            // catch a file:// scheme), letting a caller exfiltrate our private files into a chat.
            if (uri.scheme != android.content.ContentResolver.SCHEME_CONTENT) {
                Twig.warn { "Share: refusing non-content URI scheme '${uri.scheme}'" }
                skipped++
                continue
            }
            val out = File(cacheDir, "share_${System.currentTimeMillis()}_${seq.incrementAndGet()}")
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use copy@{ input ->
                    var total = 0L
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMAGE_BYTES) {
                                // Reject the whole copy: return from the OUTER (copy@) lambda, not just the
                                // inner outputStream.use — a bare `return@use false` only exited the inner
                                // stream lambda, its value was discarded, and the copy fell through to `true`,
                                // so an oversized share was accepted as a silently-TRUNCATED file.
                                return@copy false
                            }
                            output.write(buf, 0, n)
                        }
                    }
                    true
                } ?: false
            }.getOrElse {
                Twig.warn(it) { "Share: failed to copy incoming image" }
                false
            }
            if (ok && out.length() > 0) {
                copied.add(out)
            } else {
                out.delete()
                skipped++
            }
        }

        if (copied.isEmpty()) {
            onWarn("Couldn't read the shared image — the app that shared it may have revoked access.")
            return null
        }
        if (overCap) {
            onWarn("Only the first $MAX_IMAGES images will be sent.")
        } else if (skipped > 0) {
            onWarn("$skipped image(s) couldn't be read and were skipped.")
        }
        return PendingShare.Images(copied)
    }
}
