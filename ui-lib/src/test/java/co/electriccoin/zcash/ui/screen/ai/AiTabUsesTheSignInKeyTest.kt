package co.electriccoin.zcash.ui.screen.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AI-KEY-S (W-52, W-53; AI-SIGNIN-KEY-SPEC 4.1, 4.3, 4.5, 4.6, 6.3): the source half. What the behaviour tests of
 * AiAccountAccessTest cannot reach: that the app's real callers ask AiAccountAccess, and how the graph builds it.
 *
 * WHY THIS FAILS TODAY: AiTabVM.bootstrap and ChatViewModel.handleAiCommand call client.register(walletPubkey), read
 * the saved key themselves, and AiAccountAccess is not in the graph or the foreground observer. Two cases are guards
 * and pass today: Reset and Destroy name no AI network class, and the app's start stays in the main process.
 */
class AiTabUsesTheSignInKeyTest {
    private val ui = "src/main/java/co/electriccoin/zcash/ui"
    private val repo: File get() = checkNotNull(AiSignInApi.uiLib.parentFile)

    private fun ui(path: String): String = AiSignInApi.read(AiSignInApi.uiLib.resolve("$ui/$path"))

    private val tabVm get() = AiSignInApi.read(AiSignInApi.mainAiFile("AiTabVM.kt"))
    private val client get() = AiSignInApi.read(AiSignInApi.mainAiFile("AiApiClient.kt"))
    private val application get() = repo.resolve("app/src/main/java/co/electriccoin/zcash/app/ZcashApplication.kt").readText()

    /** One member function of a class at four spaces: from its signature to the next member. */
    private fun member(
        source: String,
        signature: String,
    ): String {
        assertTrue("$signature is gone", signature in source)
        return source.substringAfter(signature).substringBefore("\n    fun ").substringBefore("\n    private fun ")
    }

    private fun aiCommand(): String {
        val chat = ui("screen/chat/viewmodel/ChatViewModel.kt")
        val start = chat.indexOf("val prefs = co.electriccoin.zcash.ui.screen.ai.AiPreferences(")
        val body = chat.substring(chat.lastIndexOf("handleAiCommand", start).coerceAtLeast(0))
        return body.substringBefore("\"AI command failed")
    }

    @Test
    fun `the tab's bootstrap gets its key from AiAccountAccess, with no sign-up of its own`() {
        val body = member(tabVm, "private fun bootstrap()")
        assertFalse("bootstrap still signs up", "client.register(" in body)
        assertFalse("bootstrap still reads the saved key itself", "it.getToken()" in body)
        assertTrue("the tab does not name AiAccountAccess", "AiAccountAccess" in tabVm)
        assertFalse("the tab still resolves a wallet fingerprint", "walletPubkeyResolver" in tabVm)
    }

    @Test
    fun `a chat and a picture from the tab take the charged key`() {
        listOf("private fun dispatchChat(", "fun generateImage(").forEach { signature ->
            assertTrue("$signature does not ask for the charged key", "keyForCharge(" in member(tabVm, signature))
        }
    }

    @Test
    fun `a refused key goes to AiAccountAccess from the tab`() {
        assertTrue("the tab does not hand a 401 on", "onRefused(" in tabVm)
    }

    @Test
    fun `the balance answer goes to AiAccountAccess from the tab`() {
        assertTrue("the tab does not hand signInKeyId on", "onBalance(" in member(tabVm, "private suspend fun refreshBalance("))
    }

    @Test
    fun `the chat shortcut takes the charged key from AiAccountAccess`() {
        val body = aiCommand()
        assertFalse("the shortcut still signs up", "client.register(" in body)
        assertFalse("the shortcut still reads the saved key itself", "prefs.getToken()" in body)
        assertFalse("the shortcut still sends the fingerprint", "WalletPubkey." in body)
        assertTrue("the shortcut does not ask for the charged key", "keyForCharge(" in body)
    }

    @Test
    fun `the old sign-up has no caller and is gone from the client`() {
        val callers = AiSignInApi.productSources().filter { "client.register(" in it.readText() }.map { it.name }
        assertEquals(emptyList<String>(), callers)
        assertFalse("AiApiClient.register still exists", "fun register(" in client)
        assertFalse("the old path is still in the client", "/ai/auth/register" in client)
    }

    /** spec 4.1, F12: the new function logs a fixed text and the exception's class only. */
    @Test
    fun `the client's request function logs a fixed text and the exception's class only`() {
        val body = member(client, "suspend fun request(")
        val logs = Regex("""Log\.\w+\(([^\n]*)\)""").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue("the request function has no log line on failure", logs.isNotEmpty())
        logs.forEach { line ->
            assertFalse("a log line takes the exception: $line", Regex(""",\s*\w+\s*$""").containsMatchIn(line))
            assertFalse("a log line takes the exception's text: $line", ".message" in line || "\$e" in line)
            assertTrue("a log line names no class: $line", "javaClass" in line || "::class" in line)
        }
    }

    @Test
    fun `AiAccountAccess keeps no private half, wipes the seed in a finally, keeps the key id in memory`() {
        val source = AiSignInApi.read(AiSignInApi.mainAiFile("AiAccountAccess.kt"))
        assertFalse("a field of a private half", Regex("""\b(val|var)\s+\w*[Pp]rivate\w*\s*[:=]""").containsMatchIn(source.memberLevel()))
        assertFalse("a field of a key pair", Regex("""\b(val|var)\s+\w+\s*[:=]\s*AiSignInKey""").containsMatchIn(source.memberLevel()))
        val finally = source.substringAfter("finally", "")
        assertTrue("the seed is not wiped in a finally", ".fill(0)" in finally)
    }

    private fun String.memberLevel(): String = lines().filter { it.startsWith("    ") && !it.startsWith("     ") }.joinToString("\n")

    @Test
    fun `the graph builds one AiAccountAccess from the seed read, the fence's readers and read-only candidates`() {
        val graph = AiSignInApi.uiLib.resolve("src/main/java/co/electriccoin/zcash/di/DataSourceModule.kt").readText()
        val wiring = graph.substringAfter("AiAccountAccess(", "")
        assertTrue("AiAccountAccess is not a single of the graph", wiring.isNotEmpty())
        listOf("seedReader(", "chatOwnerOf", "walletScopeGeneration").forEach { word ->
            assertTrue("the graph does not hand on $word", word in wiring)
        }
        val access = AiSignInApi.read(AiSignInApi.mainAiFile("AiAccountAccess.kt"))
        val candidates = graph + access
        assertTrue("the candidates do not read the saved identity", "pinnedIdentity" in candidates)
        assertTrue("the candidates do not read the current address", "getUnifiedAddress" in candidates)
        assertFalse("a comparison may never pin an identity", "pinIdentityIfAbsent" in candidates)
    }

    @Test
    fun `the store's write after a network answer re-reads the fence under the wallet-scope lock`() {
        val store = AiSignInApi.read(AiSignInApi.mainAiFile("AiPreferences.kt"))
        assertTrue("no write takes walletScopeLock", "walletScopeLock" in store)
        assertTrue("the store does not hold ai_link_state", "\"ai_link_state\"" in store)
        assertTrue("the store does not hold ai_parked_keys", "\"ai_parked_keys\"" in store)
    }

    @Test
    fun `the foreground hook stands beside the inbox's, after the main-process check, and nothing else calls it`() {
        val source = application
        val mainOnly = source.indexOf("MainProcess.isMain(")
        val inbox = source.indexOf("observeForegroundForNostrInbox()")
        val hook = source.indexOf("observeForegroundForAiAccount()")
        assertTrue("the main-process check is gone", mainOnly >= 0)
        assertTrue("no foreground hook for the AI account", hook > mainOnly)
        assertTrue("the hook does not stand beside the inbox's", kotlin.math.abs(hook - inbox) < BESIDE_CHARS)
        val body = source.substringAfter("private fun observeForegroundForAiAccount()").substringBefore("\n    private fun ")
        assertTrue("the hook is not in onStart", "override fun onStart" in body && "onForeground(" in body)
        val elsewhere =
            AiSignInApi
                .productSources()
                .filter { it.name != "AiAccountAccess.kt" && "onForeground(" in it.readText() && "AiAccountAccess" in it.readText() }
                .map { it.name }
        assertEquals("the start rule is reachable from elsewhere", emptyList<String>(), elsewhere)
    }

    /** Spec 4.5: "Reset and Destroy make no call". A guard: true today, and it must stay true. */
    @Test
    fun `Reset and Destroy name no AI network class`() {
        listOf("screen/deletewallet/ResetZashiUseCase.kt", "screen/chat/util/DestroyManager.kt").forEach { path ->
            val source = ui(path)
            assertFalse("$path names AiAccountAccess", "AiAccountAccess" in source)
            assertFalse("$path names AiApiClient", "AiApiClient" in source)
        }
    }

    /** A guard: the observer the hook stands beside is reached after the check, as today. */
    @Test
    fun `the inbox's observer still starts only in the main process`() {
        val source = application
        assertTrue(source.indexOf("MainProcess.isMain(") in 0 until source.indexOf("observeForegroundForNostrInbox()"))
    }

    private companion object {
        const val BESIDE_CHARS = 200
    }
}
