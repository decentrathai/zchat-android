package co.electriccoin.zcash.ui.screen.changeidentity

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Represents a single identity (mask) that a user can use for messaging.
 * Each identity has its own address and separate conversation data.
 */
@Serializable
data class Identity(
    /** Unique identifier for this identity */
    val id: String,
    /** Human-readable name for this identity (e.g., "Main", "Business", "Personal") */
    val name: String,
    /** The unified address for this identity */
    val address: String,
    /** When this identity was created */
    val createdAt: Long,
    /** Whether this is the default/original identity */
    val isDefault: Boolean = false
) {
    companion object {
        fun generateId(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return (1..16).map { chars.random() }.joinToString("")
        }
    }
}

/**
 * Interface for managing user identities (masks).
 * Allows users to switch between different messaging identities while keeping the same wallet.
 */
interface IdentityManager {
    /** Get all identities for the current wallet */
    fun getAllIdentities(): List<Identity>

    /** Get the currently active identity */
    fun getActiveIdentity(): Identity?

    /** Get identity by ID */
    fun getIdentity(id: String): Identity?

    /** Set the active identity */
    fun setActiveIdentity(id: String): Boolean

    /** Add a new identity */
    fun addIdentity(identity: Identity): Boolean

    /** Remove an identity (cannot remove default or active identity) */
    fun removeIdentity(id: String): Boolean

    /** Update identity name */
    fun updateIdentityName(id: String, newName: String): Boolean

    /** Get the active identity ID (for preference namespacing) */
    fun getActiveIdentityId(): String?

    /** Flow of active identity changes */
    val activeIdentityFlow: Flow<Identity?>

    /** Clear all identities (for wallet reset) */
    fun clearAll()
}

/**
 * SharedPreferences-based implementation of IdentityManager.
 */
class IdentityManagerImpl(
    context: Context
) : IdentityManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "zchat_identity_manager",
        Context.MODE_PRIVATE
    )

    private val _activeIdentityFlow = MutableStateFlow<Identity?>(null)
    override val activeIdentityFlow: Flow<Identity?> = _activeIdentityFlow.asStateFlow()

    companion object {
        private const val KEY_IDENTITIES = "identities"
        private const val KEY_ACTIVE_ID = "active_identity_id"
    }

    init {
        // Initialize active identity flow
        _activeIdentityFlow.value = getActiveIdentity()
    }

    override fun getAllIdentities(): List<Identity> {
        val jsonString = prefs.getString(KEY_IDENTITIES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Identity>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun getActiveIdentity(): Identity? {
        val activeId = prefs.getString(KEY_ACTIVE_ID, null) ?: return getAllIdentities().firstOrNull { it.isDefault }
        return getIdentity(activeId)
    }

    override fun getIdentity(id: String): Identity? {
        return getAllIdentities().find { it.id == id }
    }

    override fun setActiveIdentity(id: String): Boolean {
        val identity = getIdentity(id) ?: return false
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        _activeIdentityFlow.value = identity
        return true
    }

    override fun addIdentity(identity: Identity): Boolean {
        val identities = getAllIdentities().toMutableList()

        // Check for duplicate ID
        if (identities.any { it.id == identity.id }) {
            return false
        }

        identities.add(identity)
        saveIdentities(identities)

        // If this is the first identity, make it active
        if (identities.size == 1) {
            setActiveIdentity(identity.id)
        }

        return true
    }

    override fun removeIdentity(id: String): Boolean {
        val identities = getAllIdentities().toMutableList()
        val identity = identities.find { it.id == id } ?: return false

        // Cannot remove default identity
        if (identity.isDefault) {
            return false
        }

        // Cannot remove active identity
        if (getActiveIdentityId() == id) {
            return false
        }

        identities.removeIf { it.id == id }
        saveIdentities(identities)
        return true
    }

    override fun updateIdentityName(id: String, newName: String): Boolean {
        val identities = getAllIdentities().toMutableList()
        val index = identities.indexOfFirst { it.id == id }
        if (index == -1) return false

        identities[index] = identities[index].copy(name = newName)
        saveIdentities(identities)

        // Update flow if this is the active identity
        if (getActiveIdentityId() == id) {
            _activeIdentityFlow.value = identities[index]
        }

        return true
    }

    override fun getActiveIdentityId(): String? {
        return prefs.getString(KEY_ACTIVE_ID, null) ?: getAllIdentities().firstOrNull { it.isDefault }?.id
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
        _activeIdentityFlow.value = null
    }

    private fun saveIdentities(identities: List<Identity>) {
        val jsonString = json.encodeToString(identities)
        prefs.edit().putString(KEY_IDENTITIES, jsonString).apply()
    }

    /**
     * Initialize the default identity for a wallet.
     * Should be called when the wallet is first created or restored.
     */
    fun initializeDefaultIdentity(address: String, name: String = "Default"): Identity {
        val existingDefault = getAllIdentities().find { it.isDefault }
        if (existingDefault != null) {
            return existingDefault
        }

        val defaultIdentity = Identity(
            id = Identity.generateId(),
            name = name,
            address = address,
            createdAt = System.currentTimeMillis(),
            isDefault = true
        )

        addIdentity(defaultIdentity)
        setActiveIdentity(defaultIdentity.id)
        return defaultIdentity
    }

    /**
     * Create a new diversified identity from a new address.
     */
    fun createDiversifiedIdentity(address: String, name: String): Identity {
        val identityCount = getAllIdentities().size
        val identity = Identity(
            id = Identity.generateId(),
            name = name.ifEmpty { "Identity ${identityCount + 1}" },
            address = address,
            createdAt = System.currentTimeMillis(),
            isDefault = false
        )

        addIdentity(identity)
        return identity
    }
}
