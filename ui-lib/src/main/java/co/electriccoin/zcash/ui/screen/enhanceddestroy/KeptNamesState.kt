package co.electriccoin.zcash.ui.screen.enhanceddestroy

import co.electriccoin.zcash.ui.design.util.StringResource

/** One kept name as the screen shows it: the name and the short address. */
data class KeptNameItem(
    val address: String,
    val name: String,
    val shortAddress: StringResource,
)

/**
 * DESTROY-ADV-N (ZC-722; spec revision 2c, 5.2): the screen "Names you kept".
 *
 * [entries] holds only names whose address is not a saved contact of this wallet. [unreadable]: the list cannot be
 * read; the screen then shows its message and only "Erase the whole list". [allSaved]: every name on the list is
 * already a contact. [notice] is a string id shown once (saved, could not be saved, could not be changed).
 */
data class KeptNamesState(
    val entries: List<KeptNameItem>,
    val unreadable: Boolean,
    val allSaved: Boolean,
    val eraseConfirmShown: Boolean,
    val notice: Int?,
    val onBack: () -> Unit,
)
