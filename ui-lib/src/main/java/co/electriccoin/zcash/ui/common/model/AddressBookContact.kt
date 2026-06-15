package co.electriccoin.zcash.ui.common.model

import kotlin.time.Instant

data class AddressBookContact(
    val name: String,
    val address: String,
    val lastUpdated: Instant,
    val chain: String?,
)
