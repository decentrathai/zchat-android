package co.electriccoin.zcash.ui.screen.chat.filesharing

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadProgressTrackerTest {

    @Test
    fun `starts null (idle)`() = runTest {
        val t = UploadProgressTracker()
        assertNull(t.progress.value)
    }

    @Test
    fun `stages advance monotonically from start to finish`() = runTest {
        val t = UploadProgressTracker()
        t.start()
        val start = t.progress.value!!
        t.compressed()
        val compressed = t.progress.value!!
        t.encrypted()
        val encrypted = t.progress.value!!
        t.uploading(0.5f)
        val mid = t.progress.value!!
        t.uploaded()
        val uploaded = t.progress.value!!
        t.sent()
        val sent = t.progress.value!!

        assertTrue(start < compressed, "start=$start compressed=$compressed")
        assertTrue(compressed < encrypted, "compressed=$compressed encrypted=$encrypted")
        assertTrue(encrypted <= mid, "encrypted=$encrypted mid=$mid")
        assertTrue(mid < uploaded, "mid=$mid uploaded=$uploaded")
        assertTrue(uploaded < sent, "uploaded=$uploaded sent=$sent")
        assertEquals(1.0f, sent)
    }

    @Test
    fun `reset returns to null`() = runTest {
        val t = UploadProgressTracker()
        t.start()
        t.compressed()
        t.reset()
        assertNull(t.progress.value)
    }

    @Test
    fun `fail returns to null`() = runTest {
        val t = UploadProgressTracker()
        t.start()
        t.encrypted()
        t.fail()
        assertNull(t.progress.value)
    }

    @Test
    fun `uploading clamps value to encrypted-uploaded range`() = runTest {
        val t = UploadProgressTracker()
        t.start()
        t.compressed()
        t.encrypted()
        val encrypted = t.progress.value!!

        t.uploading(0.0f)
        assertEquals(encrypted, t.progress.value)

        t.uploading(1.0f)
        val atUploaded = t.progress.value!!
        assertTrue(atUploaded < 1.0f, "uploading(1.0) should not hit 1.0 (reserved for sent), got=$atUploaded")
        assertTrue(atUploaded > encrypted)

        // Out-of-range values should clamp
        t.uploading(-5f)
        assertEquals(encrypted, t.progress.value)
        t.uploading(5f)
        assertEquals(atUploaded, t.progress.value)
    }

    @Test
    fun `start marks tracker as active for concurrent-upload guard`() = runTest {
        val t = UploadProgressTracker()
        assertNull(t.progress.value)
        t.start()
        // Any non-null value indicates "upload in progress" for the caller-side guard
        assertTrue(t.progress.value != null)
    }

    @Test
    fun `reset and fail are idempotent`() = runTest {
        val t = UploadProgressTracker()
        t.reset()
        t.fail()
        t.reset()
        assertNull(t.progress.value)

        t.start()
        t.fail()
        t.fail()
        assertNull(t.progress.value)
    }

    @Test
    fun `stage values match documented fractions`() = runTest {
        val t = UploadProgressTracker()
        t.start()
        assertEquals(0.05f, t.progress.value)
        t.compressed()
        assertEquals(0.15f, t.progress.value)
        t.encrypted()
        assertEquals(0.25f, t.progress.value)
        t.uploading(0.5f)
        // encrypted=0.25, uploaded=0.9 → midpoint = 0.25 + 0.5*(0.9-0.25) = 0.575
        assertEquals(0.575f, t.progress.value)
        t.uploaded()
        assertEquals(0.9f, t.progress.value)
        t.sent()
        assertEquals(1.0f, t.progress.value)
    }
}
