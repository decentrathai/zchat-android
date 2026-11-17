package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-545 (WAIVERS.md W-32) — what the key-changed dialog shows while a contact's refused key is remembered
 * (ChatViewModel.refusedKeyOffer). The dialog keeps the instance it opened with and passes [refusedKey] and [ourKey]
 * back to the accept, which adopts only that pair.
 *
 * @param ourKey our public key for the contact (Base64), one half of [safetyNumber]
 * @param refusedKey the contact's refused key (Base64), the other half; the accept compares the whole string
 * @param safetyNumber the safety number of ([ourKey], [refusedKey]): the number of the pair after an accept
 * @param firstSeenMs when the key first reached this phone
 * @param laterOffers how many offers with a different key arrived since; they were not kept
 * @param fromKexAck the key came in a KEXACK (the contact already holds our key), not in a KEX
 * @param contactIsOlder the app knows the contact to be on ZChat 2.12.4 or older
 * @param oldKeyStored the contact's old key is still stored; false after an accept that was interrupted, when "Not
 * their key" is no longer possible
 */
data class RefusedKeyOfferView(
    val ourKey: String,
    val refusedKey: String,
    val safetyNumber: String,
    val firstSeenMs: Long,
    val laterOffers: Int,
    val fromKexAck: Boolean,
    val contactIsOlder: Boolean,
    val oldKeyStored: Boolean,
)
