package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D3-2 (fix-all 2.13.0, train T5; t5-specs SPECS §2.2 "Store", §2.4 C4): the Quantum Shield claim store and its wipes.
 *
 * The four records (`qs_claim_peer_`, `qs_claim_our_seq_`, `qs_claim_sent_seq_`, `qs_claim_setup_` + the peer) live in
 * the encrypted E2E store, so the wallet scope, clearAll and the wallet copies carry them. Wanted: each write is
 * synchronous (commit) and lands in that store; clearE2EKeys (Reset Encryption) removes all four in its one edit;
 * clearQuantumShieldPSK (turn off) removes only the setup record, still removes the key and its bound record, and keeps
 * its one edit and one commit (QuantumShieldConfirmationTest pins that); our seq never goes down, not across a turn-off
 * either.
 *
 * A [ZchatPreferencesImpl] with no Context (the R5-2 idiom): its E2E and conversation-mapping stores are in-memory
 * SharedPreferences that record every edit. The new preference methods and the store class are reached by name, so
 * this file compiles before they exist. Synthetic values only.
 */
class QsClaimStoreTest {
    private companion object {
        const val PEER = "usyntheticd32storepeer000001"
        const val STORE_CLASS = "co.electriccoin.zcash.ui.screen.chat.datasource.PrefsQsClaimStore"
        val CLAIM_KEYS = listOf("qs_claim_peer_", "qs_claim_our_seq_", "qs_claim_sent_seq_", "qs_claim_setup_")
    }

    /** One editor's work: what it put and removed, and how often it was committed or applied. */
    private class Edit {
        val puts = LinkedHashMap<String, Any?>()
        val removes = LinkedHashSet<String>()
        var clear = false
        var commits = 0
        var applies = 0
    }

    /** An in-memory SharedPreferences that records each edit. */
    private class MemoryPrefs {
        val values: MutableMap<String, Any?> = Collections.synchronizedMap(LinkedHashMap())
        val edits: MutableList<Edit> = Collections.synchronizedList(mutableListOf())

        private fun land(edit: Edit) {
            synchronized(values) {
                if (edit.clear) values.clear()
                edit.removes.forEach { values.remove(it) }
                edit.puts.forEach { (k, v) -> values[k] = v }
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val edit = Edit().also { edits += it }
            lateinit var self: SharedPreferences.Editor
            self =
                Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { _, method, args ->
                    when (method.name) {
                        "putString", "putLong", "putBoolean", "putInt", "putFloat", "putStringSet" -> {
                            edit.puts[args!![0] as String] = args[1]
                            self
                        }
                        "remove" -> {
                            edit.removes += args!![0] as String
                            self
                        }
                        "clear" -> {
                            edit.clear = true
                            self
                        }
                        "commit" -> {
                            edit.commits++
                            land(edit)
                            true
                        }
                        "apply" -> {
                            edit.applies++
                            land(edit)
                            null
                        }
                        else -> self
                    }
                } as SharedPreferences.Editor
            return self
        }

        val prefs: SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "edit" -> editor()
                    "contains" -> values.containsKey(args!![0] as String)
                    "getAll" -> synchronized(values) { LinkedHashMap(values) }
                    "getString", "getLong", "getBoolean", "getInt", "getFloat", "getStringSet" ->
                        if (values.containsKey(args!![0] as String)) values[args[0] as String] else args[1]
                    else -> null
                }
            } as SharedPreferences
    }

    private class Store {
        val e2e = MemoryPrefs()
        val mapping = MemoryPrefs()
        val impl: ZchatPreferencesImpl = allocate()

        init {
            set("e2ePrefs", e2e.prefs)
            set("convMappingPrefs", mapping.prefs)
            set("_e2eHandshakeTicks", MutableStateFlow(0L))
        }

        private fun set(
            name: String,
            value: Any,
        ) {
            ZchatPreferencesImpl::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .set(impl, value)
        }

        private fun allocate(): ZchatPreferencesImpl {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, ZchatPreferencesImpl::class.java) as ZchatPreferencesImpl
        }

        /** A ZchatPreferences method by name (the new ones do not exist before the fix). */
        fun call(
            name: String,
            vararg args: Any,
        ): Any? {
            val method =
                ZchatPreferences::class.java.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            assertNotNull(method, "ZchatPreferences.$name is missing")
            return unwrap { method.invoke(impl, *args) }
        }

        /** Writes all four claim records for [peer] through the store's preference methods. */
        fun writeAll(peer: String) {
            assertEquals(true, call("setQsClaimPeer", peer, "synthetic-peer-claim-record"))
            assertEquals(true, call("setQsClaimOurSeq", peer, "100|u|synthetic|"))
            assertEquals(true, call("setQsClaimSentSeq", peer, 100L))
            assertEquals(true, call("setQsClaimSetup", peer, "1790000000000|synthetic-commit"))
        }

        fun claimKeys(peer: String): List<String> = CLAIM_KEYS.map { it + peer }.filter { it in e2e.values }
    }

    @Test
    fun `every claim record is committed to the encrypted E2E store`() {
        val s = Store()
        s.writeAll(PEER)

        assertEquals(CLAIM_KEYS.map { it + PEER }, s.claimKeys(PEER), "the four records live in the E2E store")
        assertTrue(s.e2e.edits.all { it.commits == 1 && it.applies == 0 }, "each claim write is one synchronous commit")
        assertEquals("synthetic-peer-claim-record", s.call("getQsClaimPeer", PEER))
        assertEquals("100|u|synthetic|", s.call("getQsClaimOurSeq", PEER))
        assertEquals(100L, s.call("getQsClaimSentSeq", PEER))
        assertEquals("1790000000000|synthetic-commit", s.call("getQsClaimSetup", PEER))
        assertTrue(s.mapping.edits.isEmpty(), "nothing is written anywhere else")
    }

    @Test
    fun `Reset Encryption removes all four records in its one edit`() {
        val s = Store()
        s.writeAll(PEER)
        s.writeAll("usyntheticd32storeother00001")
        s.e2e.edits.clear()

        s.impl.clearE2EKeys(PEER)

        assertEquals(emptyList(), s.claimKeys(PEER), "clearE2EKeys must remove every claim record of the peer")
        assertEquals(4, s.claimKeys("usyntheticd32storeother00001").size, "another peer's records stay")
        val edit = s.e2e.edits.single { "e2e_key_changed_$PEER" in it.removes }
        assertTrue(CLAIM_KEYS.all { it + PEER in edit.removes }, "the records go in the same edit as the keys: ${edit.removes}")
    }

    @Test
    fun `turning the shield off removes only the setup record, with the key, in one commit`() {
        val s = Store()
        s.writeAll(PEER)
        s.impl.setQuantumShieldPSK(PEER, "c3ludGhldGljLXBzaw==")
        s.impl.setQuantumShieldBound(PEER, true)
        s.e2e.edits.clear()

        s.impl.clearQuantumShieldPSK(PEER)

        assertNull(s.impl.getQuantumShieldPSK(PEER))
        assertNull(s.impl.getQuantumShieldBound(PEER))
        assertFalse("qs_claim_setup_$PEER" in s.e2e.values, "turning off removes the setup record")
        assertEquals(
            listOf("qs_claim_peer_$PEER", "qs_claim_our_seq_$PEER", "qs_claim_sent_seq_$PEER"),
            s.claimKeys(PEER),
            "the peer's claim, our seq and our sent seq stay (our seq never goes down)",
        )
        val edit = s.e2e.edits.single()
        assertEquals(1, edit.commits, "one edit, one commit")
        assertEquals(setOf("qs_psk_$PEER", "qs_bound_$PEER", "qs_claim_setup_$PEER"), edit.removes)
    }

    @Test
    fun `our seq never goes down`() {
        val s = Store()
        val store =
            unwrap {
                Class
                    .forName(STORE_CLASS)
                    .getConstructor(ZchatPreferences::class.java)
                    .newInstance(s.impl)
            }
        val commit = store.javaClass.methods.first { it.name == "commitOwnClaim" && it.parameterCount == 3 }
        val read = store.javaClass.methods.first { it.name == "ownClaim" && it.parameterCount == 1 }

        fun seq(): Long? = unwrap { read.invoke(store, PEER) }?.let { it.javaClass.getMethod("getSeq").invoke(it) as Long }

        assertEquals(true, unwrap { commit.invoke(store, PEER, 100L, "u|synthetic|") })
        assertEquals(false, unwrap { commit.invoke(store, PEER, 99L, "n||") }, "a lower seq is refused")
        assertEquals(false, unwrap { commit.invoke(store, PEER, 100L, "n||") }, "the same seq is refused")
        assertEquals(100L, seq())
        s.impl.clearQuantumShieldPSK(PEER)
        assertEquals(100L, seq(), "turning the shield off keeps our seq")
        assertEquals(true, unwrap { commit.invoke(store, PEER, 101L, "n||") })
        assertEquals(101L, seq())
    }

    @Test
    fun `clearAll and the wallet copies carry the records with the E2E store`() {
        val src =
            File(repoRoot(), "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/ZchatPreferences.kt").readText()
        val clearAll = src.substring(src.indexOf("private fun clearAllStores()"), src.indexOf("override fun walletScopedStores()"))
        assertTrue("e2ePrefs.edit().clear().commit()" in clearAll, "clearAll wipes the E2E store")
        val scoped = src.substring(src.indexOf("override fun walletScopedStores()"))
        assertTrue("E2E_PREFS_NAME to e2ePrefs" in scoped.substring(0, scoped.indexOf(")\n")), "the E2E store is wallet scoped")
        CLAIM_KEYS.forEach { key ->
            val writes = Regex("(\\w+)\\.edit\\(\\)[^\\n]*\"$key").findAll(src).map { it.groupValues[1] }.toSet()
            assertEquals(setOf("e2ePrefs"), writes, "$key is written only to the E2E store")
        }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "repo root not found" }
    }
}

/** Runs a reflective call and rethrows what the called code threw. */
private fun <T> unwrap(block: () -> T): T =
    try {
        block()
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
