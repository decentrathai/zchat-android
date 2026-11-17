package co.electriccoin.zcash.ui.screen.chat.datasource

/** P2e test support: [ChatWalletOwnership] parts kept in memory, with switches to make writes fail. */
internal class InMemoryOwnerRecord(
    var value: String? = null
) : ChatOwnerRecord {
    var failWrites = false

    override fun owner(): String? = value

    override fun setOwner(tag: String) {
        check(!failWrites) { "owner write failed" }
        value = tag
    }
}

internal class InMemoryBundles : SetAsideBundles {
    val bundles = LinkedHashMap<String, Map<String, Map<String, Any?>>>()
    var failWrites = false

    override fun write(
        tag: String,
        content: Map<String, Map<String, Any?>>
    ) {
        check(!failWrites) { "bundle write failed" }
        bundles[tag] = content.mapValues { (_, entries) -> LinkedHashMap(entries) }
    }

    override fun read(tag: String): Map<String, Map<String, Any?>>? = bundles[tag]

    override fun delete(tag: String) {
        bundles.remove(tag)
    }
}

internal class InMemoryStore(
    override val storeId: String,
    initial: Map<String, Any?> = emptyMap(),
) : WalletScopedStore {
    val entries = LinkedHashMap<String, Any?>(initial)
    var failWrites = false

    override fun exportEntries(): Map<String, Any?> = LinkedHashMap(entries)

    override fun importEntries(entries: Map<String, Any?>) {
        check(!failWrites) { "store $storeId write failed" }
        this.entries.clear()
        this.entries.putAll(entries)
    }
}

internal class InMemoryLiveStores(
    val stores: List<InMemoryStore>
) : LiveChatStores {
    var reloads = 0

    override fun stores(): List<WalletScopedStore> = stores

    override fun reload() {
        reloads++
    }

    /** Every non-empty store, by id — what a user of the current wallet can see. */
    fun visible(): Map<String, Map<String, Any?>> =
        stores.filter { it.entries.isNotEmpty() }.associate { it.storeId to LinkedHashMap(it.entries) }
}

/** D6: the kept-copy tagger of [ChatWalletOwnership]; records each owner it was asked to tag with. */
internal class InMemoryUntaggedMarks : UntaggedChatMarks {
    val taggedWith = mutableListOf<String>()
    var failWrites = false

    override fun tagUntaggedWith(owner: String): Boolean {
        if (failWrites) return false
        taggedWith += owner
        return true
    }
}

internal class InMemoryChatOwnership(
    owner: String? = null,
    stores: List<InMemoryStore> = listOf(InMemoryStore("messages"), InMemoryStore("contacts"), InMemoryStore("keys")),
) {
    val record = InMemoryOwnerRecord(owner)
    val bundles = InMemoryBundles()
    val live = InMemoryLiveStores(stores)
    val marks = InMemoryUntaggedMarks()
    val ownership = ChatWalletOwnership(record, bundles, live, marks)

    fun store(id: String): InMemoryStore = live.stores.single { it.storeId == id }
}
