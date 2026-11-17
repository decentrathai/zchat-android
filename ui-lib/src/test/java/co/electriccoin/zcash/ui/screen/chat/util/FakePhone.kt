package co.electriccoin.zcash.ui.screen.chat.util

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.PreferenceHolder
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.FlexaRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-692-W / ZC-692-S (register ZC-692): a phone for the JVM tests of Destroy All's wipe.
 *
 * It gives every root of the app's own storage a folder under one temp folder ([dataDir], [deviceProtectedDir], two
 * external volumes), and it stands in for everything the wipe asks Android: the phone-security keys, the two process
 * lists, the notification manager, the clipboard, the package manager, the job library, the folder sync and the swap
 * of the default handler for uncaught errors. It does that by replacing, by reflection, the private function fields
 * of the product object `DestroyWipe` ([SEAMS]); [close] puts every one of them back to its default and sets
 * `DestroyWipe`'s state of the process ([STATE]) back, so nothing leaks into the next test. Every name of
 * `DestroyWipe` is reached by reflection: this file compiles without it, and then no stand-in is in place
 * ([wipeExists] is false) and the tests fail on what they assert.
 *
 * Its file layer (write, rename, delete, folder sync) can refuse a write or a delete ([refuseWrite],
 * [refuseDelete]), throw an [Error] at a named step and do nothing from then on, as a killed process would
 * ([killAt]), and lose what no folder sync made durable ([powerCut]).
 *
 * Every call is written to [events] with the thread it ran on. A stand-in named in [hangAt] never answers, one in
 * [errorAt] throws an [Error], one in [failAt] throws an exception. Time is virtual: the wait for a step that hangs
 * returns at once and is counted in [waits] with the limit that was asked.
 */
internal class FakePhone(
    val packageName: String? = "xyz.example.zchat",
) : AutoCloseable {
    /** Thrown by a stand-in that plays a kill, an error nothing expects or the end of a call that never answered. */
    class PhoneError(
        step: String
    ) : Error("FakePhone: $step")

    data class Event(
        val name: String,
        val thread: Thread
    )

    val root: File = Files.createTempDirectory("fake-phone").toFile()
    val dataDir = File(root, "data")
    val deviceProtectedDir = File(root, "de")
    val volumes = listOf("vol0", "vol1").map { File(root, "$it/Android/data/${packageName ?: "pkg"}") }

    val ownPid = 4000
    val ownUid = 10_123

    private val log = CopyOnWriteArrayList<Event>()
    val events: List<String> get() = log.map { it.name }

    fun threadsOf(prefix: String): Set<Thread> = log.filter { it.name.startsWith(prefix) }.map { it.thread }.toSet()

    fun count(prefix: String): Int = log.count { it.name.startsWith(prefix) }

    // ------------------------------------------------------------------ faults

    val hangAt: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val errorAt: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val failAt: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** ZC-692-S: the phone dies right at this stand-in (a kill of the process), as [killAt] does for the file layer. */
    val dieAt: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** File layer: (operation, file) -> the phone dies right before it. Operations: write, rename, delete, sync. */
    @Volatile
    var killAt: ((String, File) -> Boolean)? = null

    @Volatile
    var refuseWrite: (File) -> Boolean = { false }

    @Volatile
    var refuseDelete: (File) -> Boolean = { false }

    /** True after a kill or a power cut: no stand-in does anything any more. */
    @Volatile
    var dead = false
        private set

    private val hung: MutableSet<Thread> = ConcurrentHashMap.newKeySet()
    private val release = CountDownLatch(1)

    /** (thread name, the limit in ms) of every wait that ran out, in order. */
    val waits = CopyOnWriteArrayList<Pair<String, Long>>()

    // ------------------------------------------------------------------ what the stand-ins hold

    val keys: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val procList = CopyOnWriteArrayList<Triple<Int, Int, String>>()
    val androidList = CopyOnWriteArrayList<Triple<Int, Int, String>>()

    /** Processes that stay listed after they were asked to end. */
    val unkillable: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    val channels = CopyOnWriteArrayList<String>()
    val channelGroups = CopyOnWriteArrayList<String>()

    /**
     * ZC-888: how many channel deletes Android refuses (SecurityException: the channel of a foreground service that is
     * still stopping); Int.MAX_VALUE refuses every one.
     */
    val channelDeleteRefusals = AtomicInteger(0)

    /** ZC-888: channel groups that a delete does not remove. */
    @Volatile
    var channelGroupsStay = false

    /** ZC-888: background jobs still pending after the cancel of all work. */
    @Volatile
    var pendingWorkStays = 0

    /**
     * ZC-1066: what the start pass's copy of the kept names does on this phone (KeepRig gives it the rig's contact book
     * and list); by default it copies nothing.
     */
    @Volatile
    var startCopy: () -> Boolean = { false }

    @Volatile
    var installTime = 1_700_000_000_123L

    /** ZC-692-S: the name of this process as the app's own provider stored it; null = it cannot be determined. */
    @Volatile
    var processName: String? = packageName

    /** ZC-692-S: how many notifications of the app are on screen; a cancel-all removes them unless they are stubborn. */
    @Volatile
    var activeNotifications = 0

    @Volatile
    var stubbornNotifications = false

    /** Runs inside the uninstall request (`startActivity`): the place of a late writer. */
    @Volatile
    var onUninstallRequest: () -> Unit = {}

    val handlerSwaps = CopyOnWriteArrayList<Thread.UncaughtExceptionHandler?>()
    val dataDirReadsAfterUninstall = CopyOnWriteArrayList<Thread>()

    @Volatile
    private var uninstallRequested = false

    val processEndRequested: Boolean get() = "kill:$ownPid" in events

    // ------------------------------------------------------------------ the product object, by reflection

    private val wipeClass: Class<*>? = runCatching { Class.forName(WIPE_CLASS) }.getOrNull()
    val wipeExists: Boolean get() = wipeClass != null

    private fun field(name: String) =
        wipeClass?.declaredFields?.firstOrNull { it.name == name }?.apply { isAccessible = true }

    /** The value of a field of `DestroyWipe` (a replaceable call, a state field or a constant); fails when missing. */
    fun wipeField(name: String): Any? =
        (field(name) ?: throw AssertionError("DestroyWipe has no field $name")).get(null)

    fun setWipeField(
        name: String,
        value: Any?
    ) = (field(name) ?: throw AssertionError("DestroyWipe has no field $name")).set(null, value)

    /** Calls a public function of `DestroyWipe` by name; fails when the object or the function is missing. */
    fun wipe(
        name: String,
        vararg args: Any?
    ): Any? {
        val clazz = wipeClass ?: throw AssertionError("the object $WIPE_CLASS does not exist")
        val method =
            clazz.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: throw AssertionError("DestroyWipe has no public function $name with ${args.size} parameter(s)")
        try {
            return method.invoke(clazz.getField("INSTANCE").get(null), *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun enter(
        base: String,
        detail: String = ""
    ) {
        if (dead) throw PhoneError("dead at $base")
        log += Event(base + detail, Thread.currentThread())
        if (base in dieAt) {
            dead = true
            throw PhoneError("killed at $base")
        }
        if (base in errorAt) throw PhoneError("an Error at $base")
        if (base in failAt) throw IllegalStateException("FakePhone: $base fails")
        if (base in hangAt) {
            hung += Thread.currentThread()
            release.await()
            throw PhoneError("$base never answered")
        }
    }

    private fun rel(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    /**
     * ZC-090-R: a stand-in of a test, outside `DestroyWipe` (the Reset's own collaborators), is one more step of this
     * phone: written to [events] as [name] plus [detail], and it obeys [hangAt], [errorAt], [failAt] and [dieAt] by
     * [name], like the stand-ins here; a step that hangs is counted in [waits] by the wait that gives up on it.
     */
    fun step(
        name: String,
        detail: String = "",
    ) = enter(name, detail)

    // ------------------------------------------------------------------ the file layer

    private sealed class Change(
        val folder: File?
    ) {
        class Created(
            val file: File
        ) : Change(file.parentFile)

        class Renamed(
            val from: File,
            val to: File,
            val replaced: ByteArray?
        ) : Change(to.parentFile)

        class Deleted(
            val file: File,
            val bytes: ByteArray?
        ) : Change(file.parentFile)
    }

    private val unsynced = Collections.synchronizedList(ArrayList<Change>())

    private fun fileStep(
        operation: String,
        file: File
    ) {
        if (dead) throw PhoneError("dead at $operation")
        if (killAt?.invoke(operation, file) == true) {
            dead = true
            throw PhoneError("killed before $operation ${rel(file)}")
        }
        enter(operation, ":" + rel(file))
    }

    private val writeFile: (File, ByteArray) -> Unit = { file, bytes ->
        fileStep("write", file)
        if (refuseWrite(file)) throw IOException("FakePhone: write refused")
        val existed = file.exists()
        file.writeBytes(bytes)
        if (!existed) unsynced += Change.Created(file)
    }

    private val renameFile: (File, File) -> Boolean = { from, to ->
        fileStep("rename", to)
        val replaced = if (to.isFile) to.readBytes() else null
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        unsynced += Change.Renamed(from, to, replaced)
        true
    }

    private val deleteFile: (File) -> Boolean = { file ->
        fileStep("delete", file)
        if (refuseDelete(file) || !file.exists()) {
            false
        } else {
            val bytes = if (file.isFile) file.readBytes() else null
            val deleted = file.delete()
            if (deleted) unsynced += Change.Deleted(file, bytes)
            deleted
        }
    }

    private val syncFolder: (File) -> Unit = { folder ->
        fileStep("sync", folder)
        synchronized(unsynced) { unsynced.removeAll { it.folder == folder } }
        // ZC-692-S: a folder that was deleted is gone for good once the folder above it is synced, with all that
        // was deleted inside it: its entry no longer exists, so nothing below it can come back.
        synchronized(unsynced) {
            unsynced.removeAll { change ->
                val inside = change.folder
                change is Change.Deleted && inside != null && !inside.exists() && inside.startsWith(folder)
            }
        }
    }

    /** The power goes: everything no folder sync made durable is as it was before, and the phone is off. */
    fun powerCut() {
        dead = true
        synchronized(unsynced) {
            unsynced.asReversed().forEach { change ->
                when (change) {
                    is Change.Created -> change.file.delete()
                    is Change.Renamed -> {
                        if (change.to.exists()) change.to.copyTo(change.from, overwrite = true)
                        if (change.replaced != null) change.to.writeBytes(change.replaced) else change.to.delete()
                    }
                    is Change.Deleted -> {
                        change.file.parentFile?.mkdirs()
                        if (change.bytes != null) change.file.writeBytes(change.bytes) else change.file.mkdirs()
                    }
                }
            }
            unsynced.clear()
        }
    }

    /** A new process on the same phone: the stand-ins work again and `DestroyWipe`'s state is as at a start. */
    fun newProcess() {
        dead = false
        uninstallRequested = false
        STATE.forEach { name -> field(name)?.set(null, pristine[name]) }
    }

    // ------------------------------------------------------------------ the stand-ins behind DestroyWipe's fields

    private val keystoreAliases: () -> List<String> = {
        enter("keystore.list")
        synchronized(keys) { keys.toList() }
    }
    private val keystoreDelete: (String) -> Unit = { name ->
        enter("keystore.delete", ":$name")
        keys.remove(name)
    }
    private val keystoreContains: (String) -> Boolean = { name ->
        enter("keystore.contains", ":$name")
        name in keys
    }
    private val currentProcessName: (Context) -> String = {
        enter("process.name")
        processName ?: throw IllegalStateException("Unable to determine process name")
    }
    private val countActiveNotifications: (Context) -> Int = {
        enter("notifications.active")
        activeNotifications
    }
    private val cancelSyncJob: (Context) -> Unit = { enter("job.cancel") }
    private val cancelSyncAlarm: (Context) -> Unit = { enter("alarm.cancel") }
    private val procProcesses: () -> List<Triple<Int, Int, String>> = {
        enter("proc.list")
        procList.toList()
    }
    private val runningProcesses: (Context) -> List<Triple<Int, Int, String>> = {
        enter("android.list")
        androidList.toList()
    }
    private val killProcess: (Int) -> Unit = { pid ->
        // On a dead phone nothing is ended and nothing is thrown: the request to end the process is the last call.
        if (!dead) enter("kill", ":$pid")
        if (!dead && pid !in unkillable) {
            procList.removeAll { it.first == pid }
            androidList.removeAll { it.first == pid }
        }
    }
    private val ownPidCall: () -> Int = { ownPid }
    private val ownUidCall: () -> Int = { ownUid }
    private val cancelNotifications: (Context) -> Unit = {
        enter("notifications.cancelAll")
        if (!stubbornNotifications) activeNotifications = 0
    }
    private val listChannels: (Context) -> List<String> = {
        enter("channels.list")
        channels.toList()
    }
    private val deleteChannel: (Context, String) -> Unit = { _, id ->
        enter("channel.delete", ":$id")
        val refusals = channelDeleteRefusals.get()
        if (refusals > 0) {
            if (refusals != Int.MAX_VALUE) channelDeleteRefusals.decrementAndGet()
            throw SecurityException("FakePhone: the channel of a foreground service")
        }
        channels.remove(id)
    }
    private val listChannelGroups: (Context) -> List<String> = {
        enter("channelGroups.list")
        channelGroups.toList()
    }
    private val deleteChannelGroup: (Context, String) -> Unit = { _, id ->
        enter("channelGroup.delete", ":$id")
        if (!channelGroupsStay) channelGroups.remove(id)
    }
    private val clearClipboard: (Context) -> Unit = { enter("clipboard.clear") }
    private val firstInstallTime: (Context) -> Long = {
        enter("pm.firstInstallTime")
        installTime
    }
    private val cancelAllWork: (Context) -> Unit = { enter("work.cancelAll") }
    private val pruneWork: (Context) -> Unit = { enter("work.prune") }

    // ZC-888: how many background jobs are still pending (B10's finishing job left out).
    private val countPendingWork: (Context) -> Int = {
        enter("work.pending")
        pendingWorkStays
    }

    // ZC-1066: the start pass's copy of the kept names.
    private val copyKeptNamesAtStart: (Context) -> Boolean = {
        enter("names.startCopy")
        startCopy()
    }

    // DESTROY-ADV-W (B10): the alarm and the one-time job a Destroy that kept the list arms.
    private val armStartAlarm: (Context) -> Unit = { enter("alarm.arm") }
    private val enqueueStartJob: (Context) -> Unit = { enter("job.enqueue") }
    private val swapUncaughtHandler: (Thread.UncaughtExceptionHandler?) -> Thread.UncaughtExceptionHandler? = { next ->
        handlerSwaps += next
        log += Event("handler.swap", Thread.currentThread())
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(next)
        before
    }
    private val waitFor: (Thread, Long) -> Long = { thread, limitMs ->
        val realEnd = System.nanoTime() + limitMs.coerceAtLeast(1L) * NANOS_PER_MS
        while (thread.isAlive && thread !in hung && System.nanoTime() < realEnd) thread.join(2)
        if (thread.isAlive) {
            // Without what the coroutine debug mode appends to a thread's name while a coroutine runs on it.
            waits += thread.name.substringBefore(" @") to limitMs
            limitMs
        } else {
            0L
        }
    }

    private val standIns: Map<String, Any> =
        mapOf(
            "keystoreAliases" to keystoreAliases,
            "keystoreDelete" to keystoreDelete,
            "keystoreContains" to keystoreContains,
            "currentProcessName" to currentProcessName,
            "countActiveNotifications" to countActiveNotifications,
            "cancelSyncJob" to cancelSyncJob,
            "cancelSyncAlarm" to cancelSyncAlarm,
            "procProcesses" to procProcesses,
            "runningProcesses" to runningProcesses,
            "killProcess" to killProcess,
            "ownPid" to ownPidCall,
            "ownUid" to ownUidCall,
            "cancelNotifications" to cancelNotifications,
            "listChannels" to listChannels,
            "deleteChannel" to deleteChannel,
            "listChannelGroups" to listChannelGroups,
            "deleteChannelGroup" to deleteChannelGroup,
            "clearClipboard" to clearClipboard,
            "firstInstallTime" to firstInstallTime,
            "cancelAllWork" to cancelAllWork,
            "pruneWork" to pruneWork,
            "countPendingWork" to countPendingWork,
            "copyKeptNamesAtStart" to copyKeptNamesAtStart,
            "armStartAlarm" to armStartAlarm,
            "enqueueStartJob" to enqueueStartJob,
            "writeFile" to writeFile,
            "renameFile" to renameFile,
            "deleteFile" to deleteFile,
            "syncFolder" to syncFolder,
            "swapUncaughtHandler" to swapUncaughtHandler,
            "waitFor" to waitFor,
        )

    /** The replaceable fields `DestroyWipe` does not have (all of them while the object does not exist). */
    val missingSeams: List<String>

    init {
        check(standIns.keys == SEAMS.toSet()) { "FakePhone: SEAMS and the stand-ins differ" }
        synchronized(pristine) {
            (SEAMS + STATE).forEach { name ->
                val field = field(name)
                if (field != null && name !in pristine) pristine[name] = field.get(null)
            }
        }
        missingSeams = SEAMS.filter { field(it) == null }
        standIns.forEach { (name, standIn) -> field(name)?.set(null, standIn) }
        listOf(dataDir, deviceProtectedDir).forEach { it.mkdirs() }
        volumes.forEach { it.mkdirs() }
        procList += Triple(ownPid, ownUid, packageName ?: "pkg")
        androidList += Triple(ownPid, ownUid, packageName ?: "pkg")
    }

    /**
     * Puts every replaceable field of `DestroyWipe` back to its default, the real call, and keeps the folders: for
     * the cases that show the real calls bear a context with no services.
     */
    fun useRealCalls() {
        SEAMS.forEach { name -> if (name in pristine) field(name)?.set(null, pristine[name]) }
    }

    /** Puts every replaceable field and the state of `DestroyWipe` back, lets hung stand-ins go, deletes the files. */
    override fun close() {
        (SEAMS + STATE).forEach { name -> if (name in pristine) field(name)?.set(null, pristine[name]) }
        dead = true
        release.countDown()
        // ZC-090-R: a thread let go here runs on into the calls after the one that hung (a Reset's step, a wipe's part);
        // it ends before the next test puts its own stand-ins in place, so none of its calls reaches the next phone.
        hung.forEach { it.join(HUNG_THREAD_END_MS) }
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ the context

    inner class PhoneContext : ContextWrapper(null) {
        override fun getApplicationInfo(): ApplicationInfo {
            if (uninstallRequested) dataDirReadsAfterUninstall += Thread.currentThread()
            return ApplicationInfo().apply { dataDir = this@FakePhone.dataDir.path }
        }

        override fun getDataDir(): File = this@FakePhone.dataDir

        override fun getCacheDir(): File = File(this@FakePhone.dataDir, "cache")

        override fun getFilesDir(): File = File(this@FakePhone.dataDir, "files")

        override fun getNoBackupFilesDir(): File = File(this@FakePhone.dataDir, "no_backup")

        override fun getExternalCacheDir(): File? = File(volumes[0], "cache")

        override fun getExternalFilesDir(type: String?): File? = File(volumes[0], "files")

        override fun getExternalFilesDirs(type: String?): Array<File> = volumes.map { File(it, "files") }.toTypedArray()

        override fun getExternalCacheDirs(): Array<File> = volumes.map { File(it, "cache") }.toTypedArray()

        override fun createDeviceProtectedStorageContext(): Context =
            object : ContextWrapper(null) {
                override fun getDataDir(): File = deviceProtectedDir

                override fun getApplicationInfo(): ApplicationInfo =
                    ApplicationInfo().apply { dataDir = deviceProtectedDir.path }
            }

        override fun databaseList(): Array<String> = emptyArray()

        override fun getPackageName(): String? = this@FakePhone.packageName

        override fun getApplicationContext(): Context? = null

        override fun getSystemService(name: String): Any? {
            log += Event("getSystemService:$name", Thread.currentThread())
            return null
        }

        override fun stopService(service: Intent?): Boolean {
            enter("stopService")
            return true
        }

        override fun startService(service: Intent?): ComponentName? {
            enter("startService")
            return null
        }

        override fun startActivity(intent: Intent?) {
            uninstallRequested = true
            enter("startActivity")
            onUninstallRequest()
        }
    }

    val context: Context = PhoneContext()

    // ------------------------------------------------------------------ DestroyManager and what it is given

    private fun allocateBare(clazz: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, clazz)!!
    }

    private fun setField(
        target: Any,
        owner: Class<*>,
        name: String,
        value: Any?
    ) = owner.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

    /** A stand-in for an interface; [answer] gets "<method name>/<number of parameters>". */
    private fun <T> proxy(
        type: Class<T>,
        answer: (String) -> Any?
    ): T {
        val made =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "FakePhone ${type.simpleName}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    else -> answer(method.name + "/" + (args?.size ?: 0))
                }
            }
        return type.cast(made)!!
    }

    private fun holder(
        clazz: Class<out PreferenceHolder>,
        event: String
    ): PreferenceHolder {
        val provider =
            proxy(PreferenceProvider::class.java) { call ->
                if (call != "clearPreferences/1") throw UnsupportedOperationException("unexpected settings call: $call")
                enter(event)
                true
            }
        val holder = allocateBare(clazz) as PreferenceHolder
        setField(holder, PreferenceHolder::class.java, "preferenceProvider", provider)
        setField(holder, PreferenceHolder::class.java, "mutex", Mutex())
        return holder
    }

    /** A provider with no synchronizer: its current value is null, and `getSynchronizer()` would never return. */
    private fun synchronizerProvider(): SynchronizerProvider {
        val current = MutableStateFlow<Synchronizer?>(null)
        return proxy(SynchronizerProvider::class.java) { call ->
            when (call) {
                // The property's getter and the suspend function share a name: the function has one parameter more.
                "getSynchronizer/0" -> {
                    enter("synchronizer.current")
                    current
                }
                "getSynchronizer/1" -> {
                    // "Never returns" is played by an Error at once, so a wipe that calls it fails its test in
                    // milliseconds and not after a timeout: no caller of Destroy may wait here.
                    enter("synchronizer.get")
                    throw PhoneError("getSynchronizer() never returns: a wipe that calls it waits for ever")
                }
                else -> throw UnsupportedOperationException("unexpected synchronizer provider call: $call")
            }
        }
    }

    /**
     * A [DestroyManager] built without its constructor, as `DestroyLeavesNoStoreBehindTest` builds its own: every
     * field is null or false. `context` and `zchatPreferences` are always set; with [wired] also the two settings
     * providers, the synchronizer provider and Flexa. The wallet coordinator stays null (an SDK class that cannot be
     * stood in for): any call to it throws.
     */
    fun manager(wired: Boolean = true): DestroyManager {
        val manager = allocateBare(DestroyManager::class.java) as DestroyManager
        val owner = DestroyManager::class.java
        setField(manager, owner, "context", context)
        setField(
            manager,
            owner,
            "zchatPreferences",
            proxy(ZchatPreferences::class.java) { call ->
                if (call != "clearAll/0") throw UnsupportedOperationException("unexpected ZchatPreferences call: $call")
                enter("clearAll")
                null
            }
        )
        if (wired) {
            setField(
                manager,
                owner,
                "encryptedPreferenceProvider",
                holder(EncryptedPreferenceProvider::class.java, "encrypted.clear")
            )
            setField(
                manager,
                owner,
                "standardPreferenceProvider",
                holder(StandardPreferenceProvider::class.java, "standard.clear")
            )
            setField(manager, owner, "synchronizerProvider", synchronizerProvider())
            setField(
                manager,
                owner,
                "flexaRepository",
                proxy(FlexaRepository::class.java) { call ->
                    if (call != "disconnect/1") throw UnsupportedOperationException("unexpected Flexa call: $call")
                    enter("flexa.disconnect")
                    Unit
                }
            )
        }
        return manager
    }

    /** One Destroy All on [manager], from the caller's thread, as the app's callers make it. */
    fun destroy(manager: DestroyManager = manager()): DestroyManager {
        runBlocking { manager.destroyAll(requestUninstall = true) }
        return manager
    }

    // ------------------------------------------------------------------ files

    fun put(
        path: String,
        text: String = "x\n"
    ): File =
        File(root, path).apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    /** Every FILE under every root of the app's own storage, relative to the phone; folders do not count. */
    fun files(): List<String> =
        (listOf(dataDir, deviceProtectedDir) + volumes)
            .flatMap { rootFolder -> rootFolder.walkTopDown().filter { it.isFile }.toList() }
            .map { rel(it) }
            .sorted()

    val recordFile: File get() = File(dataDir, "no_backup/zchat_destroy_pending")
    val phraseStore: File get() = File(dataDir, "shared_prefs/co.electriccoin.zcash.encrypted.xml")
    val phraseStoreBackup: File get() = File(dataDir, "shared_prefs/co.electriccoin.zcash.encrypted.xml.bak")

    /** Row A17 (crash reports and the external cache on the first volume) and a file on a second volume. */
    val externalRows: List<String>
        get() =
            listOf(
                File(volumes[0], "files/log/exception/crash_1.txt"),
                File(volumes[0], "cache/external.tmp"),
                File(volumes[1], "files/log/exception/crash_2.txt"),
            ).map { rel(it) }

    /** One file for every row A1 to A17 of the spec's storage table under its real name, and the files nobody named. */
    fun seedEverything() {
        put(PHRASE_STORE, phraseStoreXml("12a9aabbcc", "1288ddeeff"))
        (ROWS_A2_TO_A16 + UNNAMED + STAYS).forEach { put(it) }
        externalRows.forEach { put(it) }
        keys += listOf("_androidx_security_master_key_", "zchat_ai_master_key")
        channels += listOf("sync_channel_v2", "chat_messages_v3", "incoming_calls_v1", "tex_recovery")
    }

    companion object {
        const val WIPE_CLASS = "co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe"
        private const val NANOS_PER_MS = 1_000_000L
        private const val HUNG_THREAD_END_MS = 2_000L

        /** The private function fields of `DestroyWipe` whose defaults are the real calls. */
        val SEAMS =
            listOf(
                "keystoreAliases",
                "keystoreDelete",
                "keystoreContains",
                "currentProcessName",
                "countActiveNotifications",
                "cancelSyncJob",
                "cancelSyncAlarm",
                "procProcesses",
                "runningProcesses",
                "killProcess",
                "ownPid",
                "ownUid",
                "cancelNotifications",
                "listChannels",
                "deleteChannel",
                "listChannelGroups",
                "deleteChannelGroup",
                "clearClipboard",
                "firstInstallTime",
                "cancelAllWork",
                "pruneWork",
                "countPendingWork",
                "copyKeptNamesAtStart",
                "armStartAlarm",
                "enqueueStartJob",
                "writeFile",
                "renameFile",
                "deleteFile",
                "syncFolder",
                "swapUncaughtHandler",
                "waitFor",
            )

        /** The state `DestroyWipe` keeps for the whole process. */
        val STATE = listOf("backgroundSyncHeld", "startOutcome", "recordWrittenByThisProcess")

        /** The value every field of [SEAMS] and [STATE] had before the first FakePhone of this JVM replaced it. */
        private val pristine = HashMap<String, Any?>()

        fun pristineValue(name: String): Any? = synchronized(pristine) { pristine[name] }

        const val PHRASE_STORE = "data/shared_prefs/co.electriccoin.zcash.encrypted.xml"
        const val KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
        const val VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__"

        /** A phrase-store file as Android writes it: the two keyset entries and one encrypted entry between them. */
        fun phraseStoreXml(
            keyKeyset: String,
            valueKeyset: String
        ): String =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                "    <string name=\"$KEY_KEYSET\">$keyKeyset</string>\n" +
                "    <string name=\"AU6gk2pQexample\">AXbQexampleCiphertext</string>\n" +
                "    <string name=\"$VALUE_KEYSET\">$valueKeyset</string>\n</map>\n"

        fun sha256Hex(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** The five lines of a record, worked out here and not by the product. */
        fun recordText(
            installTime: Long,
            store: String
        ): String {
            val body = "zchat-destroy 1\ninstall=$installTime\nstore=$store\nkeep=\n"
            return body + "end=" + sha256Hex(body) + "\n"
        }

        fun storeHash(
            keyKeyset: String,
            valueKeyset: String
        ): String = sha256Hex("$keyKeyset\n$valueKeyset")

        /** Rows A2 to A16 of the spec table 3.1 under real names (A1: the phrase store; A17: [externalRows]). */
        val ROWS_A2_TO_A16 =
            listOf(
                // A2
                "data/shared_prefs/cash.z.ecc.android.sdk.xml",
                "data/shared_prefs/cash.z.ecc.android.sdk.encrypted.xml",
                // A3
                "data/shared_prefs/zchat_e2e_keys_encrypted.xml",
                "data/shared_prefs/zchat_ratchet_state.xml",
                "data/shared_prefs/zchat_group_keys_encrypted.xml",
                "data/shared_prefs/zchat_group_messages_encrypted.xml",
                "data/shared_prefs/zchat_decrypted_text_encrypted.xml",
                "data/shared_prefs/zchat_pending_messages_encrypted.xml",
                "data/shared_prefs/zchat_nicknames_encrypted.xml",
                // A4
                "data/shared_prefs/zchat_preferences.xml",
                "data/shared_prefs/zchat_conv_mapping.xml",
                "data/shared_prefs/zchat_conversation_mode.xml",
                "data/shared_prefs/zchat_relay_set.xml",
                "data/shared_prefs/zchat_ratchet_counter_floor.xml",
                "data/shared_prefs/zchat_call_log.xml",
                // A5
                "data/shared_prefs/zchat_contact_book_enc.xml",
                "data/shared_prefs/zchat_contact_book.xml",
                "data/shared_prefs/zchat_avatar_index_enc.xml",
                "data/files/avatars/contact.jpg",
                // A6
                "data/shared_prefs/zchat_ai_prefs.xml",
                "data/files/ai_images/picture.png",
                // A7
                "data/shared_prefs/zchat_set_aside_1a2b3c4d.xml",
                // A8
                "data/shared_prefs/zchat_e2e_keys_encrypted__unreadable_1700000000000.xml",
                "data/shared_prefs/zchat_encrypted_store_open_failures.xml",
                // A9
                "data/shared_prefs/zchat_chat_owner.xml",
                "data/shared_prefs/zchat_address_cache.xml",
                "data/shared_prefs/zchat_identity_manager.xml",
                "data/shared_prefs/zchat_tex_recovery.xml",
                "data/shared_prefs/zchat_theme_prefs.xml",
                "data/shared_prefs/zchat_update_check.xml",
                "data/shared_prefs/zchat_post_upgrade.xml",
                "data/shared_prefs/co.electriccoin.zcash.xml",
                // A10
                "data/files/address_book/address_book_1",
                "data/files/metadata/metadata_1",
                // A11
                "data/files/pczt/transaction.pczt",
                "data/files/qa/seams.json",
                "data/files/profileInstalled",
                // A12
                "data/cache/zchat_files/photo.jpg",
                "data/cache/zchat_audio/voice.m4a",
                "data/cache/share-inbox/shared.bin",
                "data/cache/apk_updates/update.apk",
                "data/cache/ai-share/answer.png",
                // A13
                "data/databases/old_sdk_name.db",
                // A14
                "data/no_backup/co.electricoin.zcash/zcash_sdk_mainnet_data.sqlite3",
                "data/no_backup/co.electricoin.zcash/zcash_sdk_mainnet_data.sqlite3.set-aside-1",
                "data/no_backup/co.electricoin.zcash/tor_dir/state",
                // A15 (the part that goes)
                "data/no_backup/address_book",
                // A16
                "de/shared_prefs/device_protected.xml",
            )

        /** Files nobody named: only a wipe that lists finds them. */
        val UNNAMED =
            listOf(
                "data/shared_prefs/some_library.xml",
                "data/app_other/x",
                "data/files/new_folder/x",
            )

        /** What may be left besides the record: the system's code cache and the job library's own table. */
        val STAYS =
            listOf(
                "data/code_cache/x",
                "data/no_backup/androidx.work.workdb",
                "data/no_backup/androidx.work.workdb-wal",
            )
    }
}
