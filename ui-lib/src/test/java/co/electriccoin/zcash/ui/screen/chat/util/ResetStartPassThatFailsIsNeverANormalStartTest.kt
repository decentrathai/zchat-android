package co.electriccoin.zcash.ui.screen.chat.util

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * ZC-090-P (register ZC-090, high; review REVIEW_T7aR_2_20261020.md finding 3): the Reset's start pass deletes the
 * phrase store and then works out what else it deletes (its plan, `ResetWipe.Plan`) outside any catch of its own; if
 * that throws, `atProcessStart` only logs it and the outcome stays NONE. The start then goes on as an ordinary start:
 * the start-up check lets the wallet through, nothing holds background sync, and a Reset half done (no phrase store,
 * everything else still there) looks like a phone with no wallet. The rule: a pass that fails while its record is
 * still on the phone answers NOT_FINISHED (the Reset's own screen, Try again runs the pass again) and holds background
 * sync, as a pass that finds files left does.
 *
 * The plan step is made to fail through its replaceable call (`ResetWipe.makePlan`, reached by name): nothing on a
 * JVM phone makes the real one throw. The phone is [FakePhone]; tags are synthetic.
 */
class ResetStartPassThatFailsIsNeverANormalStartTest {
    private val phones = ArrayList<FakePhone>()
    private var pristinePlan: Any? = null

    @After
    fun tearDown() {
        if (pristinePlan != null) planField().set(wipeInstance(), pristinePlan)
        phones.forEach { it.close() }
        setOutcome("NONE")
    }

    @Test
    fun `the pass fails after the phrase store was deleted - NOT_FINISHED, the record stays, background sync held`() {
        val phone = phoneWithRecord()
        phone.setWipeField("backgroundSyncHeld", false)
        planFails()

        pass(phone)

        assertFalse("the failure was not after the phrase store's delete", File(phone.root, PHRASE).exists())
        assertTrue("the record went", File(phone.root, RECORD).exists())
        assertEquals("a pass that failed with its record there is taken for an ordinary start", "NOT_FINISHED", outcome())
        assertEquals("background sync is not held after the failed pass", true, phone.wipeField("backgroundSyncHeld"))
    }

    @Test
    fun `control - the same failing plan on a phone with no record - an ordinary start, nothing deleted`() {
        val phone = FakePhone().also { phones += it }
        phone.put(PHRASE, PHRASE_XML)
        planFails()

        pass(phone)

        assertTrue("the phrase store went with no record", File(phone.root, PHRASE).exists())
        assertEquals("NONE", outcome())
    }

    // ------------------------------------------------------------------ the phone

    private fun phoneWithRecord(): FakePhone {
        val phone = FakePhone().also { phones += it }
        phone.put(PHRASE, PHRASE_XML)
        phone.put("data/shared_prefs/zchat_preferences.xml")
        phone.put(RECORD, record(phone.installTime, STORE, OWN))
        return phone
    }

    private fun record(
        install: Long,
        store: String,
        owner: String,
    ): String {
        val body = "zchat-reset 1\ninstall=$install\nstore=$store\nkeep=none\nowner=$owner\nfiles=\n"
        return body + "end=" + FakePhone.sha256Hex(body) + "\n"
    }

    // ------------------------------------------------------------------ the product, by name

    private fun wipeClass(): Class<*> =
        runCatching { Class.forName(RESET_WIPE) }.getOrNull() ?: throw AssertionError("the object ResetWipe does not exist")

    private fun wipeInstance(): Any = checkNotNull(wipeClass().getField("INSTANCE").get(null))

    private fun planField() =
        (
            wipeClass().declaredFields.firstOrNull { it.name == PLAN_CALL }
                ?: throw AssertionError("ResetWipe has no replaceable plan step ($PLAN_CALL)")
        ).apply { isAccessible = true }

    private fun planFails() {
        val field = planField()
        if (pristinePlan == null) pristinePlan = field.get(wipeInstance())
        field.set(wipeInstance(), { _: File, _: Any -> throw IllegalStateException("synthetic: the plan cannot be worked out") })
    }

    private fun pass(phone: FakePhone) {
        try {
            wipeClass().getMethod("atProcessStart", Context::class.java).invoke(wipeInstance(), phone.context)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun outcome(): String = (wipeClass().getMethod("getStartOutcome").invoke(wipeInstance()) as Enum<*>).name

    private fun setOutcome(name: String) {
        val clazz = runCatching { Class.forName(RESET_WIPE) }.getOrNull() ?: return
        val type = clazz.getMethod("getStartOutcome").returnType
        val value = type.enumConstants!!.first { (it as Enum<*>).name == name }
        clazz.getMethod("setStartOutcome", type).invoke(clazz.getField("INSTANCE").get(null), value)
    }

    private companion object {
        const val RESET_WIPE = "co.electriccoin.zcash.ui.screen.chat.util.ResetWipe"
        const val PLAN_CALL = "makePlan"
        const val PHRASE = "data/shared_prefs/co.electriccoin.zcash.encrypted.xml"
        const val RECORD = "data/no_backup/zchat_reset_pending"
        val OWN = "a".repeat(64)
        val STORE = FakePhone.storeHash("12a9aabbcc", "1288ddeeff")
        val PHRASE_XML = FakePhone.phraseStoreXml("12a9aabbcc", "1288ddeeff")
    }
}
