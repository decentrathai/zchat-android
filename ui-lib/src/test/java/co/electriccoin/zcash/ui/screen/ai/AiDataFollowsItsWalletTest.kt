package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.screen.changeidentity.IdentityManagerImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.AddressCacheImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.AvatarStore
import co.electriccoin.zcash.ui.screen.chat.datasource.CHAT_OWNER_KEY
import co.electriccoin.zcash.ui.screen.chat.datasource.CHAT_OWNER_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatClaim
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatStoreStart
import co.electriccoin.zcash.ui.screen.chat.datasource.ChatWalletOwnership
import co.electriccoin.zcash.ui.screen.chat.datasource.ContactBookImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME
import co.electriccoin.zcash.ui.screen.chat.datasource.InMemoryBundles
import co.electriccoin.zcash.ui.screen.chat.datasource.KeptChatStoreCopies
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhone
import co.electriccoin.zcash.ui.screen.chat.datasource.KeysetPhoneContext
import co.electriccoin.zcash.ui.screen.chat.datasource.Keysets
import co.electriccoin.zcash.ui.screen.chat.datasource.LiveChatStores
import co.electriccoin.zcash.ui.screen.chat.datasource.LiveChatStoresImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.NO_CHAT_OWNER
import co.electriccoin.zcash.ui.screen.chat.datasource.PrefsChatOwnerRecord
import co.electriccoin.zcash.ui.screen.chat.datasource.SetAsideBundles
import co.electriccoin.zcash.ui.screen.chat.datasource.StoreOpenerApi
import co.electriccoin.zcash.ui.screen.chat.datasource.UNREADABLE_STORE_COPY_MARKER
import co.electriccoin.zcash.ui.screen.chat.datasource.UntaggedChatMarks
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.security.KeyStoreException

/**
 * ZC-718 (register ZC-718, high; affects 2.12.4: yes; RESET-LEFTOVERS-SPEC.md revision 2d, sections 3.4 and 6.1): the
 * AI chats, the AI pictures and the AI access key follow their wallet. After a different recovery phrase is loaded the
 * wallet sees no AI chat, no AI picture and no AI access key of another wallet; the same phrase gets its own back on
 * this phone with no server call; a Reset without "keep" deletes the AI chats and pictures and KEEPS the access key in
 * the reset wallet's own set-aside file (rule K-0, WAIVERS.md W-52).
 *
 * The real ChatWalletOwnership and the real LiveChatStoresImpl over the real AiPreferences (its file in a
 * [KeysetPhone], opened through the one holder of ZC-733-A with the library seam of [StoreOpenerApi]), the real
 * contact book, picture index, address cache and identity manager, and the real AiImageStore over a temporary files
 * folder; the set-aside bundles in memory. The owner the picture store reads and the owner the ownership writes are
 * one: the plain owner file of [PrefsChatOwnerRecord] over the test's own context.
 *
 * PICTURES ARE PLAIN FILES HERE: AiImageStore.save and loadBitmap need Android's Base64 and BitmapFactory, so the test
 * writes a few bytes where the store keeps a picture (files/ai_images/<owner tag>/<id>.png; flat files/ai_images/<id>.png
 * as 2.12.4 kept them) and asks the store's file-level function `fileFor(id)` (the file a picture is read from, or
 * none) where a picture is found. Every new name is reached by name ([Api]), so this class compiles before the fix.
 *
 * Two cases are guards and pass before and after the fix: (f) "the known limit, pinned" and (q1) "a phone that never
 * used AI". Wallet tags are synthetic ('a'.repeat(64)); every entry is synthetic.
 */
class AiDataFollowsItsWalletTest {
    private class Phone {
        val data: File = Files.createTempDirectory("zchat-zc718-phone").toFile().apply { deleteOnExit() }
        val files = KeysetPhone(File(data, "shared_prefs"))
        val context = KeysetPhoneContext(files, dataDir = data)

        /** A file that exists cannot be opened (its key does not answer); a new one can, unless [cannotMakeNew]. */
        var oldUnreadable = false
        var cannotMakeNew = false
        val calls =
            StoreOpenerApi.FileCalls(
                createWithMasterKey = { name, _ ->
                    if (cannotMakeNew) throw KeyStoreException("synthetic: no answer")
                    files.Library(name).open()
                },
                createByKeyName = { name, _ ->
                    if (oldUnreadable) throw KeyStoreException("synthetic: no answer")
                    files.Library(name).open()
                },
            )
        val book = ContactBookImpl(context)
        val avatars = AvatarStore(context)

        init {
            StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(book), calls)
            StoreOpenerApi.useCalls(StoreOpenerApi.holderOf(avatars), calls)
            StoreOpenerApi.useCalls(StoreOpenerApi.aiHolder(context), calls)
        }

        val ai = AiPreferences(context)
        val images = AiImageStore(context)
        val bundles = InMemoryBundles()
        val record = PrefsChatOwnerRecord(context)
        val copies = KeptChatStoreCopies(plainPrefs = files::plain, fileNames = files::listing)
        val preferences: ZchatPreferences = realPreferences().first
        val live: LiveChatStores by lazy {
            Api.liveStores(preferences, book, avatars, AddressCacheImpl(context), IdentityManagerImpl(context), ai)
        }
        val ownership by lazy { ChatWalletOwnership(record, bundles, live, Api.tagger(images, copies)) }

        fun owner(tag: String?) {
            if (tag == null) {
                files.remove(CHAT_OWNER_PREFS_NAME)
            } else {
                files.put(CHAT_OWNER_PREFS_NAME, mapOf(CHAT_OWNER_KEY to tag))
            }
        }

        val pictures: File get() = File(context.filesDir, "ai_images")

        /** Where the store keeps picture [id] of [owner] (null: the flat folder of 2.12.4). */
        fun pictureFile(
            owner: String?,
            id: String,
        ): File = File(if (owner == null) pictures else File(pictures, owner), "$id.png")

        fun putPicture(
            owner: String?,
            id: String,
        ): File = pictureFile(owner, id).apply { parentFile?.mkdirs() }.apply { writeBytes(byteArrayOf(1, 2, 3)) }

        /** Wallet A's AI data: a key, a user id, a chat list, a picture list and one picture file. */
        fun walletAWithData(): File {
            owner(WALLET_A)
            ai.saveCredentials(TOKEN_A, USER_A)
            ai.setConversationsJson(CHATS_A)
            ai.setImagesJson(IMAGES_A)
            return putPicture(WALLET_A, PICTURE_A)
        }

        /** The AI store's entries without the library's own two key sets (the real library never lists them). */
        fun aiEntries(): Map<String, Any?> = files.entries(AI).filterKeys { !it.startsWith(LIBRARY_ENTRY) }
    }

    @Before
    fun newProcess() {
        StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())
        ChatStoreStart.userVisible = false
    }

    @After
    fun endProcess() = StoreOpenerApi.forgetAll(StoreOpenerApi.appProcess())

    // (a) FAILS today: the AI store is not among the stores a claim moves (LiveChatStoresImpl.stores()).
    @Test
    fun `a - a different phrase sees no AI key, chat or picture of the wallet before it`() {
        val phone = Phone()
        val pictureA = phone.walletAWithData()

        assertEquals(ChatClaim.STARTED_EMPTY, phone.ownership.claimFor(WALLET_B))

        assertNull("B holds A's access key", phone.ai.getToken())
        assertNull("B holds A's user id", phone.ai.getUserId())
        assertNull("B sees A's AI chats", phone.ai.getConversationsJson())
        assertNull("B sees A's picture list", phone.ai.getImagesJson())
        assertEquals("B's picture folder is not empty", emptyList<String>(), phone.pictureFile(WALLET_B, "x").parentFile.names())
        assertTrue("A's picture file was deleted", pictureA.isFile)
        assertNull("B finds A's picture", Api.fileFor(phone.images, PICTURE_A))
    }

    // (b) FAILS today: B wrote over A's key.
    @Test
    fun `b - the same phrase returns and gets its own back, nothing of B's`() {
        val phone = Phone()
        val pictureA = phone.walletAWithData()
        phone.ownership.claimFor(WALLET_B)
        phone.ai.saveCredentials(TOKEN_B, USER_B)
        phone.ai.setConversationsJson(CHATS_B)

        assertEquals(ChatClaim.RESTORED, phone.ownership.claimFor(WALLET_A))

        assertEquals(TOKEN_A, phone.ai.getToken())
        assertEquals(USER_A, phone.ai.getUserId())
        assertEquals(CHATS_A, phone.ai.getConversationsJson())
        assertEquals(IMAGES_A, phone.ai.getImagesJson())
        assertEquals("A's picture is not found", pictureA.canonicalFile, Api.fileFor(phone.images, PICTURE_A)?.canonicalFile)
    }

    // (c) FAILS today: the same.
    @Test
    fun `c - B's own data returns too`() {
        val phone = Phone()
        phone.walletAWithData()
        phone.ownership.claimFor(WALLET_B)
        phone.ai.saveCredentials(TOKEN_B, USER_B)
        phone.ai.setConversationsJson(CHATS_B)
        phone.ownership.claimFor(WALLET_A)

        assertEquals(ChatClaim.RESTORED, phone.ownership.claimFor(WALLET_B))

        assertEquals(TOKEN_B, phone.ai.getToken())
        assertEquals(USER_B, phone.ai.getUserId())
        assertEquals(CHATS_B, phone.ai.getConversationsJson())
        assertNull(phone.ai.getImagesJson())
    }

    // (d) FAILS today: one flat folder (clearAll deletes what lies in files/ai_images/, not a wallet's folder).
    @Test
    fun `d - Clear all images under B empties B's folder and leaves A's picture`() {
        val phone = Phone()
        val pictureA = phone.walletAWithData()
        phone.ownership.claimFor(WALLET_B)
        val pictureB = phone.putPicture(WALLET_B, PICTURE_B)

        phone.images.clearAll()

        assertFalse("B's picture stayed", pictureB.exists())
        assertTrue("A's picture was deleted by B's Clear all images", pictureA.isFile)
    }

    /**
     * (e) FAILS today (the second half: one flat folder). An install from 2.12.4: no owner recorded; the AI store
     * holds exactly the seven keys 2.12.4 wrote (read with `git show v2.12.4:ui-lib/src/main/java/co/electriccoin/zcash/
     * ui/screen/ai/AiPreferences.kt`, its lines 94-100: ai_token, ai_user_id, ai_selected_model, ai_selected_image_model,
     * ai_conversations, ai_images, ai_retention_days) and two flat pictures.
     */
    @Test
    fun `e - an install from 2_12_4 is adopted with its pictures, and the next wallet does not see them`() {
        val phone = Phone()
        phone.owner(null)
        phone.ai.saveCredentials(TOKEN_A, USER_A)
        phone.ai.setSelectedChatModel("synthetic-chat-model")
        phone.ai.setSelectedImageModel("synthetic-image-model")
        phone.ai.setConversationsJson(CHATS_A)
        phone.ai.setImagesJson(IMAGES_A)
        phone.ai.setRetentionDays(7)
        val before = phone.aiEntries()
        assertEquals("not 2.12.4's seven keys", SEVEN_KEYS_OF_2124, before.keys)
        phone.putPicture(null, PICTURE_A)
        phone.putPicture(null, PICTURE_B)

        assertEquals(ChatClaim.ADOPTED, phone.ownership.claimFor(WALLET_A))

        assertEquals("an entry changed at the adoption", before, phone.aiEntries())
        assertNotNull("the first flat picture is not found", Api.fileFor(phone.images, PICTURE_A))
        assertNotNull("the second flat picture is not found", Api.fileFor(phone.images, PICTURE_B))

        phone.ownership.claimFor(WALLET_B)
        assertNull("B finds the adopted picture", Api.fileFor(phone.images, PICTURE_A))
        assertNull("B finds the adopted picture", Api.fileFor(phone.images, PICTURE_B))
        phone.images.clearAll()
        phone.ownership.claimFor(WALLET_A)
        assertNotNull("B's Clear all images deleted A's picture", Api.fileFor(phone.images, PICTURE_A))
        assertNotNull("B's Clear all images deleted A's picture", Api.fileFor(phone.images, PICTURE_B))
    }

    /**
     * (f) GUARD, passes before and after the fix: THE KNOWN LIMIT, PINNED (residual R-A1; the known-issue line of spec
     * 5.3: "AI chats and AI pictures that were on this phone when ZCHAT updated go to the first wallet you open"). A
     * wallet was reset on 2.12.4, so no owner is recorded and no wallet is saved, and A's AI data is still on the phone.
     * Nothing on the phone says whose it is: the first phrase loaded after the update, B, adopts it.
     */
    @Test
    fun `f - the known limit R-A1 - the first phrase after the update adopts AI data nobody owns`() {
        val phone = Phone()
        phone.owner(null)
        phone.ai.saveCredentials(TOKEN_A, USER_A)
        phone.ai.setConversationsJson(CHATS_A)

        assertEquals(ChatClaim.ADOPTED, phone.ownership.claimFor(WALLET_B))

        assertEquals(TOKEN_A, phone.ai.getToken())
        assertEquals(CHATS_A, phone.ai.getConversationsJson())
    }

    // (g) FAILS today: no such step (the picture names a function the store does not have).
    @Test
    fun `g - a flat picture that cannot be moved - the claim goes on, the picture stays flat and is nobody's`() {
        val phone = Phone()
        phone.owner(null)
        phone.ai.saveCredentials(TOKEN_A, USER_A)
        phone.ai.setImagesJson(IMAGES_A)
        val flat = phone.putPicture(null, PICTURE_A)
        File(phone.pictures, WALLET_A).writeBytes(byteArrayOf(0)) // the wallet's folder name is taken by a plain file

        val claim = runCatching { phone.ownership.claimFor(WALLET_A) }

        assertEquals("the claim did not go on", ChatClaim.ADOPTED, claim.getOrNull())
        assertTrue("the picture left the flat folder", flat.isFile)
        assertEquals("the picture is not read where it is", flat.canonicalFile, Api.fileFor(phone.images, PICTURE_A)?.canonicalFile)
        phone.ownership.claimFor(WALLET_B)
        assertNull("the next wallet's list names the picture", phone.ai.getImagesJson())
        phone.images.clearAll()
        assertTrue("the next wallet's Clear all images deleted it", flat.isFile)
    }

    // (h) FAILS today: no such step. WalletRepository.updateWalletEndpoint (Choose Server) has no catch.
    @Test
    fun `h - Choose Server on an upgraded phone - the claim for the same wallet adopts and throws nothing`() {
        val phone = Phone()
        phone.owner(null)
        val flat = phone.putPicture(null, PICTURE_A)
        File(phone.pictures, WALLET_A).writeBytes(byteArrayOf(0))

        val claim = runCatching { phone.ownership.claimFor(WALLET_A) }

        assertEquals("Choose Server's claim threw", ChatClaim.ADOPTED, claim.getOrNull())
        assertEquals(flat.canonicalFile, Api.fileFor(phone.images, PICTURE_A)?.canonicalFile)
        val repository = StoreOpenerApi.mainSource("ui/common/repository/WalletRepository.kt")
        val endpoint = repository.substringAfter("override fun updateWalletEndpoint(", "").substringBefore("\n    }")
        assertTrue("updateWalletEndpoint no longer saves the wallet (the claim)", "persistWalletInternal(" in endpoint)
    }

    // (i) FAILS today: the AI store is never read by a claim.
    @Test
    fun `i - an AI store that cannot be read - handed over as the holder's wallet store`() {
        val source = StoreOpenerApi.stripComments(StoreOpenerApi.mainSource("ui/screen/ai/AiPreferences.kt"))
        val handOver = source.substringAfter("fun walletScopedStores(", "")
        assertTrue("AiPreferences has no walletScopedStores()", handOver.isNotEmpty())
        val body = handOver.substringBefore("\n    fun ").substringBefore("\n    companion object")
        assertTrue("not the holder's wallet store", "walletStore(" in body)
        assertFalse("the plain adapter", "PrefsWalletScopedStore(" in body)

        val setAside = Phone()
        setAside.owner(WALLET_A)
        setAside.files.put(AI, Keysets.wrappedEntries() + mapOf("ai_token" to "synthetic-unreadable"))
        setAside.oldUnreadable = true
        val claim = runCatching { setAside.ownership.claimFor(WALLET_B) }
        assertTrue("the claim failed: $claim", claim.isSuccess)
        assertNull("the incoming wallet's AI store is not empty", runCatching { setAside.ai.getToken() }.getOrElse { "unreadable" })

        newProcess()
        val stuck = Phone()
        stuck.owner(WALLET_A)
        stuck.files.put(AI, Keysets.wrappedEntries() + mapOf("ai_token" to "synthetic-unreadable"))
        stuck.oldUnreadable = true
        stuck.cannotMakeNew = true
        assertTrue("the claim went on over an AI store it cannot open", runCatching { stuck.ownership.claimFor(WALLET_B) }.isFailure)
        assertNull("something was set aside", stuck.bundles.read(WALLET_A))
        assertEquals("the owner changed", WALLET_A, stuck.record.owner())
    }

    // (j) FAILS today: one lock per object (AiImageStore's `private val ioLock = Any()`).
    @Test
    fun `j - source - the mover and every file call of the picture store take one lock for the process`() {
        val history = StoreOpenerApi.stripComments(StoreOpenerApi.mainSource("ui/screen/ai/AiHistory.kt"))
        val store = history.substringAfter("class AiImageStore(", "")
        assertTrue(store.isNotEmpty())
        val instancePart = store.substringBefore("companion object")
        val locks = Regex("""synchronized\((\w+)\)""").findAll(store).map { it.groupValues[1] }.toSet()
        assertEquals("not one lock: $locks", 1, locks.size)
        val lock = locks.single()
        assertFalse(
            "the lock is one per object",
            Regex("""\bval\s+$lock\s*=""").containsMatchIn(instancePart),
        )
        assertTrue("the lock is declared nowhere", Regex("""\bval\s+$lock\s*=""").containsMatchIn(history))
        for (function in listOf("save", "loadBitmap", "delete", "clearAll", "fileFor", "moveFlatPicturesTo")) {
            val body = store.substringAfter("fun $function(", "").substringBefore("\n    fun ")
            assertTrue("AiImageStore has no $function", body.isNotEmpty())
            assertTrue("$function does not take the lock", "synchronized($lock)" in body)
        }
    }

    // (k) FAILS today: there is no AiResetKeep (ResetZashiUseCase.wipeZchatStores names three stores).
    @Test
    fun `k - keep unticked - the clear empties the AI store with a checked write and deletes this wallet's pictures`() {
        val phone = Phone()
        val pictureA = phone.walletAWithData()
        val flat = phone.putPicture(null, "flat")
        val pictureB = phone.putPicture(WALLET_B, PICTURE_B)
        val keep = Api.resetKeep(phone.ai, phone.images, phone.bundles)

        phone.files.refuseCommit += AI
        assertTrue("the clear's write is not checked", runCatching { Api.clear(keep, WALLET_A) }.isFailure)
        phone.files.refuseCommit -= AI
        Api.clear(keep, WALLET_A)

        assertEquals("the AI store is not empty", emptyMap<String, Any?>(), phone.aiEntries())
        assertFalse("this wallet's picture stayed", pictureA.exists())
        assertFalse("this wallet's picture folder stayed", pictureA.parentFile?.exists() == true)
        assertFalse("a flat picture stayed", flat.exists())
        assertTrue("another wallet's picture was deleted", pictureB.isFile)
    }

    // (m) FAILS today: there is no entriesKeptAtReset; today nothing is deleted and nothing is put aside.
    @Test
    fun `m - keep unticked - what is kept is the access key and its user id, nothing else (rule K-0)`() {
        val phone = Phone()
        phone.walletAWithData()
        phone.ai.setSelectedChatModel("synthetic-chat-model")
        phone.ai.setRetentionDays(3)
        phone.ai.setImageAspect("16:9")

        assertEquals(mapOf("ai_token" to TOKEN_A, "ai_user_id" to USER_A), Api.kept(phone.ai))

        newProcess()
        val noKey = Phone()
        noKey.owner(WALLET_A)
        noKey.ai.setConversationsJson(CHATS_A)
        noKey.ai.setImagesJson(IMAGES_A)
        noKey.ai.setSelectedChatModel("synthetic-chat-model")
        assertEquals("kept without a key", emptyMap<String, Any?>(), Api.kept(noKey.ai))
    }

    // (n) FAILS today: there is no keep step.
    @Test
    fun `n - keep unticked - the keep step writes the two entries as the only part of the reset wallet's bundle`() {
        val phone = Phone()
        phone.walletAWithData()
        val keep = Api.resetKeep(phone.ai, phone.images, phone.bundles)

        Api.keep(keep, WALLET_A)
        assertEquals(
            "the bundle is not the AI part with the two entries alone",
            mapOf(AI to mapOf("ai_token" to TOKEN_A, "ai_user_id" to USER_A)),
            phone.bundles.read(WALLET_A),
        )
        Api.clear(keep, WALLET_A)
        assertNotNull("the clear deleted the kept key", phone.bundles.read(WALLET_A))

        newProcess()
        val noKey = Phone()
        noKey.owner(WALLET_A)
        noKey.ai.setConversationsJson(CHATS_A)
        noKey.bundles.write(WALLET_A, mapOf(AI to mapOf("ai_token" to "synthetic-older")))
        val noKeyStep = Api.resetKeep(noKey.ai, noKey.images, noKey.bundles)
        Api.keep(noKeyStep, WALLET_A)
        assertEquals("a bundle was written with no key", mapOf("ai_token" to "synthetic-older"), noKey.bundles.read(WALLET_A)?.get(AI))
        Api.clear(noKeyStep, WALLET_A)
        assertNull("the older bundle stayed", noKey.bundles.read(WALLET_A))

        newProcess()
        val failing = Phone()
        failing.walletAWithData()
        val before = failing.aiEntries()
        failing.bundles.failWrites = true
        val failingStep = Api.resetKeep(failing.ai, failing.images, failing.bundles)
        assertTrue("the keep step did not throw", runCatching { Api.keep(failingStep, WALLET_A) }.isFailure)
        assertEquals("the AI store changed", before, failing.aiEntries())
    }

    // (n), the source half: written so that it holds before and after ZC-090-R.
    @Test
    fun `n - source - the keep step stands before anything is deleted, the clear after the owner says nobody`() {
        val reset = StoreOpenerApi.stripComments(StoreOpenerApi.mainSource("ui/screen/deletewallet/ResetZashiUseCase.kt"))
        val field =
            Regex("""(\w+)\s*:\s*(?:co\.electriccoin\.zcash\.ui\.screen\.ai\.)?AiResetKeep\b""").find(reset)?.groupValues?.get(1)
        assertNotNull("ResetZashiUseCase holds no AiResetKeep", field)
        val invoke = reset.substringAfter("operator fun invoke(", "")
        val keepAt = invoke.indexOf("$field.keep(")
        assertTrue("invoke() does not call $field.keep(", keepAt >= 0)
        val prompt = invoke.indexOf("requestBiometrics(")
        assertTrue("the keep step stands before the unlock prompt", prompt in 0 until keepAt)
        assertTrue("the keep step does not wait for keep unticked", "if (!keepFiles" in invoke.substring(prompt, keepAt))
        val later =
            listOf(
                "flexaRepository.disconnect(",
                "closeFlow(",
                "recordChatOwner(",
                "clearSDK(",
                "wipeZchatStores(",
                "markChatsWiped(",
            )
        for (call in later) {
            val at = invoke.indexOf(call)
            assertTrue("the keep step stands after $call", at < 0 || at > keepAt)
        }
        val clearAt = invoke.indexOf("$field.clear(")
        if (clearAt >= 0) {
            val wiped = invoke.indexOf("markChatsWiped(")
            assertTrue("the clear stands before the owner says nobody", wiped in 0 until clearAt)
        }
    }

    // (o) FAILS today: the AI store is not among the stores a claim moves.
    @Test
    fun `o - the same phrase returns after a Reset without keep and gets exactly its key back`() {
        val phone = Phone()
        phone.walletAWithData()
        phone.bundles.write(WALLET_A, mapOf(AI to Api.kept(phone.ai)))
        phone.ai.clear()
        phone.record.setOwner(NO_CHAT_OWNER)

        phone.ownership.claimFor(WALLET_B)
        assertNull("B holds A's key", phone.ai.getToken())
        assertNotNull("A's kept key is gone", phone.bundles.read(WALLET_A))

        phone.record.setOwner(NO_CHAT_OWNER)
        assertEquals(ChatClaim.RESTORED, phone.ownership.claimFor(WALLET_A))
        assertEquals("not exactly A's key and user id", mapOf("ai_token" to TOKEN_A, "ai_user_id" to USER_A), phone.aiEntries())
        assertNull("the kept file stayed", phone.bundles.read(WALLET_A))
    }

    // (p) FAILS today: the AI store is not among the stores a claim moves. After the fix: why the clear runs last.
    @Test
    fun `p - a Reset cut before the owner says nobody loses no key`() {
        val phone = Phone()
        phone.walletAWithData()
        val whole = phone.aiEntries()
        phone.bundles.write(WALLET_A, mapOf(AI to mapOf("ai_token" to TOKEN_A, "ai_user_id" to USER_A)))

        assertEquals(ChatClaim.KEPT, phone.ownership.claimFor(WALLET_A))
        assertEquals("the key moved", TOKEN_A, phone.ai.getToken())

        phone.ownership.claimFor(WALLET_B)
        val setAside =
            phone.bundles
                .read(WALLET_A)
                ?.get(AI)
                ?.filterKeys { !it.startsWith(LIBRARY_ENTRY) }
        assertEquals("A's bundle does not hold A's whole AI store", whole, setAside)
        phone.ownership.claimFor(WALLET_A)
        assertEquals(TOKEN_A, phone.ai.getToken())
        assertEquals(CHATS_A, phone.ai.getConversationsJson())
    }

    /**
     * (q1) GUARD, passes before and after the fix: A PHONE THAT NEVER USED AI. No AI file: two claims make none (today
     * no claim touches the AI store; after the fix the wallet store's rule "no file and nothing to bring in: nothing is
     * made" keeps it so, where the plain adapter would make the file and its Keystore key at every change of wallet).
     */
    @Test
    fun `q1 - a phone that never used AI gets no AI file from a change of wallet`() {
        val phone = Phone()
        phone.owner(WALLET_A)

        phone.ownership.claimFor(WALLET_B)
        phone.ownership.claimFor(WALLET_A)

        assertFalse("a claim made the AI file", phone.files.exists(AI))
        assertFalse("a claim asked for the AI key", phone.calls.asked.any { it.contains(AI) })
    }

    /**
     * (q2) FAILS today (there is no entriesKeptAtReset, and no claim brings an AI part in). The rest of spec case
     * "a phone that never used AI": asking what a Reset keeps makes no file; a claim that brings a kept pair in makes it.
     */
    @Test
    fun `q2 - asking what is kept makes no AI file, a kept pair brought in makes it`() {
        val phone = Phone()
        phone.owner(NO_CHAT_OWNER)

        assertEquals(emptyMap<String, Any?>(), Api.kept(phone.ai))
        assertFalse("asking what is kept made the AI file", phone.files.exists(AI))

        phone.bundles.write(WALLET_A, mapOf(AI to mapOf("ai_token" to TOKEN_A, "ai_user_id" to USER_A)))
        phone.ownership.claimFor(WALLET_A)
        assertEquals(mapOf("ai_token" to TOKEN_A, "ai_user_id" to USER_A), phone.aiEntries())
    }

    // (r) FAILS today: the wipe of ZC-733-B names every kept copy of the wiped wallet.
    @Test
    fun `r - a kept copy of the AI store survives the wipe of its wallet, with its owner mark`() {
        val phone = Phone()
        phone.owner(WALLET_A)
        val aiCopy = "$AI${UNREADABLE_STORE_COPY_MARKER}100"
        val bookCopy = "zchat_contact_book_enc${UNREADABLE_STORE_COPY_MARKER}101"
        phone.files.put(aiCopy, Keysets.wrappedEntries())
        phone.files.put(bookCopy, Keysets.wrappedEntries())
        val marks = mapOf("copy_owner:$aiCopy" to WALLET_A, "copy_owner:$bookCopy" to WALLET_A)
        phone.files.put(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME, marks)
        val listed =
            phone.files
                .listing()
                .map { it.removeSuffix(".xml") }
                .filter { UNREADABLE_STORE_COPY_MARKER in it }

        val named = StoreOpenerApi.copiesToWipe(phone.copies, listed)
        named.forEach { phone.files.remove(it) }
        StoreOpenerApi.cleanMarksForWipe(phone.copies)

        assertEquals("not only the chat store's copy is named", listOf(bookCopy), named)
        assertEquals(
            "the AI copy's owner mark went",
            WALLET_A,
            phone.files.entries(ENCRYPTED_STORE_OPEN_FAILURES_PREFS_NAME)["copy_owner:$aiCopy"],
        )
    }

    // (l) FAILS today: LiveChatStoresImpl.stores() has no AI store, AiPreferences.clear() applies.
    @Test
    fun `l - one object - every AiPreferences shares one store, the claim's stores include it, clear commits`() {
        val phone = Phone()
        assertTrue(
            "two AiPreferences do not share one store object",
            StoreOpenerApi.holderOf(AiPreferences(phone.context)) === StoreOpenerApi.holderOf(phone.ai),
        )
        val stores = StoreOpenerApi.stripComments(StoreOpenerApi.mainSource("ui/screen/chat/datasource/ChatWalletOwnershipStores.kt"))
        val live = stores.substringAfter("class LiveChatStoresImpl(", "").substringBefore("\n}")
        val graph = StoreOpenerApi.stripComments(StoreOpenerApi.mainSource("di/DataSourceModule.kt"))
        val built = graph.substringAfter("LiveChatStoresImpl(", "").substringBefore("untaggedMarks")
        assertTrue("the dependency graph hands no AI store to the claim's stores", "AiPreferences" in built)
        assertTrue(
            "LiveChatStoresImpl.stores() adds no further stores",
            live.substringAfter("override fun stores()").substringBefore("override fun").count { it == '+' } >= 5,
        )
        val preferences = StoreOpenerApi.stripComments(StoreOpenerApi.mainSource("ui/screen/ai/AiPreferences.kt"))
        val clear = preferences.substringAfter("fun clear()", "").substringBefore("\n    }")
        assertTrue("AiPreferences.clear() does not commit", ".commit()" in clear && ".apply()" !in clear)
    }

    // AI-KEY-S (W-53, spec 6.3). A GUARD, it passes today: a change of wallet moves the whole store, so the two new
    // entries follow their wallet; it must stay so.
    @Test
    fun `s1 - ai_link_state and ai_parked_keys follow their wallet with the rest of the store`() {
        val phone = Phone()
        phone.walletAWithData()
        val aiStore = phone.ai.walletScopedStores().single()
        aiStore.importEntries(phone.aiEntries() + (LINK_STATE to LINK_STATE_A) + (PARKED to parkedList(TOKEN_PARKED)))

        phone.ownership.claimFor(WALLET_B)
        assertNull("B sees A's verdict", phone.aiEntries()[LINK_STATE])
        assertNull("B sees A's parked keys", phone.aiEntries()[PARKED])

        phone.ownership.claimFor(WALLET_A)
        assertEquals(LINK_STATE_A, phone.aiEntries()[LINK_STATE])
        assertEquals(parkedList(TOKEN_PARKED), phone.aiEntries()[PARKED])
    }

    // AI-KEY-S, not in the spec: ZC-1031 keeps only ACCESS_KEY_ENTRIES when nobody's stores are claimed. FAILS today: the
    // parked list, the only way to a paid account the phrase cannot open, is lost. The verdict must not come along: it
    // was another wallet's answer, and a restored key has no verdict (spec 5.3), so 4.4 runs before anything is charged.
    @Test
    fun `s2 - a claim of nobody's stores keeps the parked keys beside the access key, and no verdict`() {
        val phone = Phone()
        phone.owner(NO_CHAT_OWNER)
        phone.ai.saveCredentials(TOKEN_A, USER_A)
        val aiStore = phone.ai.walletScopedStores().single()
        aiStore.importEntries(phone.aiEntries() + (LINK_STATE to LINK_STATE_A) + (PARKED to parkedList(TOKEN_PARKED)))

        phone.ownership.claimFor(WALLET_A)

        assertEquals(TOKEN_A, phone.ai.getToken())
        assertEquals("the parked key was lost", parkedList(TOKEN_PARKED), phone.aiEntries()[PARKED])
        assertNull("a verdict of an earlier wallet came along", phone.aiEntries()[LINK_STATE])
    }

    // AI-KEY-S: the same when no access key is saved: the parked list alone is reason enough. FAILS today.
    @Test
    fun `s3 - a claim of nobody's stores keeps the parked keys when no access key is saved`() {
        val phone = Phone()
        phone.owner(NO_CHAT_OWNER)
        phone.ai.setRetentionDays(7)
        val aiStore = phone.ai.walletScopedStores().single()
        aiStore.importEntries(phone.aiEntries() + (PARKED to parkedList(TOKEN_PARKED)))

        phone.ownership.claimFor(WALLET_A)

        assertEquals("the parked key was lost", parkedList(TOKEN_PARKED), phone.aiEntries()[PARKED])
    }

    // AI-KEY-S: the wallet's own saved key wins (ZC-1031), and neither list loses an entry. FAILS today.
    @Test
    fun `s4 - a claim of nobody's stores joins the parked keys with those the wallet saved`() {
        val phone = Phone()
        phone.owner(NO_CHAT_OWNER)
        phone.ai.saveCredentials(TOKEN_B, USER_B)
        val aiStore = phone.ai.walletScopedStores().single()
        aiStore.importEntries(phone.aiEntries() + (PARKED to parkedList(TOKEN_PARKED)))
        phone.bundles.write(
            WALLET_A,
            mapOf(AI to mapOf("ai_token" to TOKEN_A, "ai_user_id" to USER_A, PARKED to parkedList(TOKEN_PARKED_OWN))),
        )

        phone.ownership.claimFor(WALLET_A)

        assertEquals(TOKEN_A, phone.ai.getToken())
        val parked = phone.aiEntries()[PARKED].toString()
        assertTrue("the live parked key was lost", TOKEN_PARKED in parked)
        assertTrue("the wallet's saved parked key was lost", TOKEN_PARKED_OWN in parked)
    }

    /** The new names of ZC-718, reached by name so that this class compiles before the fix. */
    private object Api {
        fun liveStores(
            preferences: ZchatPreferences,
            book: ContactBookImpl,
            avatars: AvatarStore,
            addresses: AddressCacheImpl,
            identities: IdentityManagerImpl,
            ai: AiPreferences,
        ): LiveChatStores {
            val constructor =
                LiveChatStoresImpl::class.java.constructors.firstOrNull { it.parameterCount == 6 }
                    // RED: no further stores yet; the claim still runs, without the AI store, and the cases fail on what
                    // they assert (the two guards pass).
                    ?: return LiveChatStoresImpl({ preferences }, { book }, { avatars }, { addresses }, { identities })
            val further: () -> List<*> = { walletScopedStores(ai) }
            return call {
                constructor.newInstance(
                    { preferences },
                    { book },
                    { avatars },
                    { addresses },
                    { identities },
                    further,
                ) as LiveChatStores
            }
        }

        fun walletScopedStores(ai: AiPreferences): List<*> = call(ai, "walletScopedStores") as List<*>

        /** The kept-copies tagger with the picture store's side step (the composition the graph uses). */
        fun tagger(
            images: AiImageStore,
            copies: KeptChatStoreCopies,
        ): UntaggedChatMarks {
            val method =
                images.javaClass.methods.firstOrNull { it.name == "movingFlatPictures" && it.parameterCount == 1 }
                    ?: return copies // RED: no side step yet; the claim still runs, and the cases fail on what they assert.
            return call { method.invoke(images, copies) as UntaggedChatMarks }
        }

        fun fileFor(
            images: AiImageStore,
            id: String,
        ): File? = call(images, "fileFor", id) as File?

        @Suppress("UNCHECKED_CAST")
        fun kept(ai: AiPreferences): Map<String, Any?> = call(ai, "entriesKeptAtReset") as Map<String, Any?>

        fun resetKeep(
            ai: AiPreferences,
            images: AiImageStore,
            bundles: SetAsideBundles,
        ): Any {
            val type =
                runCatching { Class.forName("co.electriccoin.zcash.ui.screen.ai.AiResetKeep") }.getOrNull()
                    ?: StoreOpenerApi.missing("ZC-718: there is no AiResetKeep")
            val constructor =
                type.constructors.firstOrNull { it.parameterCount == 3 }
                    ?: StoreOpenerApi.missing("ZC-718: AiResetKeep is not built from the AI store, the pictures and the bundles")
            return call { constructor.newInstance(ai, images, bundles) }
        }

        fun keep(
            resetKeep: Any,
            tag: String,
        ) {
            call(resetKeep, "keep", tag)
        }

        fun clear(
            resetKeep: Any,
            tag: String,
        ) {
            call(resetKeep, "clear", tag)
        }

        private fun call(
            target: Any,
            name: String,
            vararg args: Any,
        ): Any? {
            val method =
                target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                    ?: StoreOpenerApi.missing("ZC-718: ${target.javaClass.simpleName} has no $name")
            return call { method.invoke(target, *args) }
        }

        private fun <T> call(block: () -> T): T =
            try {
                block()
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }

    private companion object {
        const val AI = "zchat_ai_prefs"
        const val LINK_STATE = "ai_link_state"
        const val PARKED = "ai_parked_keys"
        const val LINK_STATE_A = """{"accountId":"synthetic-user-a","verdict":"linked","paid":"no"}"""
        const val TOKEN_PARKED = "synthetic-token-parked"
        const val TOKEN_PARKED_OWN = "synthetic-saved-by-the-wallet"

        fun parkedList(token: String): String = """[{"token":"$token","userId":"synthetic-user-parked"}]"""

        const val LIBRARY_ENTRY = "__androidx_security_crypto_encrypted_prefs_"
        val WALLET_A = "a".repeat(64)
        val WALLET_B = "b".repeat(64)
        const val TOKEN_A = "synthetic-token-a"
        const val TOKEN_B = "synthetic-token-b"
        const val USER_A = "synthetic-user-a"
        const val USER_B = "synthetic-user-b"
        const val CHATS_A = """[{"id":"synthetic-chat-a"}]"""
        const val CHATS_B = """[{"id":"synthetic-chat-b"}]"""
        const val IMAGES_A = """[{"id":"synthetic-picture-a"}]"""
        const val PICTURE_A = "synthetic-picture-a"
        const val PICTURE_B = "synthetic-picture-b"
        val SEVEN_KEYS_OF_2124 =
            setOf(
                "ai_token",
                "ai_user_id",
                "ai_selected_model",
                "ai_selected_image_model",
                "ai_conversations",
                "ai_images",
                "ai_retention_days",
            )

        fun File?.names(): List<String> = this?.listFiles()?.map { it.name }.orEmpty()
    }
}
