package co.electriccoin.zcash.ui.screen.chat.datasource

import android.content.SharedPreferences
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1104 (owner design 26 Oct 2026, W-79): the chat list's "Groups" row is collapsed by default and the user's
 * expanded/collapsed choice is remembered: it survives leaving the screen and an app restart.
 *
 * What it must be: `ChatListGroupsFolderStore(prefs: SharedPreferences)` with `isExpanded(): Boolean` (false when
 * nothing was stored) and `setExpanded(expanded: Boolean)`, written to the preferences (commit or apply), so a new
 * store over the same preferences (a new screen, view model or process) reads the last choice.
 *
 * Read by reflection so this RED test compiles before the store exists.
 */
class GroupsFolderExpandedSurvivesTest {
    private fun newStore(prefs: SharedPreferences): Any =
        Class
            .forName("co.electriccoin.zcash.ui.screen.chat.datasource.ChatListGroupsFolderStore")
            .getConstructor(SharedPreferences::class.java)
            .newInstance(prefs)

    private fun isExpanded(store: Any): Boolean = store.javaClass.getMethod("isExpanded").invoke(store) as Boolean

    private fun setExpanded(
        store: Any,
        expanded: Boolean
    ) {
        store.javaClass.getMethod("setExpanded", Boolean::class.javaPrimitiveType).invoke(store, expanded)
    }

    @Test
    fun the_groups_row_is_collapsed_by_default() {
        assertFalse(isExpanded(newStore(MapPrefs())), "nothing stored: the Groups row starts collapsed")
    }

    @Test
    fun the_choice_survives_a_new_store_over_the_same_preferences() {
        val disk = MapPrefs()
        setExpanded(newStore(disk), true)
        assertTrue(isExpanded(newStore(disk)), "expanded, then the screen or app restarts: still expanded")
        setExpanded(newStore(disk), false)
        assertFalse(isExpanded(newStore(disk)), "folded again, then a restart: still folded")
    }

    /** Map-backed preferences; an edit reaches the map only on commit() or apply(), as on Android. */
    private class MapPrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()

        override fun getString(
            key: String,
            defValue: String?
        ): String? = if (map.containsKey(key)) map[key] as String? else defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = if (map.containsKey(key)) map[key] as MutableSet<String>? else defValues

        override fun getInt(
            key: String,
            defValue: Int
        ): Int = map[key] as? Int ?: defValue

        override fun getLong(
            key: String,
            defValue: Long
        ): Long = map[key] as? Long ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float
        ): Float = map[key] as? Float ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean
        ): Boolean = map[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false

            private fun put(
                key: String,
                value: Any?
            ) = apply {
                removed.remove(key)
                staged[key] = value
            }

            override fun putString(
                key: String,
                value: String?
            ) = put(key, value)

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ) = put(key, values)

            override fun putInt(
                key: String,
                value: Int
            ) = put(key, value)

            override fun putLong(
                key: String,
                value: Long
            ) = put(key, value)

            override fun putFloat(
                key: String,
                value: Float
            ) = put(key, value)

            override fun putBoolean(
                key: String,
                value: Boolean
            ) = put(key, value)

            override fun remove(key: String) =
                apply {
                    staged.remove(key)
                    removed.add(key)
                }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                if (clear) map.clear()
                removed.forEach { map.remove(it) }
                map.putAll(staged)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
