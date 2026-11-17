package co.electriccoin.zcash.ui.screen.deletewallet

import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryChatOwnership
import co.electriccoin.zcash.ui.screen.chat.datasource.chatOwnerTag
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * P2e (RD-1): Delete Wallet must write down which wallet owns the chats while its phrase is still on
 * the phone, so the next, different phrase gets empty chats and this wallet's chats are set aside for
 * it. After a delete WITHOUT keep the chats belong to no wallet.
 *
 * The full invoke() is not drivable on the JVM (biometrics, SDK erase), so — as in
 * ResetZashiWipeDispatcherTest — the two helpers it calls are driven on a bare instance.
 */
class ResetZashiRecordsChatOwnerTest {
    private val seed = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE) { "deleted$it" })

    @Test
    fun delete_wallet_records_the_deleted_wallet_as_owner_of_the_chats() =
        runBlocking {
            val chats = InMemoryChatOwnership(owner = null) // an install from before P2e
            val useCase = bareUseCase(chats)

            invokeSuspend(useCase, "recordChatOwner")

            assertEquals(
                "Delete Wallet did not record the deleted wallet as the owner of the chats it leaves behind",
                chatOwnerTag(seed.split),
                chats.record.owner(),
            )
        }

    @Test
    fun a_delete_without_keep_leaves_chats_that_belong_to_no_wallet() =
        runBlocking {
            val chats = InMemoryChatOwnership(owner = chatOwnerTag(seed.split))
            val useCase = bareUseCase(chats)

            invokeSuspend(useCase, "markChatsWiped")

            assertEquals("none", chats.record.owner())
        }

    private fun bareUseCase(chats: InMemoryChatOwnership): Any {
        val useCase = allocateBare(ResetZashiUseCase::class.java)
        setField(useCase, "persistableWalletProvider", walletProvider())
        setField(useCase, "chatWalletOwnership", chats.ownership)
        return useCase
    }

    private fun walletProvider(): PersistableWalletProvider {
        val wallet =
            PersistableWallet(
                network = ZcashNetwork.Mainnet,
                endpoint = LightWalletEndpoint(host = "zec.rocks", port = 443, isSecure = true),
                birthday = BlockHeight.new(3_000_000L),
                seedPhrase = seed,
                walletInitMode = WalletInitMode.NewWallet,
            )
        return Proxy.newProxyInstance(
            PersistableWalletProvider::class.java.classLoader,
            arrayOf(PersistableWalletProvider::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                // The Flow property's getter and the suspend getter share a JVM name; only the latter takes a Continuation.
                "getPersistableWallet" -> if (args.isNullOrEmpty()) flowOf(wallet) else wallet
                "requirePersistableWallet" -> wallet
                "toString" -> "FakePersistableWalletProvider"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException("unexpected call ${method.name}")
            }
        } as PersistableWalletProvider
    }

    private fun allocateBare(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(theUnsafe, clazz)!!
    }

    private fun setField(
        target: Any,
        name: String,
        value: Any?
    ) = target.javaClass
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .set(target, value)

    /** Calls a private `suspend fun name()` across the JVM Continuation ABI. */
    private suspend fun invokeSuspend(
        target: Any,
        name: String
    ): Unit =
        suspendCoroutine { outer ->
            val method =
                target.javaClass
                    .getDeclaredMethod(name, Continuation::class.java)
                    .apply { isAccessible = true }
            val bridge =
                object : Continuation<Any?> {
                    override val context = outer.context

                    override fun resumeWith(result: Result<Any?>) {
                        result.exceptionOrNull()?.let { outer.resumeWithException(it) } ?: outer.resume(Unit)
                    }
                }
            val result =
                try {
                    method.invoke(target, bridge)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    outer.resumeWithException(e.targetException)
                    return@suspendCoroutine
                }
            if (result != COROUTINE_SUSPENDED) outer.resume(Unit)
        }
}
