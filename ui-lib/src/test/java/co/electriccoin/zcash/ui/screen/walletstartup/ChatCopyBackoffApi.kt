package co.electriccoin.zcash.ui.screen.walletstartup

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.w3c.dom.Element
import java.io.File
import java.lang.reflect.InvocationTargetException
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * F10 (fix-all 2.13.0, chat copy #16): the new parts of the chat-copy back-off, reached by name so the tests compile
 * before they exist — the parked start-up state and offer, the per-copy hold ([held], [retryHeld],
 * [acknowledgeRestoreParked]), the start-up screen's "park read" action and the Advanced Settings "Restore old chats"
 * helper ([RESTORE_OLD_CHATS_CLASS]). A missing part fails the test with its name.
 */
internal object ChatCopyBackoffApi {
    const val PARKED_STATE = "ChatCopyRestoreParked"
    const val PARKED_OFFER = "ChatCopyRestoreParked"
    const val PARK_READ_ACTION = "CHAT_COPY_PARK_READ"
    const val RESTORE_OLD_CHATS_CLASS = "co.electriccoin.zcash.ui.screen.advancedsettings.RestoreOldChats"

    /** One day on the phone clock: the wait after the second pause. */
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val HOUR_MS = 60L * 60 * 1000

    fun isParked(state: StartupStorageCheck.State): Boolean = state.javaClass.simpleName == PARKED_STATE

    /** The stores a parked start-up state names. */
    @Suppress("UNCHECKED_CAST")
    fun parkedStores(state: StartupStorageCheck.State): List<String> {
        if (!isParked(state)) missing("the start-up state is not $PARKED_STATE: $state")
        return state.javaClass.getMethod("getStores").invoke(state) as List<String>
    }

    fun isParkedOffer(offer: WalletStartupRepair.Offer): Boolean = offer.javaClass.simpleName == PARKED_OFFER

    /** KeptChatStoreCopies.held(): this wallet's kept copies that are held back (paused or parked). */
    @Suppress("UNCHECKED_CAST")
    fun held(copies: KeptChatStoreCopies): List<String> = call(copies, "held") as List<String>

    /** KeptChatStoreCopies.retryHeld(): lets the held copies be offered again (the Settings "Restore old chats"). */
    fun retryHeld(copies: KeptChatStoreCopies): Boolean = call(copies, "retryHeld") as Boolean

    /** KeptChatStoreCopies.acknowledgeRestoreParked(): the user has read the parked screen. */
    fun acknowledgeRestoreParked(copies: KeptChatStoreCopies): Boolean = call(copies, "acknowledgeRestoreParked") as Boolean

    /** WalletStartupRepair.acknowledgeChatCopyPark(): the parked screen's Continue. */
    fun acknowledgeChatCopyPark(repair: WalletStartupRepair): Boolean = callSuspend(repair, "acknowledgeChatCopyPark") as Boolean

    /** A RestoreOldChats over [copies] and [restarter], its work on [io]. */
    fun restoreOldChats(
        copies: KeptChatStoreCopies,
        restarter: AppRestarter,
        io: CoroutineDispatcher = Dispatchers.Unconfined,
    ): Any {
        val type =
            try {
                Class.forName(RESTORE_OLD_CHATS_CLASS)
            } catch (e: ClassNotFoundException) {
                missing("there is no $RESTORE_OLD_CHATS_CLASS (the Advanced Settings \"Restore old chats\" row): ${e.message}")
            }
        val constructor =
            type.constructors.firstOrNull { it.parameterTypes.size == 3 }
                ?: missing("$RESTORE_OLD_CHATS_CLASS has no (KeptChatStoreCopies, AppRestarter, CoroutineDispatcher) constructor")
        return constructor.newInstance(copies, restarter, io)
    }

    /** RestoreOldChats.isAvailable(): whether Advanced Settings shows the row. */
    fun isAvailable(restoreOldChats: Any): Boolean = callSuspend(restoreOldChats, "isAvailable") as Boolean

    /** RestoreOldChats.retryAndRestart(): the confirmed row's action. */
    fun retryAndRestart(restoreOldChats: Any): Boolean = callSuspend(restoreOldChats, "retryAndRestart") as Boolean

    fun stringId(name: String): Int =
        try {
            R.string::class.java.getField(name).getInt(null)
        } catch (e: NoSuchFieldException) {
            missing("there is no string $name: ${e.message}")
        }

    /** The strings of ui-lib's res/ui/<folder>/<qualifier>/ files, by name. */
    fun strings(
        folder: String,
        qualifier: String
    ): Map<String, String> {
        val dir = uiLibDir.resolve("src/main/res/ui/$folder/$qualifier")
        if (!dir.isDirectory) missing("missing ${dir.path}")
        return dir
            .listFiles { f -> f.name.endsWith(".xml") }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { file ->
                val nodes =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(file)
                        .getElementsByTagName("string")
                (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("name") to it.textContent }
            }.toMap()
    }

    fun source(relative: String): String {
        val file = uiLibDir.resolve("src/main/java/co/electriccoin/zcash/$relative")
        if (!file.isFile) missing("missing ${file.path}")
        return file.readText()
    }

    private val uiLibDir: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        generateSequence(start) { it.parentFile }
            .take(8)
            .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
            .firstOrNull { it.resolve("src/main/res/ui/error").isDirectory }
            ?: missing("could not locate the ui-lib module from ${start.path}")
    }

    private fun missing(message: String): Nothing = throw AssertionError(message)

    private fun call(
        target: Any,
        name: String
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?: missing("${target.javaClass.simpleName} has no $name()")
        return try {
            method.invoke(target)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** Calls the suspend function [name] of [target] (no arguments besides its continuation) and waits for it. */
    private fun callSuspend(
        target: Any,
        name: String
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 1 && it.parameterTypes[0] == Continuation::class.java
            } ?: missing("${target.javaClass.simpleName} has no suspend $name()")
        return runBlocking {
            suspendCoroutine<Any?> { continuation ->
                val result =
                    try {
                        method.invoke(target, continuation)
                    } catch (e: InvocationTargetException) {
                        continuation.resumeWithException(e.targetException)
                        return@suspendCoroutine
                    }
                if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
            }
        }
    }
}
