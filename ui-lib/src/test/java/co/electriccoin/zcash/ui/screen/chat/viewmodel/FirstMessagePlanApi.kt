package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * M2 (fix-all 2.13.0, train T5b; money SPEC §2.2-2.4 with t5b SPECS §2 C-M2-1..C-M2-9): the first-message plan check,
 * reached by name so the RED tests compile before it exists. Every call unwraps InvocationTargetException.
 *
 * - `common.model.PlanProbe`: `Built(fee: Zatoshi, transactionCount: Int, usesOrchardInputs: Boolean,
 *   inputs: List<ProposalInputOutpoint>?, change: List<Long>?)` or `Short(need: Zatoshi?)`.
 * - `common.model.ProposalInputOutpoint(txIdHex, pool, value, index: Int?)`: the output index of the note it spends.
 * - `screen.chat.model.FirstMessagePlan`: `Fundable`, `NotFundable`, `WaitForFunds` (objects) or `Unchecked(cause)`.
 * - `screen.chat.model.FirstMessagePlanFunding.decide(kex, message, messageAlone: Zatoshi): FirstMessagePlan` (pure).
 * - `screen.chat.model.FirstMessagePlanCheck.check(destination, messageMemos: Int, amountPerOutput: Zatoshi)` (suspend).
 * - `screen.chat.usecase.ProbeFirstMessagePlanUseCase(CreateChunkedMessageProposalUseCase)`: the real check.
 * - `ChatViewModel.firstMessagePlanCheck`: the constructor parameter the send gate asks.
 */
internal object FirstMessagePlanApi {
    private const val MODEL = "co.electriccoin.zcash.ui.screen.chat.model"
    const val PLAN = "$MODEL.FirstMessagePlan"
    const val CHECK = "$MODEL.FirstMessagePlanCheck"
    const val FUNDING = "$MODEL.FirstMessagePlanFunding"
    const val PROBE = "co.electriccoin.zcash.ui.common.model.PlanProbe"
    const val OUTPOINT = "co.electriccoin.zcash.ui.common.model.ProposalInputOutpoint"
    const val USE_CASE = "co.electriccoin.zcash.ui.screen.chat.usecase.ProbeFirstMessagePlanUseCase"
    const val FIELD = "firstMessagePlanCheck"

    fun classOrNull(name: String): Class<*>? =
        try {
            Class.forName(name)
        } catch (_: ClassNotFoundException) {
            null
        }

    fun cls(name: String): Class<*> = classOrNull(name) ?: throw AssertionError("$name does not exist (M2)")

    /** A plan answer by name: Fundable, NotFundable, WaitForFunds, or Unchecked (with [cause]). */
    fun answer(
        name: String,
        cause: Throwable? = null,
    ): Any =
        if (name == "Unchecked") {
            cls("$PLAN\$Unchecked").getConstructor(Throwable::class.java).newInstance(cause)
        } else {
            cls("$PLAN\$$name").getField("INSTANCE").get(null)
        }

    /** The answer's kind: Fundable, NotFundable, WaitForFunds or Unchecked. */
    fun nameOf(plan: Any?): String = plan?.javaClass?.simpleName ?: "null"

    /** One received note a proposal spends, with its output [index] (null: the index could not be read). */
    fun outpoint(
        txIdHex: String?,
        index: Int?,
        value: Long,
        pool: Int = IRONWOOD,
    ): Any {
        val constructor =
            cls(OUTPOINT).constructors.firstOrNull { it.parameterCount == 4 && !it.isSynthetic }
                ?: throw AssertionError("ProposalInputOutpoint has no output index (M2, C-M2-5)")
        return construct { constructor.newInstance(txIdHex, pool, value, index) }
    }

    fun built(
        inputs: List<Any>?,
        change: List<Long>?,
        fee: Long = 15_000,
        transactionCount: Int = 1,
        usesOrchardInputs: Boolean = false,
    ): Any {
        val constructor =
            cls("$PROBE\$Built").constructors.firstOrNull { it.parameterCount == 5 && !it.isSynthetic }
                ?: throw AssertionError("PlanProbe.Built(fee, transactionCount, usesOrchardInputs, inputs, change) does not exist")
        return construct { constructor.newInstance(Zatoshi(fee), transactionCount, usesOrchardInputs, inputs, change) }
    }

    fun short(need: Long? = null): Any =
        construct { cls("$PROBE\$Short").getConstructor(Zatoshi::class.java).newInstance(need?.let(::Zatoshi)) }

    /** FirstMessagePlanFunding.decide(kex, message, messageAlone). */
    fun decide(
        kex: Any,
        message: Any,
        messageAlone: Long,
    ): Any {
        val funding = cls(FUNDING)
        val target = funding.getField("INSTANCE").get(null)
        val method =
            funding.methods.firstOrNull { it.name == "decide" && it.parameterCount == 3 }
                ?: throw AssertionError("FirstMessagePlanFunding.decide(kex, message, messageAlone) does not exist")
        return construct { method.invoke(target, kex, message, Zatoshi(messageAlone)) as Any }
    }

    /** Calls the suspend function [name] of [target] with [args] (the continuation is added here). */
    suspend fun callSuspend(
        target: Any,
        name: String,
        vararg args: Any?,
    ): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method =
                target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size + 1 }
                    ?: throw AssertionError("${target.javaClass.simpleName}.$name/${args.size} does not exist (M2)")
            try {
                method.invoke(target, *args, continuation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

    private fun <T> construct(block: () -> T): T =
        try {
            block()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    /**
     * A FirstMessagePlanCheck that answers [answers] in turn (the last one repeats) and records every call as
     * (destination, memos, amount per output). With [throwing], every call throws it instead.
     */
    class ScriptedCheck(
        private vararg val answers: String,
        private val throwing: Throwable? = null,
    ) {
        val calls: MutableList<Triple<String, Int, Long>> = CopyOnWriteArrayList()
        private val next = AtomicInteger(0)

        /** The check, or null while the build has no FirstMessagePlanCheck. */
        fun proxyOrNull(): Any? {
            val iface = classOrNull(CHECK) ?: return null
            return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, method, args ->
                when (method.name) {
                    "check" -> {
                        calls += Triple(args!![0] as String, args[1] as Int, (args[2] as Zatoshi).value)
                        throwing?.let { throw it }
                        val i = next.getAndIncrement().coerceAtMost(answers.size - 1)
                        answer(answers[i])
                    }
                    "toString" -> "ScriptedCheck(${answers.joinToString()})"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException("unexpected FirstMessagePlanCheck call: ${method.name}")
                }
            }
        }
    }

    /** Whether this build's ChatViewModel has the plan-check field. */
    fun hasField(): Boolean = ChatViewModel::class.java.declaredFields.any { it.name == FIELD }

    /** Sets the ViewModel's plan check to [check] (null: leaves it unset), when the build has the field. */
    fun install(
        vm: ChatViewModel,
        check: Any?,
    ) {
        val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == FIELD } ?: return
        field.isAccessible = true
        field.set(vm, check)
    }

    /**
     * The harness default (C-M2-2): a plan check that answers Fundable, so the tests written before M2 keep their
     * behaviour. A ViewModel allocated without its constructor would otherwise hold null there, which the gate reads
     * as Unchecked and refuses (fail closed).
     */
    fun installFundable(vm: ChatViewModel) {
        if (!hasField()) return
        install(vm, ScriptedCheck("Fundable").proxyOrNull())
    }

    const val IRONWOOD = 4
}
