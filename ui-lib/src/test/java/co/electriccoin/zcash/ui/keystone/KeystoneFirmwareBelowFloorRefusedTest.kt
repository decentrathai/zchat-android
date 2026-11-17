package co.electriccoin.zcash.ui.keystone

import cash.z.ecc.android.sdk.model.Pczt
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * K0-4 (fix-all 2.13.0, train T1) — the firmware floor on the one Keystone path 2.13.0 keeps open (Wallet → Send and
 * Shield). Ironwood signing needs Keystone firmware 3.0.2 or newer (Zodl support article 49); ZChat accepted a
 * signature from any firmware.
 *
 * Keystone stamps its firmware into the signed PCZT's `global.proprietary["keystone:fw_version"]` as three bytes, with
 * the major version raised by 10 (upstream zodl-android KeystoneFirmwareVersion, MOB-1510). The gate reads the stamp,
 * converts it to the version the device shows, and refuses:
 * - 3.0.1 (stamped 13.0.1) and anything older, such as 2.9.9 (stamped 12.9.9);
 * - a PCZT with no stamp (firmware too old to stamp one);
 * and accepts 3.0.2 and 3.1.0. A refused signature is never stored for submission, so nothing can be sent with it.
 *
 * Only the refusal is provable here. The accept path through a real device's scan-back needs a Keystone 3 Pro.
 * KeystoneFirmwareGate and KeystoneProposalRepositoryImpl.acceptSignedPczt are read by name, so this file compiles
 * before they exist.
 */
class KeystoneFirmwareBelowFloorRefusedTest {
    @Test
    fun `firmware below 3_0_2 and an unstamped PCZT are refused`() {
        for ((label, bytes) in REFUSED) {
            val thrown = runCatching { requireSupported(bytes) }.exceptionOrNull()
            assertTrue(
                "$label must be refused with KeystoneFirmwareBelowMinimumException, got $thrown",
                thrown?.javaClass?.name == BELOW_MINIMUM,
            )
        }
    }

    @Test
    fun `firmware 3_0_2 and newer is accepted`() {
        for ((label, bytes) in ACCEPTED) {
            val thrown = runCatching { requireSupported(bytes) }.exceptionOrNull()
            assertNull("$label must be accepted, got $thrown", thrown)
        }
    }

    @Test
    fun `a refused signature is never stored for submission`() =
        runBlocking {
            val repository = repository()

            val thrown = runCatching { accept(repository, stamped(13, 0, 1)) }.exceptionOrNull()

            assertEquals(BELOW_MINIMUM, thrown?.javaClass?.name)
            assertNull("a 3.0.1 signature was stored for submission", signatures(repository))

            accept(repository, stamped(13, 0, 2))
            assertNotNull("a 3.0.2 signature must be stored for submission", signatures(repository))
        }

    private fun requireSupported(bytes: ByteArray) {
        val gate = Class.forName(GATE)
        try {
            gate.getMethod("requireSupported", ByteArray::class.java).invoke(gate.getField("INSTANCE").get(null), bytes)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private suspend fun repository(): KeystoneProposalRepositoryImpl =
        KeystoneProposalRepositoryImpl(
            accountDataSource = TwoAccountDataSource.keystoneSelected(),
            proposalDataSource = Zc101RecordingProposalDataSource(),
        )

    private fun accept(
        repository: KeystoneProposalRepositoryImpl,
        bytes: ByteArray,
    ) {
        try {
            KeystoneProposalRepositoryImpl::class.java.getMethod("acceptSignedPczt", ByteArray::class.java).invoke(repository, bytes)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun signatures(repository: KeystoneProposalRepositoryImpl): Pczt? =
        KeystoneProposalRepositoryImpl::class.java
            .getDeclaredField("pcztWithSignatures")
            .apply { isAccessible = true }
            .get(repository) as Pczt?

    private companion object {
        const val GATE = "co.electriccoin.zcash.ui.common.model.KeystoneFirmwareGate"
        const val BELOW_MINIMUM = "co.electriccoin.zcash.ui.common.repository.KeystoneFirmwareBelowMinimumException"

        /** Synthetic PCZT bytes around the stamp: a proprietary-map entry `keystone:fw_version` → 3 bytes. */
        fun stamped(
            major: Int,
            minor: Int,
            build: Int,
        ): ByteArray =
            byteArrayOf(0x50, 0x43, 0x5A, 0x54, 0x01, 0x13) +
                "keystone:fw_version".toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x03, major.toByte(), minor.toByte(), build.toByte()) +
                byteArrayOf(0x00, 0x7F)

        val REFUSED =
            listOf(
                "3.0.1 (stamped 13.0.1)" to stamped(13, 0, 1),
                "3.0.0 (stamped 13.0.0)" to stamped(13, 0, 0),
                "2.9.9 (stamped 12.9.9)" to stamped(12, 9, 9),
                "an unstamped PCZT" to byteArrayOf(0x50, 0x43, 0x5A, 0x54, 0x01, 0x00, 0x7F),
                "a stamp with the wrong length byte" to
                    "keystone:fw_version".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x04, 13, 0, 2, 0),
            )
        val ACCEPTED =
            listOf(
                "3.0.2 (stamped 13.0.2)" to stamped(13, 0, 2),
                "3.1.0 (stamped 13.1.0)" to stamped(13, 1, 0),
                "3.0.4 (stamped 13.0.4)" to stamped(13, 0, 4),
            )
    }
}
