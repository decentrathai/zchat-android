package co.electriccoin.zcash.preference

/**
 * The encrypted preferences [filename] could not be opened right now: the phone's security system (Android
 * Keystore) or the encrypted file did not answer. Nothing in the file was changed, and a later attempt may work.
 */
class EncryptedPreferencesUnavailableException(
    val filename: String,
    cause: Throwable
) : IllegalStateException(
        "Encrypted preferences $filename cannot be opened right now (${cause.javaClass.simpleName}); " +
            "nothing was changed.",
        cause
    )
