package co.electriccoin.zcash.ui.keystone

import co.electriccoin.zcash.ui.common.usecase.CreateKeystoneAccountUseCase
import com.keystone.module.ZcashAccount
import com.keystone.module.ZcashAccounts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K0-1 (fix-all 2.13.0, train T1): the one place that imports a Keystone account refuses while new connections are
 * closed (KeystoneAvailability.NEW_CONNECTIONS = false), whatever screen reaches it. Nothing is imported, the
 * selected account does not change, and no screen is opened.
 *
 * At HEAD CreateKeystoneAccountUseCase imports the account, selects it and goes back to the root screen.
 *
 * KeystoneAvailability and KeystoneConnectionsClosedException are read by name, so this file compiles before they
 * exist. The debug seam IMPORT_KEYSTONE_VIEWONLY goes through AccountDataSource directly and is not affected.
 */
class KeystoneAccountImportRefusedTest {
    @Test
    fun `new connections and chat signing are closed in this version`() {
        // A const val on an object is a static field.
        assertEquals("KeystoneAvailability.NEW_CONNECTIONS", false, availability().getField("NEW_CONNECTIONS").get(null))
        assertEquals("KeystoneAvailability.CHAT_SIGNING", false, availability().getField("CHAT_SIGNING").get(null))
    }

    @Test
    fun `importing a scanned Keystone account is refused before anything is imported or selected`() =
        runBlocking {
            val accounts = TwoAccountDataSource.zashiOnly()
            val router = RouteRecordingNavigationRouter()
            val useCase = CreateKeystoneAccountUseCase(accountDataSource = accounts, navigationRouter = router)
            val scanned = ZcashAccount("uviewsynthetic-k0-not-a-real-key", 0, "Synthetic Keystone")

            val thrown =
                runCatching { useCase(ZcashAccounts("synthetic-k0-fingerprint", listOf(scanned)), scanned) }
                    .exceptionOrNull()

            assertEquals("a Keystone account was imported although connections are closed", emptyList<String>(), accounts.imports)
            assertEquals("the selected account changed", emptyList<String>(), accounts.selections)
            assertEquals("a screen was opened", emptyList<String>(), router.commands)
            assertTrue(
                "the refusal must be KeystoneConnectionsClosedException, got $thrown",
                thrown?.javaClass?.name == CLOSED_EXCEPTION,
            )
        }

    private fun availability(): Class<*> = Class.forName(AVAILABILITY)

    private companion object {
        const val AVAILABILITY = "co.electriccoin.zcash.ui.common.model.KeystoneAvailability"
        const val CLOSED_EXCEPTION = "co.electriccoin.zcash.ui.common.model.KeystoneConnectionsClosedException"
    }
}
