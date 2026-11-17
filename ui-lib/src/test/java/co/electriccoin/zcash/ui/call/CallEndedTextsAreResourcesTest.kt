package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.R
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TURN-CREDS (fix-all 2.13.0, train T5; turn-creds SPEC §3.1 T3, §3.3, finding F5): the call's Ended toast is not
 * English on a Spanish phone, and it can say that ZChat's call relay was not available.
 *
 * Today CallEndReason.displayLabel holds eight English literals. Wanted: the eight labels are string resources in
 * ui/common/values{,-es}/call_screen_strings.xml with today's English as the English value, plus two new ones
 * (call_ended_connect_failed, call_ended_no_relay), each reason names its resource through `labelRes`, and the English
 * literals are gone from CallEndReason.kt (the SpanishLeftoversAndPlaceholdersTest pattern).
 *
 * Text rules (app-texts): no placeholders; the no-relay text says the relay was not available and that only a direct
 * connection was tried; Spanish says «servidor relay», never «relé»; neither promises an automatic retry or says
 * anything was lost. Keys and R fields are reached by name, so this file compiles before they exist.
 *
 * QA2 Q2-83 (ZC-1600, W-137): the busy notice says "Contact is busy" / «Tu contacto está ocupado», not "Peer is busy".
 */
class CallEndedTextsAreResourcesTest {
    private val uiLibDir: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        repeat(8) {
            val current = dir ?: return@repeat
            listOf(current, current.resolve("ui-lib")).forEach { module ->
                if (module.resolve("src/main/java/co/electriccoin/zcash/ui").isDirectory) return@lazy module
            }
            dir = current.parentFile
        }
        fail("could not locate the ui-lib module from ${start.path}")
    }

    private fun strings(values: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/common/$values/call_screen_strings.xml")
        if (!file.isFile) fail("expected ${file.path}")
        val text = file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    }

    private fun unescape(value: String): String {
        val quotes = value.replace("\\'", "'").replace("\\\"", "\"")
        return quotes.replace("&amp;", "&")
    }

    @Test
    fun `the eight English labels left CallEndReason kt`() {
        val source = uiLibDir.resolve("src/main/java/co/electriccoin/zcash/ui/call/CallEndReason.kt").readText()
        val left = TODAY_EN.values.filter { "\"$it\"" in source }
        assertEquals(emptyList(), left, "English Ended-toast literals still in CallEndReason.kt")
    }

    @Test
    fun `English holds today's eight labels and the two new texts`() {
        val en = strings("values")
        (TODAY_EN + NEW_EN).forEach { (key, value) ->
            assertEquals(value, en[key], "values/call_screen_strings.xml $key")
        }
    }

    @Test
    fun `Spanish holds all ten`() {
        val es = strings("values-es")
        ES.forEach { (key, value) -> assertEquals(value, es[key], "values-es/call_screen_strings.xml $key") }
    }

    @Test
    fun `no placeholders, no relé, nothing lost, no automatic retry`() {
        val en = strings("values")
        val es = strings("values-es")
        (TODAY_EN.keys + NEW_EN.keys).forEach { key ->
            listOf("en" to en[key], "es" to es[key]).forEach { (lang, text) ->
                if (text == null) fail("$lang $key missing")
                assertTrue('%' !in text, "$lang $key has a placeholder: $text")
                assertTrue(!Regex("""(?i)\blost\b|perdid|automatic|autom[aá]tic""").containsMatchIn(text), "$lang $key: $text")
            }
            assertTrue(!Regex("""(?i)rel[eé]\b""").containsMatchIn(es[key].orEmpty()), "es $key says relé: ${es[key]}")
        }
        val noRelayEn = en.getValue("call_ended_no_relay")
        assertTrue("relay wasn't available" in noRelayEn && "only a direct connection was tried" in noRelayEn, noRelayEn)
        val noRelayEs = es.getValue("call_ended_no_relay")
        assertTrue("servidor relay" in noRelayEs && "solo se intentó una conexión directa" in noRelayEs, noRelayEs)
    }

    @Test
    fun `each reason's labelRes names its resource`() {
        val expected =
            listOf<Pair<Any, String>>(
                CallEndReason.UserEnded to "call_ended",
                CallEndReason.Shutdown to "call_ended",
                CallEndReason.BackPressed to "call_ended",
                CallEndReason.RemoteHangup("user_ended") to "call_ended",
                CallEndReason.Declined to "call_ended_declined",
                CallEndReason.Busy to "call_ended_busy",
                CallEndReason.PermissionDenied to "call_ended_mic_permission",
                CallEndReason.IceFailed to "call_ended_connection_failed",
                CallEndReason.Timeout to "call_ended_no_answer",
                CallEndReason.Glare to "call_ended_glare",
                CallEndReason.SetupFailed("x") to "call_ended_setup_failed",
                reasonObject("ConnectFailed") to "call_ended_connect_failed",
                reasonObject("NoRelay") to "call_ended_no_relay",
            )
        expected.forEach { (reason, key) ->
            val id =
                runCatching { R.string::class.java.getField(key).getInt(null) }.getOrElse {
                    fail("no string resource $key")
                }
            assertEquals(id, labelRes(reason), "$reason must show $key")
        }
    }

    private fun reasonObject(simpleName: String): Any =
        runCatching {
            Class.forName("co.electriccoin.zcash.ui.call.CallEndReason\$$simpleName").getField("INSTANCE").get(null)
        }.getOrNull() ?: fail("CallEndReason.$simpleName does not exist")

    private fun labelRes(reason: Any): Int =
        runCatching { reason.javaClass.getMethod("getLabelRes").invoke(reason) as Int }.getOrElse {
            fail("CallEndReason has no labelRes (the Ended toast still shows English literals)")
        }

    private companion object {
        val TODAY_EN =
            linkedMapOf(
                "call_ended" to "Call ended",
                "call_ended_declined" to "Call declined",
                "call_ended_busy" to "Contact is busy",
                "call_ended_mic_permission" to "Microphone permission needed",
                "call_ended_connection_failed" to "Connection failed",
                "call_ended_no_answer" to "No answer",
                "call_ended_glare" to "Call collision — please retry",
                "call_ended_setup_failed" to "Call setup failed",
            )

        val NEW_EN =
            linkedMapOf(
                "call_ended_connect_failed" to "Couldn't connect the call",
                "call_ended_no_relay" to
                    "Couldn't connect. ZChat's call relay wasn't available, so only a direct connection was tried. " +
                    "Try again later or on another network.",
            )

        val ES =
            linkedMapOf(
                "call_ended" to "Llamada finalizada",
                "call_ended_declined" to "Llamada rechazada",
                "call_ended_busy" to "Tu contacto está ocupado",
                "call_ended_mic_permission" to "Se necesita permiso para usar el micrófono",
                "call_ended_connection_failed" to "Falló la conexión",
                "call_ended_no_answer" to "Sin respuesta",
                "call_ended_glare" to "Las llamadas se cruzaron. Vuelve a intentarlo.",
                "call_ended_setup_failed" to "No se pudo iniciar la llamada",
                "call_ended_connect_failed" to "No se pudo conectar la llamada",
                "call_ended_no_relay" to
                    "No se pudo conectar. El servidor relay de llamadas de ZChat no estaba disponible, así que solo " +
                    "se intentó una conexión directa. Vuelve a intentarlo más tarde o desde otra red.",
            )
    }
}
