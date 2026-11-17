package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.CompletableDeferred
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * T5b fixup (X-02, X-05): a FirstMessagePlanCheck whose every call waits until the test answers it, the way the real
 * check waits for the SDK. Call number n (from 0) suspends until [answer] is given for it, and is cancelled with the
 * coroutine that asked (leaving the chat cancels the screen's scope). Built on [FirstMessagePlanApi].
 */
internal class GatedFirstMessagePlanCheck {
    private val gates = CopyOnWriteArrayList<CompletableDeferred<String>>()

    /** How many checks were asked so far. */
    val calls: Int
        get() = gates.size

    /** Answers call [index] with the plan named [plan] (Fundable, NotFundable, WaitForFunds). */
    fun answer(
        index: Int,
        plan: String,
    ) {
        gates[index].complete(plan)
    }

    /** Installs this check as [vm]'s plan check. */
    fun install(vm: ChatViewModel) {
        val iface = FirstMessagePlanApi.cls(FirstMessagePlanApi.CHECK)
        val proxy =
            Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, method, args ->
                when (method.name) {
                    "check" -> {
                        val gate = CompletableDeferred<String>().also { gates += it }
                        val wait: suspend () -> Any = { FirstMessagePlanApi.answer(gate.await()) }
                        @Suppress("UNCHECKED_CAST")
                        wait.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any>)
                    }
                    "toString" -> "GatedFirstMessagePlanCheck"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException("unexpected FirstMessagePlanCheck call: ${method.name}")
                }
            }
        FirstMessagePlanApi.install(vm, proxy)
    }
}
