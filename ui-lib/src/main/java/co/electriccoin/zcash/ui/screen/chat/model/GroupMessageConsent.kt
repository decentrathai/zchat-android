package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-130 — how long the R5-1 group-message spend consent lasts.
 *
 * The consent names the members of one group the user agreed to pay an on-chain memo for when messaging that group.
 * A member's consent lasts only while the member stays in the group's paid set without leaving the group: it ends when
 * the member's roster row stops being ACTIVE (left, removed, re-invited), and all of it ends when this phone's own row
 * stops being ACTIVE. A member who comes back is asked about again before the next message pays for them.
 */
object GroupMessageConsent {
    /**
     * The part of [consent] that a roster write leaving [members] keeps: the addresses that have an ACTIVE row in
     * [members] that is not ours ([isSelf]), compared in their [canonical] form (the stored consent holds canonical
     * addresses, as the send's recipient list does). Empty when our own rows are listed and none of them is ACTIVE (we
     * left or were removed), and when [members] is empty (a roster that does not parse reads as empty: the consent
     * goes and the next message asks again, the safe side). Never adds an address.
     */
    fun keepFor(
        consent: Set<String>,
        members: List<GroupMember>,
        canonical: (String) -> String,
        isSelf: (String) -> Boolean,
    ): Set<String> {
        if (consent.isEmpty()) return consent
        val (ownRows, otherRows) = members.partition { isSelf(it.address) }
        if (ownRows.isNotEmpty() && ownRows.none { it.status == MemberStatus.ACTIVE }) return emptySet()
        val active =
            otherRows
                .filter { it.status == MemberStatus.ACTIVE }
                .map { canonical(it.address) }
                .toSet()
        return consent.filterTo(LinkedHashSet()) { it in active }
    }
}
