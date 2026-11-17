package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CompletableDeferred
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ZC-284 — helpers for the tests of what a group Join keeps on disk while its reply is on its way.
 *
 * All addresses, keys and group ids used with it are synthetic.
 */
internal object JoinHeldCopy {
    /** What [intercepting]'s answer returns to let the call through to the store. */
    val PASS = Any()

    /** A synthetic relay key (64 hex characters). */
    val RELAY_KEY: String = "ab".repeat(32)

    /**
     * [delegate], with [answer] asked first for every call (method name, arguments): [PASS] lets the call through,
     * anything else is the call's result. An exception [answer] throws is what the caller sees.
     */
    fun intercepting(
        delegate: ZchatPreferences,
        answer: (name: String, args: Array<out Any?>) -> Any?,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "JoinHeldCopy($delegate)"
                else -> {
                    val own = answer(method.name, args ?: emptyArray())
                    if (own !== PASS) {
                        own
                    } else {
                        try {
                            method.invoke(delegate, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    }
                }
            }
        } as ZchatPreferences

    /** The group screen's Join, not awaited: its reply may be suspended on the relays or on the account read. */
    fun joinNoWait(
        vm: ChatViewModel,
        groupId: String,
    ): Boolean =
        try {
            ChatViewModel::class.java.getMethod("joinGroupInvite", String::class.java).invoke(vm, groupId) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
}

/**
 * ZC-284 — a selected-account source whose read can be made to wait ([arm]): the next `getSelectedAccount` completes
 * [entered] and then never answers (until its caller is cancelled).
 */
internal class GatedAccounts(
    private val inner: AccountDataSource,
) : AccountDataSource by inner {
    private val armed = AtomicBoolean(false)
    val entered = CompletableDeferred<Unit>()
    private val never = CompletableDeferred<Unit>()

    fun arm() = armed.set(true)

    override suspend fun getSelectedAccount(): WalletAccount {
        if (armed.get()) {
            entered.complete(Unit)
            never.await()
        }
        return inner.getSelectedAccount()
    }
}
