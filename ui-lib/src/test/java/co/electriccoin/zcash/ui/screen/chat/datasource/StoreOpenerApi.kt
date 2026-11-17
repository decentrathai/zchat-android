package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * ZC-707 (store-opener design, revision 2a, section 3.2): the new parts of the store opener, reached by name so the
 * tests compile before they exist. It is the one place that states the exact shapes the tests reach:
 *
 *  - `class StoreOpenLaunch()` in this package, with `countOnce(String): Boolean`, `markOpened(String)`,
 *    `wasOpened(String): Boolean` and `forgetAll()` (internal: "a new process, for tests only");
 *  - `class StoreOpenEnv()` in this package, with the settable property `launch: StoreOpenLaunch`;
 *  - `ChatStoreStart.launch`, the one launch of the process;
 *  - `openEncryptedStore(…, env: StoreOpenEnv)` as the eighth parameter, after the seven of today.
 *
 * While the eighth parameter does not exist, [open] calls the opener as it is today, which IS what one process does
 * today, so a test fails on what the opener did and not only on a missing name. Later batches of the design
 * (ZC-705, ZC-709, ZC-733) extend this helper.
 *
 * ZC-705 (spec 3.2, 3.4) adds, all in this package:
 *
 *  - on `StoreOpenEnv` the settable properties `keepPlainAfterLimit: Boolean`, `mayChangeFiles: Boolean`,
 *    `removeStoreFile: ((String) -> Boolean)?` and `storeFileExists: ((String) -> Boolean)?` ([open]'s `env`);
 *  - on `StoreOpenLaunch` `notePlainKeyset(String, String): Boolean`, `plainKeysetCount(String): Int`,
 *    `noteKeptAfterLimit()`, `keptAfterLimit(): Boolean` and `guardLines(): List<String>` (the guard's error-level
 *    lines of the process, also logged);
 *  - `appStoreOpenEnv(context: Context, processName: (Context) -> String?, keepPlainAfterLimit: Boolean)`;
 *  - `object KeysetGuard` with `isPlain(String?): Boolean`, `kindOf(String): String` and `keptAsItIsLine(String)`
 *    (the text it logs for a store that existed); `KeysetNotWrappedException` and `NewStoreNotMadeException`;
 *  - `interface EncryptedFileCalls` with `masterKey(Context, String): Any`, `createWithMasterKey(Context, String,
 *    Any): SharedPreferences` and `createByKeyName(Context, String, String): SharedPreferences`, and
 *    `openEncryptedFile(context, name, masterKeyName, calls)`;
 *  - in KeysetGuard.kt the internal `openPhraseStoreGuardedWith(filename, open, plainPrefs, env, sleep)` behind
 *    `openPhraseStoreGuarded(context, filename)`.
 *
 * ZC-709 (spec 3.5) adds the settable `StoreOpenEnv.copyNames: (() -> Collection<String>)?` ([open]'s `env`, as
 * "copyNames"; left out while it does not exist, so the cases fail on behaviour) and, on `KeptChatStoreCopies`,
 * `askable(): List<String>` and `reoffer(): Boolean` ([askable], [reoffer]).
 *
 * ZC-733-A (spec 3.6 items 1 to 4, 7, 9, 11) adds, in this package unless named otherwise:
 *
 *  - `class LazyEncryptedStore(context, name, masterKeyName, afterOpen)` with the settable `fileCalls:
 *    EncryptedFileCalls` (the library seam, the real calls by default) and `sleep: (Long) -> Unit`, the read-only
 *    `unavailable: StateFlow<Boolean>`, and `askAtStart()`, `get()`, `tryAgain()`, each answering the store;
 *  - `open class EncryptedStoreUnavailableException` and `class OtherStoreUnavailableException(message, cause)`;
 *  - the holder as the private field `store` of `ContactBookImpl` and `AvatarStore`, and `askAtStart()` on both;
 *    `AiPreferences.holder(context)`, `AiPreferences.askAtStartIfPresent(context)` (its companion) and the instance's
 *    `tryOpenAgain(): Boolean` (co.electriccoin.zcash.ui.screen.ai);
 *  - in co.electriccoin.zcash.ui.screen.walletstartup, file StartupStorageCheck.kt, the start function
 *    `openStoresAtStart(openChatStorage, askContactBook, askPictureIndex, askAiStore)` (four `() -> Unit`);
 *  - `AiState.storeUnavailable: Boolean` and `AiTabVM.AI_STORE_UNAVAILABLE_MESSAGE` (its companion).
 *
 * ZC-733-B (spec 3.6 items 5, 6 and 8) adds:
 *
 *  - on `LazyEncryptedStore`: `walletStore(storeId: String): WalletScopedStore`, `forWalletChange(): SharedPreferences`
 *    and `wipe()`; on `StoreOpenEnv` the settable `treatFailureAsFinal: Boolean`;
 *  - on `ContactBook` (co.electriccoin.zcash.ui.screen.chat.model), each with a default: `unreadable:
 *    StateFlow<Boolean>`, `tryAddContact(Contact)`, `tryRemoveContact(String)`, `tryUpdateContactName(String, String)`,
 *    each answering a Boolean; on `AvatarStore` the read-only `unavailable: StateFlow<Boolean>`;
 *  - in co.electriccoin.zcash.ui.screen.chat.view, file UnreadableStoreNotice.kt: the composable
 *    `UnreadableStoreNotice(...)` and the pure `unreadableStoreLines(namesUnreadable: Boolean, picturesUnreadable:
 *    Boolean): List<Int>` (the string ids it shows, names first);
 *  - on `KeptChatStoreCopies`: `copiesToWipe(listed: Collection<String>): List<String>` (the kept copies a wipe of the
 *    wallet that owns the chats deletes) and `cleanMarksForWipe(): Boolean` (one commit; true when anything is left).
 */
internal object StoreOpenerApi {
    private const val PACKAGE = "co.electriccoin.zcash.ui.screen.chat.datasource"
    private const val OPENER_FILE_CLASS = "$PACKAGE.EncryptedStoreOpenerKt"
    private const val LAUNCH_CLASS = "$PACKAGE.StoreOpenLaunch"
    private const val ENV_CLASS = "$PACKAGE.StoreOpenEnv"
    private const val PARAMETERS_TODAY = 7

    /** One process as the opener sees it: the launch object every open of that process shares (null: none exists). */
    class Process internal constructor(
        val launch: Any?
    )

    /** A new process: a launch object of its own. */
    fun newProcess(): Process = Process(launchClass()?.getDeclaredConstructor()?.newInstance())

    /** The app's own process: `ChatStoreStart.launch`. */
    fun appProcess(): Process {
        val getter =
            ChatStoreStart::class.java.methods.firstOrNull { it.name == "getLaunch" }
                ?: missing("ChatStoreStart has no launch")
        return Process(getter.invoke(ChatStoreStart))
    }

    /** `StoreOpenLaunch.forgetAll()`: the process forgets what it counted and opened (a new process, for tests only). */
    fun forgetAll(process: Process) {
        val launch = process.launch ?: missing("there is no $LAUNCH_CLASS")
        val method =
            launch.javaClass.declaredMethods.firstOrNull { it.name == "forgetAll" || it.name.startsWith("forgetAll$") }
                ?: missing("StoreOpenLaunch has no forgetAll()")
        method.isAccessible = true
        method.invoke(launch)
    }

    /**
     * Opens [name] through `openEncryptedStore` as one open of [process]; `sleep` waits for nothing. [env] sets more
     * fields of the environment by name (ZC-705); a field that does not exist yet is left out ([env]).
     */
    @Suppress("LongParameterList")
    fun open(
        process: Process,
        name: String,
        plainPrefs: (String) -> SharedPreferences,
        userVisible: Boolean = true,
        nowMillis: () -> Long = System::currentTimeMillis,
        env: Map<String, Any?> = emptyMap(),
        open: () -> SharedPreferences,
    ): SharedPreferences {
        val opener = opener()
        val sleep: (Long) -> Unit = {}
        val copyOwner: () -> String? = { chatOwnerOf(plainPrefs) }
        val today = arrayOf<Any?>(name, open, plainPrefs, nowMillis, sleep, userVisible, copyOwner)
        if (opener.parameterCount == PARAMETERS_TODAY && env.isNotEmpty()) missing("openEncryptedStore takes no environment")
        val arguments = if (opener.parameterCount == PARAMETERS_TODAY) today else today + env(process, env)
        return try {
            opener.invoke(null, *arguments) as SharedPreferences
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** True when the opener takes the environment (the eighth parameter). */
    fun openerTakesAnEnvironment(): Boolean = opener().parameterCount > PARAMETERS_TODAY

    private fun opener(): Method {
        val candidates = Class.forName(OPENER_FILE_CLASS).declaredMethods.filter { it.name == "openEncryptedStore" }
        val opener = candidates.maxByOrNull { it.parameterCount } ?: missing("there is no openEncryptedStore")
        if (opener.parameterCount > PARAMETERS_TODAY && opener.parameterTypes.last().name != ENV_CLASS) {
            missing("the eighth parameter of openEncryptedStore is not a StoreOpenEnv: ${opener.parameterTypes.last().name}")
        }
        opener.isAccessible = true
        return opener
    }

    /** A `StoreOpenEnv` of [process] with the [fields] set by name. */
    fun env(
        process: Process,
        fields: Map<String, Any?> = emptyMap(),
    ): Any {
        val type =
            try {
                Class.forName(ENV_CLASS)
            } catch (e: ClassNotFoundException) {
                missing("there is no $ENV_CLASS: ${e.message}")
            }
        val env = type.getDeclaredConstructor().newInstance()
        val setter = type.methods.firstOrNull { it.name == "setLaunch" } ?: missing("StoreOpenEnv.launch cannot be set")
        setter.invoke(env, process.launch ?: missing("there is no $LAUNCH_CLASS"))
        // As with the eighth parameter: a field that does not exist yet is left out (and named on stderr), so a test
        // fails on what the opener did with the environment it has, not only on a missing name.
        fields.forEach { (field, value) ->
            val name = "set" + field.replaceFirstChar { it.uppercase() }
            val set = type.methods.firstOrNull { it.name == name && it.parameterCount == 1 }
            if (set == null) System.err.println("ZC-705: StoreOpenEnv has no $field (left out)") else set.invoke(env, value)
        }
        return env
    }

    /** ZC-705: a field of a `StoreOpenEnv` by its name. */
    fun envField(
        env: Any,
        field: String,
    ): Any? {
        val getter =
            env.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name == "get" + field.replaceFirstChar { c -> c.uppercase() } || it.name == field)
            } ?: missing("ZC-705: StoreOpenEnv has no $field")
        return getter.invoke(env)
    }

    /** ZC-705: calls [method] of the launch of [process]. */
    fun launchCall(
        process: Process,
        method: String,
        vararg arguments: Any?,
    ): Any? {
        val launch = process.launch ?: missing("there is no $LAUNCH_CLASS")
        val found =
            launch.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == arguments.size }
                ?: missing("ZC-705: StoreOpenLaunch has no $method")
        return invoke(found, launch, *arguments)
    }

    /** ZC-705: how many stores of [kind] the guard met with an unprotected key entry in [process]. */
    fun plainKeysetCount(
        process: Process,
        kind: String,
    ): Int = launchCall(process, "plainKeysetCount", kind) as Int

    /** ZC-705: the guard's error-level lines of [process]. */
    @Suppress("UNCHECKED_CAST")
    fun guardLines(process: Process): List<String> = launchCall(process, "guardLines") as List<String>

    /** ZC-705: `StoreOpenLaunch.keptAfterLimit()`. */
    fun keptAfterLimit(process: Process): Boolean = launchCall(process, "keptAfterLimit") as Boolean

    /** ZC-705: a function of `object KeysetGuard` by name. */
    fun guard(
        function: String,
        vararg arguments: Any?,
    ): Any? {
        val type = classOrMissing("$PACKAGE.KeysetGuard")
        val instance = type.getField("INSTANCE").get(null)
        val found =
            type.methods.firstOrNull { it.name == function && it.parameterCount == arguments.size }
                ?: missing("ZC-705: KeysetGuard has no $function")
        return invoke(found, instance, *arguments)
    }

    fun isPlain(entry: String?): Boolean = guard("isPlain", entry) as Boolean

    fun kindOf(storeName: String): String = guard("kindOf", storeName) as String

    /** ZC-705: `appStoreOpenEnv(context, processName, keepPlainAfterLimit)`. */
    fun appStoreOpenEnv(
        context: android.content.Context,
        processName: (android.content.Context) -> String?,
        keepPlainAfterLimit: Boolean = false,
    ): Any {
        val found =
            Class.forName(OPENER_FILE_CLASS).declaredMethods.firstOrNull { it.name == "appStoreOpenEnv" && it.parameterCount == 3 }
                ?: missing("ZC-705: there is no appStoreOpenEnv(context, processName, keepPlainAfterLimit)")
        found.isAccessible = true
        return invoke(found, null, context, processName, keepPlainAfterLimit)!!
    }

    /** ZC-705: what a stand-in for the three library calls behind `openEncryptedFile` was asked, in order. */
    class FileCalls(
        val masterKey: (String) -> Any = { "master-key:$it" },
        val createWithMasterKey: (String, Any) -> SharedPreferences,
        val createByKeyName: (String, String) -> SharedPreferences,
    ) {
        val asked = mutableListOf<String>()
    }

    /** ZC-705: `openEncryptedFile(context, name, masterKeyName, calls)` with [calls] behind its seam. */
    fun openEncryptedFile(
        context: android.content.Context,
        name: String,
        masterKeyName: String,
        calls: FileCalls,
    ): SharedPreferences {
        val seam = classOrMissing("$PACKAGE.EncryptedFileCalls")
        val found =
            Class.forName(OPENER_FILE_CLASS).declaredMethods.firstOrNull {
                it.name == "openEncryptedFile" && it.parameterCount == 4 && it.parameterTypes[3] == seam
            } ?: missing("ZC-705: there is no openEncryptedFile(context, name, masterKeyName, calls)")
        found.isAccessible = true
        return invoke(found, null, context, name, masterKeyName, fileCallsProxy(calls)) as SharedPreferences
    }

    /** A proxy of `EncryptedFileCalls` that records into [calls] and answers through it. */
    fun fileCallsProxy(calls: FileCalls): Any {
        val seam = classOrMissing("$PACKAGE.EncryptedFileCalls")
        return java.lang.reflect.Proxy.newProxyInstance(seam.classLoader, arrayOf(seam)) { _, method, args ->
            when (method.name) {
                "masterKey" -> {
                    calls.asked += "masterKey:${args[1]}"
                    calls.masterKey(args[1] as String)
                }
                "createWithMasterKey" -> {
                    calls.asked += "createWithMasterKey:${args[1]}:${args[2]}"
                    calls.createWithMasterKey(args[1] as String, args[2]!!)
                }
                "createByKeyName" -> {
                    calls.asked += "createByKeyName:${args[1]}:${args[2]}"
                    calls.createByKeyName(args[1] as String, args[2] as String)
                }
                "toString" -> "FileCalls"
                "hashCode" -> System.identityHashCode(calls)
                "equals" -> false
                else -> missing("unexpected call ${method.name}")
            }
        }
    }

    /** ZC-733-A: the holder of a store object (the private field `store` of ContactBookImpl or AvatarStore). */
    fun holderOf(storeObject: Any): Any {
        val field =
            storeObject.javaClass.declaredFields.firstOrNull { it.name == "store" && it.type.simpleName == "LazyEncryptedStore" }
                ?: missing("ZC-733-A: ${storeObject.javaClass.simpleName} holds no LazyEncryptedStore in a field named store")
        field.isAccessible = true
        return field.get(storeObject) ?: missing("ZC-733-A: the holder of ${storeObject.javaClass.simpleName} is null")
    }

    /** ZC-733-A: `AiPreferences.holder(context)`, the one holder of the AI store for that application context. */
    fun aiHolder(context: android.content.Context): Any = aiCompanion("holder", context) ?: missing("ZC-733-A: no AI holder")

    /** ZC-733-A: `AiPreferences.askAtStartIfPresent(context)`. */
    fun aiAskAtStartIfPresent(context: android.content.Context) {
        aiCompanion("askAtStartIfPresent", context)
    }

    private fun aiCompanion(
        function: String,
        context: android.content.Context,
    ): Any? {
        val type = Class.forName("co.electriccoin.zcash.ui.screen.ai.AiPreferences")
        val companion = type.getField("Companion").get(null)
        val found =
            companion.javaClass.methods.firstOrNull { it.name == function && it.parameterCount == 1 }
                ?: missing("ZC-733-A: AiPreferences has no $function(context)")
        return invoke(found, companion, context)
    }

    /** ZC-733-A: puts [calls] behind the holder's library seam and makes its waits instant (recorded in [waits]). */
    fun useCalls(
        holder: Any,
        calls: FileCalls,
        waits: MutableList<Long> = mutableListOf(),
    ) {
        setter(holder, "fileCalls").invoke(holder, fileCallsProxy(calls))
        val sleep: (Long) -> Unit = { waits += it }
        setter(holder, "sleep").invoke(holder, sleep)
    }

    private fun setter(
        target: Any,
        property: String,
    ): Method {
        val name = "set" + property.replaceFirstChar { it.uppercase() }
        return target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 1 }
            ?: missing("ZC-733-A: ${target.javaClass.simpleName} has no settable $property")
    }

    /** ZC-733-A: `askAtStart()`, `get()` or `tryAgain()` of a holder, or `askAtStart()` of a store class. */
    fun ask(
        target: Any,
        function: String,
    ): Any? = memberCall(target, function)

    /** ZC-733-A: the holder's `unavailable` flag. */
    @Suppress("UNCHECKED_CAST")
    fun unavailable(holder: Any): Boolean = (memberCall(holder, "getUnavailable") as kotlinx.coroutines.flow.StateFlow<Boolean>).value

    /** ZC-733-A: a new `OtherStoreUnavailableException(message, cause)`. */
    fun otherStoreError(
        message: String,
        cause: Throwable,
    ): Throwable {
        val type = classOrMissing("$PACKAGE.OtherStoreUnavailableException")
        return type.getConstructor(String::class.java, Throwable::class.java).newInstance(message, cause) as Throwable
    }

    /** ZC-733-A: the start function `openStoresAtStart(openChatStorage, askContactBook, askPictureIndex, askAiStore)`. */
    fun openStoresAtStart(
        openChatStorage: () -> Unit,
        askContactBook: () -> Unit,
        askPictureIndex: () -> Unit,
        askAiStore: () -> Unit,
    ) {
        val type = Class.forName("co.electriccoin.zcash.ui.screen.walletstartup.StartupStorageCheckKt")
        val found =
            type.declaredMethods.firstOrNull { it.name == "openStoresAtStart" && it.parameterCount == 4 }
                ?: missing("ZC-733-A: there is no openStoresAtStart(openChatStorage, askContactBook, askPictureIndex, askAiStore)")
        invoke(found, null, openChatStorage, askContactBook, askPictureIndex, askAiStore)
    }

    /** ZC-705: the internal function behind `openPhraseStoreGuarded`, with the environment's [env] fields set. */
    fun openPhraseStoreGuarded(
        process: Process,
        filename: String,
        plainPrefs: (String) -> SharedPreferences,
        env: Map<String, Any?> = emptyMap(),
        open: () -> SharedPreferences,
    ): SharedPreferences {
        val type = classOrMissing("$PACKAGE.KeysetGuardKt")
        val found =
            type.declaredMethods.firstOrNull { it.name == "openPhraseStoreGuardedWith" && it.parameterCount == 5 }
                ?: missing("ZC-705: there is no openPhraseStoreGuardedWith(filename, open, plainPrefs, env, sleep)")
        found.isAccessible = true
        val sleep: (Long) -> Unit = {}
        return invoke(found, null, filename, open, plainPrefs, env(process, env), sleep) as SharedPreferences
    }

    /** ZC-705: `openSetAsideBundle(name, open, plainPrefs, userVisible, env)` with the environment of [process]. */
    fun openSetAsideBundle(
        process: Process,
        name: String,
        plainPrefs: (String) -> SharedPreferences,
        userVisible: Boolean,
        open: () -> SharedPreferences,
    ): SharedPreferences {
        val found =
            Class.forName("$PACKAGE.ChatWalletOwnershipStoresKt").declaredMethods.firstOrNull {
                it.name == "openSetAsideBundle" && it.parameterCount == 5
            } ?: missing("there is no openSetAsideBundle(name, open, plainPrefs, userVisible, env)")
        found.isAccessible = true
        return invoke(found, null, name, open, plainPrefs, userVisible, env(process)) as SharedPreferences
    }

    /** ZC-709: `KeptChatStoreCopies.askable()`: this wallet's copies the Settings row may offer again. */
    @Suppress("UNCHECKED_CAST")
    fun askable(copies: KeptChatStoreCopies): List<String> = memberCall(copies, "askable") as List<String>

    /** ZC-709: `KeptChatStoreCopies.reoffer()`: the Settings row's action (held and declined copies offered again). */
    fun reoffer(copies: KeptChatStoreCopies): Boolean = memberCall(copies, "reoffer") as Boolean

    private fun memberCall(
        target: Any,
        method: String,
    ): Any? {
        val found =
            target.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == 0 }
                ?: missing("${target.javaClass.name} has no $method()")
        return invoke(found, target)
    }

    /** ZC-733-B: the holder's wallet store `walletStore(storeId)` (a WalletScopedStore). */
    fun walletStore(
        holder: Any,
        storeId: String,
    ): WalletScopedStore {
        val found =
            holder.javaClass.methods.firstOrNull { it.name == "walletStore" && it.parameterCount == 1 }
                ?: missing("ZC-733-B: ${holder.javaClass.simpleName} has no walletStore(storeId)")
        return invoke(found, holder, storeId) as WalletScopedStore
    }

    /** ZC-733-B: `KeptChatStoreCopies.copiesToWipe(listed)`. */
    @Suppress("UNCHECKED_CAST")
    fun copiesToWipe(
        copies: KeptChatStoreCopies,
        listed: Collection<String>,
    ): List<String> {
        val found =
            copies.javaClass.methods.firstOrNull { it.name == "copiesToWipe" && it.parameterCount == 1 }
                ?: missing("ZC-733-B: KeptChatStoreCopies has no copiesToWipe(listed)")
        return invoke(found, copies, listed) as List<String>
    }

    /** ZC-733-B: `KeptChatStoreCopies.cleanMarksForWipe()`: true when anything is left in the file of marks. */
    fun cleanMarksForWipe(copies: KeptChatStoreCopies): Boolean = memberCall(copies, "cleanMarksForWipe") as Boolean

    /** ZC-733-B: a member flag (`unreadable` of a ContactBook, `unavailable` of AvatarStore) by its getter. */
    @Suppress("UNCHECKED_CAST")
    fun flag(
        target: Any,
        getter: String,
    ): Boolean = (memberCall(target, getter) as kotlinx.coroutines.flow.StateFlow<Boolean>).value

    /** ZC-733-B: `tryAddContact`, `tryRemoveContact` or `tryUpdateContactName` of a ContactBook. */
    fun tryContact(
        book: Any,
        function: String,
        vararg arguments: Any?,
    ): Boolean {
        val found =
            book.javaClass.methods.firstOrNull { it.name == function && it.parameterCount == arguments.size }
                ?: missing("ZC-733-B: ${book.javaClass.simpleName} has no $function")
        return invoke(found, book, *arguments) as Boolean
    }

    /** ZC-733-B: `unreadableStoreLines(namesUnreadable, picturesUnreadable)`. */
    @Suppress("UNCHECKED_CAST")
    fun unreadableStoreLines(
        names: Boolean,
        pictures: Boolean,
    ): List<Int> {
        val type =
            try {
                Class.forName("co.electriccoin.zcash.ui.screen.chat.view.UnreadableStoreNoticeKt")
            } catch (_: ClassNotFoundException) {
                missing("ZC-733-B: there is no UnreadableStoreNotice.kt")
            }
        val found =
            type.declaredMethods.firstOrNull { it.name == "unreadableStoreLines" && it.parameterCount == 2 }
                ?: missing("ZC-733-B: there is no unreadableStoreLines(namesUnreadable, picturesUnreadable)")
        return invoke(found, null, names, pictures) as List<Int>
    }

    /** A class of this package by its simple name, or null. */
    fun classOrNull(simpleName: String): Class<*>? =
        try {
            Class.forName("$PACKAGE.$simpleName")
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun classOrMissing(name: String): Class<*> =
        try {
            Class.forName(name)
        } catch (_: ClassNotFoundException) {
            missing("ZC-705: there is no $name")
        }

    private fun invoke(
        method: Method,
        target: Any?,
        vararg arguments: Any?,
    ): Any? =
        try {
            method.invoke(target, *arguments)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun launchClass(): Class<*>? =
        try {
            Class.forName(LAUNCH_CLASS)
        } catch (_: ClassNotFoundException) {
            null
        }

    /** Every Kotlin file under any module's src/main, comments stripped, by its path from the repo root. */
    fun mainSources(): Map<String, String> =
        repoDir
            .listFiles { f -> f.isDirectory && f.resolve("src/main").isDirectory }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { module ->
                module
                    .resolve("src/main")
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .map { it.relativeTo(repoDir).invariantSeparatorsPath to stripComments(it.readText()) }
                    .toList()
            }.toMap()

    /** One file of ui-lib's product code, comments stripped. */
    fun mainSource(relative: String): String {
        val path = "ui-lib/src/main/java/co/electriccoin/zcash/$relative"
        return mainSources()[path] ?: missing("missing $path")
    }

    /** The argument text of every call (not the declaration) of [function] in [code], with where it starts. */
    fun calls(
        code: String,
        function: String
    ): List<Call> =
        Regex("""\b${Regex.escape(function)}\(""")
            .findAll(code)
            .filterNot { code.substring(0, it.range.first).trimEnd().endsWith("fun") }
            .map { match ->
                val open = match.range.last
                Call(
                    arguments = code.substring(open + 1, closing(code, open)),
                    enclosingFunction =
                        Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
                            .findAll(code.substring(0, match.range.first))
                            .lastOrNull()
                            ?.groupValues
                            ?.get(1),
                )
            }.toList()

    data class Call(
        val arguments: String,
        val enclosingFunction: String?
    )

    /** The text of [code] from the declaration of [function] up to the next declaration at the start of a line. */
    fun declaration(
        code: String,
        function: String
    ): String {
        val start =
            Regex("""\bfun\s+${Regex.escape(function)}\(""").find(code)?.range?.first
                ?: missing("there is no fun $function(")
        val next = Regex("""\n(?=(?:@|internal |private |public |fun |class |object |val |const ))""").find(code, start + 1)
        return code.substring(start, next?.range?.first ?: code.length)
    }

    /** Index of the parenthesis that closes the one at [open] (string literals are skipped). */
    private fun closing(
        code: String,
        open: Int
    ): Int {
        var depth = 0
        var inString = false
        var i = open
        while (i < code.length) {
            val c = code[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) return i
            }
            i++
        }
        missing("unbalanced parentheses after index $open")
    }

    /** Removes line and block comments; string literals are kept as they are. */
    fun stripComments(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        while (i < code.length) {
            val c = code[i]
            val next = code.getOrNull(i + 1)
            when {
                c == '"' -> {
                    val end = stringEnd(code, i)
                    out.append(code, i, end)
                    i = end
                }
                c == '\'' && charLiteralEnd(code, i) > 0 -> {
                    val end = charLiteralEnd(code, i)
                    out.append(code, i, end)
                    i = end
                }
                c == '/' && next == '/' -> {
                    while (i < code.length && code[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    val end = code.indexOf("*/", i + 2)
                    i = if (end < 0) code.length else end + 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Index just after the character literal that starts at [start], or -1 when it is not one. */
    private fun charLiteralEnd(
        code: String,
        start: Int
    ): Int {
        val close = if (code.getOrNull(start + 1) == '\\') code.indexOf('\'', start + 3) else start + 2
        return if (close in (start + 2)..(start + 8) && code.getOrNull(close) == '\'') close + 1 else -1
    }

    /** Index just after the string literal that starts at [start] (a raw string or a plain one). */
    private fun stringEnd(
        code: String,
        start: Int
    ): Int {
        if (code.startsWith("\"\"\"", start)) {
            val end = code.indexOf("\"\"\"", start + 3)
            return if (end < 0) code.length else end + 3
        }
        var i = start + 1
        while (i < code.length && code[i] != '"' && code[i] != '\n') {
            if (code[i] == '\\') i++
            i++
        }
        return minOf(i + 1, code.length)
    }

    private val repoDir: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        generateSequence(start) { it.parentFile }
            .take(8)
            .firstOrNull { it.resolve("settings.gradle.kts").isFile && it.resolve("ui-lib/src/main").isDirectory }
            ?: missing("could not locate the repo root from ${start.path}")
    }

    fun missing(message: String): Nothing = throw AssertionError(message)
}
