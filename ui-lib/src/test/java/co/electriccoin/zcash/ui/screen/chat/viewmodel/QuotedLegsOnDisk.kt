package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-1334, ZC-1336 test support: the store the fixtures leave out, the legs one price question covered
 * (QuotedChatSetup and the ZBOOT allowance), kept as the real store keeps them, over the screen's own store.
 */
internal class QuotedLegsOnDisk {
    val setups = ConcurrentHashMap<String, String>()
    val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun over(vm: ChatViewModel) {
        val field = ChatViewModel::class.java.getDeclaredField("zchatPreferences").apply { isAccessible = true }
        val under = field.get(vm) as ZchatPreferences
        val wrapped =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getQuotedChatSetup" -> setups[args!![0] as String]
                    "getQuotedChatSetupPeers" -> HashSet(setups.keys)
                    "setQuotedChatSetup" -> {
                        val value = args!![1] as String?
                        if (value == null) setups.remove(args[0] as String) else setups[args[0] as String] = value
                        true
                    }
                    "isZbootQuoted" -> args!![0] in zbootQuoted
                    "setZbootQuoted" -> {
                        if (args!![1] as Boolean) zbootQuoted += args[0] as String else zbootQuoted -= args[0] as String
                        true
                    }
                    else ->
                        try {
                            method.invoke(under, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }
            } as ZchatPreferences
        PaymentHarness.setField(vm, "zchatPreferences", wrapped)
    }
}
