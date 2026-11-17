package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.screen.chat.viewmodel.EntryView
import co.electriccoin.zcash.ui.screen.chat.viewmodel.LedgerNames
import co.electriccoin.zcash.ui.screen.chat.viewmodel.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.test.fail

/**
 * F8 R25 (ZC-600) — where the send-attempt ledger lives: no address-shaped text of an entry in clear on disk.
 *
 * A ledger entry names a contact's address (or a group and a member), carries a Join's held reply (the inviter's
 * address, ours and the signed memo) and transaction ids. The conversation-mode file, where the markers of these
 * senders live, is a plain app-private file. The ledger must not go there: its entries belong in the encrypted
 * held-reply store, under their own key prefix, where the wipe and the wallet scope already cover them.
 *
 * Run on a real [ZchatPreferencesImpl] whose every SharedPreferences file is in memory ([realPreferences]), with the
 * ledger reached by name. The twin of HeldGroupAcceptStoredEncryptedTest. On a build with no ledger it stops with its
 * own message.
 *
 * Every address, group id, memo and transaction id here is made in the test.
 */
class LedgerEntriesAreStoredEncryptedAndNoPlainFileHoldsThemTest {
    private fun ledgerOf(impl: ZchatPreferencesImpl): Any =
        ZchatPreferencesImpl::class.java.methods
            .firstOrNull { it.name == "getSendAttemptLedger" }
            ?.invoke(impl)
            ?: fail(
                "ZchatPreferencesImpl has no `sendAttemptLedger`: the automatic senders have no durable record of an " +
                    "attempt at all, and nothing says where one would be kept (it must be the encrypted held-reply store)",
            )

    private fun call(
        target: Any,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method = target.javaClass.methods.first { it.name == name && it.parameterCount == args.size }
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun kinds(): List<Any> = Class.forName(LedgerNames.KIND).enumConstants!!.toList()

    private fun tag(
        kind: Any,
        subject: String,
        identity: String,
        payload: String?,
    ): Any =
        Class
            .forName(LedgerNames.TAG)
            .constructors
            .single { it.parameterCount == TAG_PROPERTIES }
            .newInstance(kind, subject, identity, null, null, payload)

    /** One entry of every kind, written the way its sender writes it: begin, then the ids commit. */
    private fun writeOneOfEveryKind(ledger: Any): Int {
        val always: (Any) -> Boolean = { true }
        var written = 0
        for ((i, kind) in kinds().withIndex()) {
            val name = kind.toString()
            val subject = if (name.startsWith("GROUP") || name == "KEY_GRANT" || name == "KEY_MEMO") "$GROUP|${address(i)}" else address(i)
            val payload = if (name == "GROUP_ACCEPT") ACCEPT_PAYLOAD else null
            if (name == "ROTATION") {
                // The write-ahead record: an entry written as it is. Built from a begun one of another key.
                val claim =
                    call(
                        ledger,
                        "begin",
                        tag(kinds().first(), subject, "rotation-seed", null),
                        9_000L,
                        HEIGHT,
                        "process",
                        ACCOUNT,
                        null,
                    )
                val seed = EntryView(call(ledger, "get", "${kinds().first()}|$subject|rotation-seed")!!)
                call(ledger, "finish", claim)
                call(ledger, "discard", seed.key, seed.startedAt)
                assertTrue("the write-ahead record is written", call(ledger, "writeAhead", seed.with("kind" to kind)) as Boolean)
            } else {
                val claim = call(ledger, "begin", tag(kind, subject, "identity-$i", payload), 1_000L + i, HEIGHT, "process", ACCOUNT, null)
                call(ledger, "recordStored", claim, listOf(TX_A, TX_B), mapOf(TX_A to HEIGHT + 40, TX_B to HEIGHT + 40))
                call(ledger, "finish", claim)
                if (i == 0) {
                    val entry = EntryView(call(ledger, "get", "$kind|$subject|identity-$i")!!)
                    assertTrue(call(ledger, "markNotSent", entry.key, entry.startedAt, HEIGHT + 60, always) as Boolean)
                }
            }
            written++
        }
        return written
    }

    @Test
    fun `no plain file holds anything of an entry, and every entry is in the held-reply store under its own prefix`() {
        val (impl, files) = realPreferences()
        val ledger = ledgerOf(impl)

        val written = writeOneOfEveryKind(ledger)

        val held = files.getValue(HELD_STORE_FIELD)
        for ((name, file) in files) {
            if (name == HELD_STORE_FIELD) continue
            assertTrue("the store `$name` holds something of a ledger entry: ${file.snapshot().keys}", file.snapshot().isEmpty())
            for ((key, value) in file.snapshot()) {
                val text = key + "\u0000" + value
                assertTrue("an address-shaped string in `$name`", ADDRESS_SHAPE.find(text) == null)
                assertTrue("an entry's group, memo or id in `$name`", listOf(GROUP, MEMO, TX_A, TX_B).none { it in text })
            }
        }
        assertEquals("one stored record per entry", written, held.snapshot().size)
        assertTrue(
            "every record is under the ledger's own prefix: ${held.snapshot().keys.map { it.take(12) }}",
            held.snapshot().keys.all { it.startsWith(PREFIX) },
        )

        @Suppress("UNCHECKED_CAST")
        val all = (call(ledger, "all") as List<Any>).map(::EntryView)
        assertEquals("every entry reads back", written, all.size)
        val accept = all.single { it.kind == "GROUP_ACCEPT" }
        assertEquals(ACCEPT_PAYLOAD, accept.payload)
        assertEquals(listOf(TX_A, TX_B), accept.txIds)
        assertEquals(mapOf(TX_A to HEIGHT + 40, TX_B to HEIGHT + 40), accept.expiries)
        assertEquals("NOT_SENT", all.single { it.kind == kinds().first().toString() }.state)

        // The held-reply store's own readers do not see the entries, and their records do not show as entries.
        assertTrue("getHeldGroupAccepts must not list a ledger entry", impl.getHeldGroupAccepts().isEmpty())
        assertNull("getGroupInviteNeedsJoin must not read a ledger entry", impl.getGroupInviteNeedsJoin(GROUP))
        assertTrue(impl.setHeldGroupAccept(GROUP, ACCEPT_PAYLOAD))
        assertTrue(impl.setGroupInviteNeedsJoin(GROUP, ACCEPT_PAYLOAD))
        @Suppress("UNCHECKED_CAST")
        assertEquals("a held reply and a waiting record beside them are not entries", written, (call(ledger, "all") as List<Any>).size)
    }

    @Test
    fun `the ledger is built over the encrypted held-reply store and names no plain file`() {
        ledgerOf(realPreferences().first)
        val lines = sourceOf("ZchatPreferences.kt").lines()
        val declaration = lines.firstOrNull { it.contains("val $HELD_STORE_FIELD") } ?: fail("no `$HELD_STORE_FIELD` declaration")
        assertTrue("the held-reply store is opened with createEncryptedPrefs: $declaration", declaration.contains("createEncryptedPrefs"))
        val built = lines.filter { it.contains("PrefsSendAttemptLedger {") || it.contains("PrefsSendAttemptLedger(") }
        assertTrue("ZchatPreferences.kt builds a PrefsSendAttemptLedger", built.isNotEmpty())
        for (line in built) {
            assertTrue("the ledger is built over the held-reply store: $line", line.contains(HELD_STORE_FIELD))
            assertTrue(
                "the ledger never names the plain mode file: $line",
                !line.contains("modePrefs") && !line.contains("getSharedPreferences"),
            )
        }
    }

    @Test
    fun `a commit that returns false and a commit that throws are both a failed begin, and nothing is stored`() {
        for (throwing in listOf(false, true)) {
            val (impl, files) = realPreferences()
            val backing = files.getValue(HELD_STORE_FIELD)
            ZchatPreferencesImpl::class.java
                .getDeclaredField(HELD_STORE_FIELD)
                .apply { isAccessible = true }
                .set(impl, failingStore(backing, throwing))
            val ledger = ledgerOf(impl)

            val failure =
                runCatching {
                    call(ledger, "begin", tag(kinds().first(), address(1), "identity", null), 1_000L, HEIGHT, "process", ACCOUNT, null)
                }.exceptionOrNull()

            assertEquals(
                "a failed write of the encrypted store (throwing=$throwing) must be reported as one failed ledger write",
                LedgerNames.WRITE_FAILED,
                failure?.javaClass?.name,
            )
            assertTrue("nothing of the entry is stored anywhere", files.values.all { it.snapshot().isEmpty() })
        }
    }

    @Test
    fun `a wipe removes the entries, the wallet scope carries them with the held-reply store only, and an upgrade starts empty`() {
        val (impl, files) = realPreferences()
        val ledger = ledgerOf(impl)
        // An install upgraded from 2.12.4 has no held-reply file: the ledger is empty and a key has no entry.
        @Suppress("UNCHECKED_CAST")
        assertTrue((call(ledger, "all") as List<Any>).isEmpty())
        assertNull(call(ledger, "get", "KEX|${address(1)}|identity"))

        writeOneOfEveryKind(ledger)
        val carrying =
            impl
                .walletScopedStores()
                .filter { store -> store.exportEntries().keys.any { it.startsWith(PREFIX) } }
                .map { it.storeId }
        assertEquals("exactly the held-reply store carries the entries with the wallet", listOf(HELD_STORE_FILE), carrying)

        impl.clearAll()
        assertTrue(
            "a wipe leaves no ledger entry",
            files
                .getValue(HELD_STORE_FIELD)
                .snapshot()
                .keys
                .none { it.startsWith(PREFIX) },
        )
        @Suppress("UNCHECKED_CAST")
        assertTrue((call(ledger, "all") as List<Any>).isEmpty())
    }

    /** [backing] with an editor whose commit never reaches the disk: it returns false, or throws as a store that cannot encrypt. */
    private fun failingStore(
        backing: MemorySharedPreferences,
        throwing: Boolean,
    ): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name != "edit") {
                return@newProxyInstance try {
                    method.invoke(backing, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
            lateinit var editor: Any
            editor =
                Proxy.newProxyInstance(
                    SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java),
                ) { _, call, _ ->
                    when (call.name) {
                        "commit" -> if (throwing) throw SecurityException("synthetic: the store cannot encrypt") else false
                        "apply" -> null
                        else -> editor
                    }
                }
            editor
        } as SharedPreferences

    private fun sourceOf(name: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val file =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/java/co/electriccoin/zcash/ui/screen/chat/datasource/$name") }
                .firstOrNull { it.isFile }
                ?: fail("could not find $name from ${start.path}")
        return file.readText()
    }

    private companion object {
        const val HELD_STORE_FIELD = "heldGroupAcceptPrefs"
        const val PREFIX = "sendattempt:"
        const val HELD_STORE_FILE = "zchat_held_group_accepts_encrypted"
        const val TAG_PROPERTIES = 6
        const val HEIGHT = 3_200_000L
        const val ACCOUNT = "0123456789abcdef"
        const val GROUP = "synthetic-ledger-group-000001"
        const val MEMO = "ZGRP:synthetic-signed-accept-memo-made-in-this-test"
        val TX_A = "a1".repeat(32)
        val TX_B = "b2".repeat(32)

        /** An address-shaped string made here: `u1` and 70 characters of [0-9a-z]; never a wallet's. */
        fun address(n: Int): String = "u1" + ("synthetic0ledger0address" + ('a' + n % 26)).repeat(4).take(70)

        val ACCEPT_PAYLOAD: String = """{"inviter":"${address(20)}","accepter":"${address(21)}","memo":"$MEMO","relay_failed":true}"""
        val ADDRESS_SHAPE = Regex("(u1|zs1|t1|tex1)[0-9a-z]{20,}")
    }
}
