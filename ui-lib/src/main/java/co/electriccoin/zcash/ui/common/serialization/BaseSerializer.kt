package co.electriccoin.zcash.ui.common.serialization

import java.io.InputStream
import java.nio.ByteBuffer

abstract class BaseSerializer {
    protected fun Int.createByteArray(): ByteArray = this.toLong().createByteArray()

    protected fun Long.createByteArray(): ByteArray =
        ByteBuffer
            .allocate(Long.SIZE_BYTES)
            .order(ADDRESS_BOOK_BYTE_ORDER)
            .putLong(this)
            .array()

    protected fun String?.createByteArray(): ByteArray {
        val byteArray = this?.toByteArray() ?: ByteArray(0)
        return byteArray.size.createByteArray() + byteArray
    }

    protected fun InputStream.readInt(): Int = readLong().toInt()

    protected fun InputStream.readLong(): Long {
        val buffer = ByteArray(Long.SIZE_BYTES)
        this.readFullyOrThrow(buffer)
        return ByteBuffer.wrap(buffer).order(ADDRESS_BOOK_BYTE_ORDER).getLong()
    }

    protected fun InputStream.readString(): String {
        val size = this.readInt()
        if (size == 0) return ""
        val buffer = ByteArray(size)
        this.readFullyOrThrow(buffer)
        return String(buffer)
    }

    /**
     * Fills [buffer] completely, looping over [InputStream.read] which is contractually allowed to
     * return fewer bytes than requested in a single call. A single `read(buffer)` plus a size check
     * misdiagnoses a legal short read as corrupt input and aborts address-book/metadata decryption.
     * Only a genuine premature EOF (read == -1) is treated as corruption.
     */
    private fun InputStream.readFullyOrThrow(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = this.read(buffer, offset, buffer.size - offset)
            require(read >= 0) { "Input is too short" }
            offset += read
        }
    }
}
