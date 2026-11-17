package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.ui.common.repository.KeystoneFirmwareBelowMinimumException

/**
 * K0-4 (fix-all 2.13.0) — the Keystone firmware floor, ported from upstream zodl-android (KeystoneFirmwareVersion.kt,
 * MOB-1510, at 9f4e719e) with the floor raised to 3.0.2: Ironwood signing needs Keystone firmware 3.0.2 or newer
 * (Zodl support article 49), and every ZChat send after NU6.3 is an Ironwood send.
 *
 * Keystone stamps its firmware version into every signed PCZT's `global.proprietary["keystone:fw_version"]` as three
 * bytes, with the major version raised by [KeystoneFirmwareVersion.STAMPED_MAJOR_OFFSET]. It only refuses: nothing here
 * can make a signature acceptable that was refused before.
 */
data class KeystoneFirmwareStamp(
    val major: Int,
    val minor: Int,
    val build: Int
) {
    override fun toString() = "$major.$minor.$build"
}

/** A Keystone firmware version as the device screen shows it. */
data class KeystoneFirmwareVersion(
    val displayMajor: Int,
    val minor: Int,
    val build: Int
) : Comparable<KeystoneFirmwareVersion> {
    override fun compareTo(other: KeystoneFirmwareVersion): Int =
        compareValuesBy(this, other, { it.displayMajor }, { it.minor }, { it.build })

    override fun toString() = "$displayMajor.$minor.$build"

    companion object {
        /** The stamp's major is the displayed major + 10 (upstream: held at every tag from 2.2.8, device-confirmed). */
        const val STAMPED_MAJOR_OFFSET = 10

        /** The oldest firmware whose signature ZChat accepts. Upstream uses 3.0.1; Ironwood needs 3.0.2. */
        val MINIMUM_SUPPORTED = KeystoneFirmwareVersion(displayMajor = 3, minor = 0, build = 2)

        /** Removes [STAMPED_MAJOR_OFFSET]; a stamp already below it is taken as display numbering. */
        fun fromStamp(stamp: KeystoneFirmwareStamp): KeystoneFirmwareVersion =
            KeystoneFirmwareVersion(
                displayMajor =
                    if (stamp.major >= STAMPED_MAJOR_OFFSET) {
                        stamp.major - STAMPED_MAJOR_OFFSET
                    } else {
                        stamp.major
                    },
                minor = stamp.minor,
                build = stamp.build
            )
    }
}

private val FIRMWARE_VERSION_KEY = "keystone:fw_version".toByteArray(Charsets.US_ASCII)
private const val FIRMWARE_VERSION_VALUE_LENGTH = 3

/**
 * The firmware stamp in a signed PCZT's bytes, or null when there is none (firmware too old to stamp one) or the bytes
 * after the key don't have the expected shape. PCZT proprietary fields are postcard-encoded `BTreeMap<String, Vec<u8>>`
 * entries: a varint key length, the key, a varint value length (0x03 here), then the value.
 */
fun ByteArray.readKeystoneFwStamp(): KeystoneFirmwareStamp? {
    val keyStart = indexOfSubArray(FIRMWARE_VERSION_KEY)
    if (keyStart < 0) return null

    val lengthIndex = keyStart + FIRMWARE_VERSION_KEY.size
    val valueStart = lengthIndex + 1
    val hasValidStamp =
        lengthIndex < size &&
            this[lengthIndex] == FIRMWARE_VERSION_VALUE_LENGTH.toByte() &&
            valueStart + FIRMWARE_VERSION_VALUE_LENGTH <= size

    return if (hasValidStamp) {
        KeystoneFirmwareStamp(
            major = this[valueStart].toInt() and 0xFF,
            minor = this[valueStart + 1].toInt() and 0xFF,
            build = this[valueStart + 2].toInt() and 0xFF,
        )
    } else {
        null
    }
}

private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    return (0..(size - needle.size)).firstOrNull { i ->
        needle.indices.all { j -> this[i + j] == needle[j] }
    } ?: -1
}

/** Whether a signature from [detected] firmware may go on to submission. */
object KeystoneFirmwarePolicy {
    enum class Outcome {
        /** The firmware stamped a version and it meets the floor. */
        OK,

        /** The firmware stamped a version below the floor. */
        UPDATE_REQUIRED,

        /** The firmware stamped no version at all (older than the stamp). */
        LEGACY,
    }

    fun evaluate(
        detected: KeystoneFirmwareVersion?,
        required: KeystoneFirmwareVersion
    ): Outcome {
        if (detected == null) return Outcome.LEGACY
        return if (detected >= required) Outcome.OK else Outcome.UPDATE_REQUIRED
    }
}

/** The check the Keystone signing path runs on the signed PCZT's bytes before it may be stored for submission. */
object KeystoneFirmwareGate {
    /**
     * Returns the signing firmware's version when it is [KeystoneFirmwareVersion.MINIMUM_SUPPORTED] or newer.
     *
     * @throws KeystoneFirmwareBelowMinimumException when it is older, or stamped no version
     */
    @Throws(KeystoneFirmwareBelowMinimumException::class)
    fun requireSupported(signedPczt: ByteArray): KeystoneFirmwareVersion {
        val detected = signedPczt.readKeystoneFwStamp()?.let(KeystoneFirmwareVersion::fromStamp)
        return when (KeystoneFirmwarePolicy.evaluate(detected, KeystoneFirmwareVersion.MINIMUM_SUPPORTED)) {
            KeystoneFirmwarePolicy.Outcome.OK -> checkNotNull(detected)
            KeystoneFirmwarePolicy.Outcome.UPDATE_REQUIRED,
            KeystoneFirmwarePolicy.Outcome.LEGACY -> throw KeystoneFirmwareBelowMinimumException(detected)
        }
    }
}
