package co.electriccoin.zcash.ui.screen.more

import android.content.SharedPreferences
import co.electriccoin.zcash.ui.design.component.listitem.ListItemState
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1103 (high; owner freeze exception W-79): the app ships English and Spanish (app/src/main/res/xml/locales_config.xml,
 * android:localeConfig in the app manifest), but its own Settings (More) has no Language choice. The owner, on HONOR
 * with the phone in Spanish, could not switch the app to English.
 *
 * Wanted:
 *  1. More lists a "Language: <current choice>" row (string `more_row_language`), built by
 *     `co.electriccoin.zcash.ui.screen.more.languageRow(current: AppLanguage, onClick: () -> Unit): ListItemState`
 *     (file MoreLanguageRow.kt), and MoreVM takes an `AppLanguageDataSource` and uses that row.
 *  2. The picker offers `enum class co.electriccoin.zcash.ui.screen.settings.model.AppLanguage(val tag: String)`:
 *     SYSTEM_DEFAULT("") first, then one constant per locale of locales_config.xml in file order (ENGLISH("en"),
 *     SPANISH("es")), so a language added to locales_config.xml without a picker entry turns this test red.
 *     Each has `@StringRes fun displayNameRes(): Int`; language names are shown in their own language.
 *  3. `co.electriccoin.zcash.ui.screen.settings.datasource.AppLanguageDataSourceImpl(prefs: SharedPreferences,
 *     applier: AppLocaleApplier)` implements `AppLanguageDataSource { val appLanguage: StateFlow<AppLanguage>;
 *     fun setLanguage(language: AppLanguage) }`. setLanguage applies at once through
 *     `fun interface AppLocaleApplier { fun apply(tag: String) }` ("" clears the override, i.e. System default)
 *     and stores the choice, so a new process (a new instance over the same prefs) reads it back.
 *
 * The real AppLocaleApplier (LocaleManager on API 33+, an own override applied in attachBaseContext below that, or
 * AppCompat) is the implementer's choice and is proved on the phones; this test pins the seam.
 *
 * The new API is reached by name, so this file compiles before it exists.
 */
class AppLanguageSettingTest {
    // ---- repo files ----

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    /** The locales the app declares to Android, in file order. */
    private val declaredLocales: List<String> by lazy {
        val xml = File(repoRoot, "app/src/main/res/xml/locales_config.xml").readText()
        Regex("""<locale\s+android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
    }

    /** name -> value of every <string> in ui-lib/src/main/res/ui/<screen>/<valuesDir>/. */
    private fun strings(valuesDir: String): Map<String, String> {
        val out = HashMap<String, String>()
        File(repoRoot, "ui-lib/src/main/res/ui").listFiles().orEmpty().forEach { screen ->
            File(screen, valuesDir).listFiles { f -> f.name.endsWith(".xml") }.orEmpty().forEach { file ->
                Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(file.readText())
                    .forEach { out[it.groupValues[1]] = it.groupValues[2] }
            }
        }
        return out
    }

    private val english by lazy { strings("values") }
    private val spanish by lazy { strings("values-es") }

    /** What a phone in that locale shows: the locale's own value, else the base one. */
    private fun shown(
        name: String,
        locale: String,
    ): String? = (if (locale == "es") spanish[name] else null) ?: english[name]

    private fun stringId(name: String): Int =
        runCatching { Class.forName("co.electriccoin.zcash.ui.R\$string").getField(name).getInt(null) }.getOrElse {
            throw AssertionError("no string resource R.string.$name")
        }

    // ---- the seam, by name ----

    private val languageClass: Class<*> by lazy {
        runCatching { Class.forName("co.electriccoin.zcash.ui.screen.settings.model.AppLanguage") }.getOrElse {
            throw AssertionError("no co.electriccoin.zcash.ui.screen.settings.model.AppLanguage: the app has no Language choice")
        }
    }

    private val languages: List<Any> by lazy {
        languageClass.enumConstants?.toList()
            ?: throw AssertionError("AppLanguage is not an enum (the picker's options are its constants, in order)")
    }

    private fun language(name: String): Any =
        languages.firstOrNull { (it as Enum<*>).name == name }
            ?: throw AssertionError("AppLanguage has no $name (found $languages)")

    private fun tagOf(language: Any): String = languageClass.getMethod("getTag").invoke(language) as String

    private fun displayNameRes(language: Any): Int = languageClass.getMethod("displayNameRes").invoke(language) as Int

    private fun languageRow(
        current: Any,
        onClick: () -> Unit,
    ): ListItemState {
        val cls =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.more.MoreLanguageRowKt") }.getOrElse {
                throw AssertionError("no MoreLanguageRow.kt with languageRow(current, onClick): More has no Language row")
            }
        val method =
            cls.methods.firstOrNull { it.name == "languageRow" && it.parameterCount == 2 && Modifier.isStatic(it.modifiers) }
                ?: throw AssertionError("MoreLanguageRow.kt has no languageRow(current: AppLanguage, onClick: () -> Unit)")
        return method.invoke(null, current, onClick) as ListItemState
    }

    private val applierClass: Class<*> by lazy {
        runCatching { Class.forName("co.electriccoin.zcash.ui.screen.settings.datasource.AppLocaleApplier") }.getOrElse {
            throw AssertionError("no AppLocaleApplier: nothing applies a chosen language to the app")
        }
    }

    /** Records every tag handed to the applier. */
    private fun recordingApplier(applied: MutableList<String>): Any =
        Proxy.newProxyInstance(applierClass.classLoader, arrayOf(applierClass)) { proxy, method, args ->
            when (method.name) {
                "apply" -> {
                    applied += args!![0] as String
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "RecordingApplier"
                else -> null
            }
        }

    /** A new AppLanguageDataSourceImpl, as a new process builds it over the same stored prefs. */
    private fun newDataSource(
        prefs: SharedPreferences,
        applied: MutableList<String>,
    ): Any {
        val cls =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.settings.datasource.AppLanguageDataSourceImpl") }
                .getOrElse { throw AssertionError("no AppLanguageDataSourceImpl: the language choice is not stored") }
        val ctor =
            cls.constructors.firstOrNull { c ->
                c.parameterTypes.size == 2 &&
                    c.parameterTypes[0] == SharedPreferences::class.java &&
                    c.parameterTypes[1] == applierClass
            } ?: throw AssertionError("AppLanguageDataSourceImpl has no constructor (SharedPreferences, AppLocaleApplier)")
        return ctor.newInstance(prefs, recordingApplier(applied))
    }

    private fun currentChoice(dataSource: Any): Any? =
        (dataSource.javaClass.getMethod("getAppLanguage").invoke(dataSource) as StateFlow<*>).value

    private fun choose(
        dataSource: Any,
        language: Any,
    ) {
        dataSource.javaClass.methods
            .first { it.name == "setLanguage" && it.parameterCount == 1 }
            .invoke(dataSource, language)
    }

    // ---- (1) the Settings row ----

    @Test
    fun more_has_a_language_row_showing_the_current_choice() {
        for ((constant, nameKey) in listOf(
            "SPANISH" to "language_name_es",
            "ENGLISH" to "language_name_en",
            "SYSTEM_DEFAULT" to "language_name_system_default",
        )) {
            var clicked = 0
            val row = languageRow(language(constant)) { clicked++ }
            assertEquals(
                stringRes(stringId("more_row_language"), stringRes(stringId(nameKey))),
                row.title,
                "the Language row must read \"Language: <current choice>\" for $constant",
            )
            assertNotNull(row.onClick, "the Language row must open the picker").invoke()
            assertEquals(1, clicked, "tapping the Language row must call its onClick (open the picker)")
        }

        val moreVm = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/more/MoreVM.kt").readText()
        assertTrue("languageRow(" in moreVm, "MoreVM's items must include languageRow(...)")
        assertTrue(
            MoreVM::class.java.constructors.any { c ->
                c.parameterTypes.any { it.name == "co.electriccoin.zcash.ui.screen.settings.datasource.AppLanguageDataSource" }
            },
            "MoreVM must take an AppLanguageDataSource (the row's current choice and the picker's action)",
        )
    }

    @Test
    fun the_language_texts_exist_in_english_and_spanish() {
        assertEquals("Language: %1\$s", english["more_row_language"], "English row label")
        assertEquals("Idioma: %1\$s", spanish["more_row_language"], "Spanish row label")
        for (key in listOf("language_dialog_title", "language_name_system_default")) {
            val en = assertNotNull(english[key], "missing English $key")
            val es = assertNotNull(spanish[key], "missing Spanish $key")
            assertNotEquals(en, es, "$key is still English on a Spanish phone")
        }
        assertEquals("System default", english["language_name_system_default"])
        // A language's name is shown in that language, on every phone.
        for (locale in listOf("en", "es")) {
            assertEquals("English", shown("language_name_en", locale), "English is named in English on a $locale phone")
            assertEquals("Español", shown("language_name_es", locale), "Spanish is named in Spanish on a $locale phone")
        }
    }

    // ---- (2) choosing applies at once, through the seam ----

    @Test
    fun choosing_a_language_applies_it_at_once() {
        val prefs = MapPrefs()
        val applied = mutableListOf<String>()
        val dataSource = newDataSource(prefs, applied)

        choose(dataSource, language("SPANISH"))
        assertEquals("es", applied.lastOrNull(), "Español must set the app locale to \"es\" at once")
        assertEquals(language("SPANISH"), currentChoice(dataSource), "the row must show Español at once")

        choose(dataSource, language("ENGLISH"))
        assertEquals("en", applied.lastOrNull(), "English must set the app locale to \"en\" at once")
        assertEquals(language("ENGLISH"), currentChoice(dataSource))

        choose(dataSource, language("SYSTEM_DEFAULT"))
        assertEquals("", applied.lastOrNull(), "System default must clear the app locale override (empty)")
        assertEquals(language("SYSTEM_DEFAULT"), currentChoice(dataSource))
    }

    // ---- (3) the picker offers exactly the declared locales plus System default ----

    @Test
    fun the_picker_offers_system_default_then_every_declared_locale() {
        assertTrue(declaredLocales.isNotEmpty(), "locales_config.xml declares no locale")
        assertEquals("SYSTEM_DEFAULT", (languages.first() as Enum<*>).name, "System default is the first option")
        assertEquals("", tagOf(languages.first()), "System default carries the empty tag (no override)")
        assertEquals(
            declaredLocales,
            languages.drop(1).map { tagOf(it) },
            "the picker must offer exactly the locales of locales_config.xml, in its order, after System default",
        )
        val names = languages.map { displayNameRes(it) }
        assertEquals(names.size, names.toSet().size, "each option needs its own display name")
        assertEquals(stringId("language_name_system_default"), displayNameRes(language("SYSTEM_DEFAULT")))
        declaredLocales.forEach { tag ->
            val option = languages.first { tagOf(it) == tag }
            assertEquals(stringId("language_name_$tag"), displayNameRes(option), "option $tag shows language_name_$tag")
        }
    }

    // ---- (4) the choice survives a new process ----

    @Test
    fun the_choice_is_read_back_after_a_new_process() {
        val prefs = MapPrefs()
        val applied = mutableListOf<String>()

        assertEquals(
            language("SYSTEM_DEFAULT"),
            currentChoice(newDataSource(prefs, applied)),
            "nothing chosen yet means System default",
        )

        choose(newDataSource(prefs, applied), language("SPANISH"))
        assertEquals(language("SPANISH"), currentChoice(newDataSource(prefs, applied)), "Español must be kept across a restart")

        choose(newDataSource(prefs, applied), language("ENGLISH"))
        assertEquals(language("ENGLISH"), currentChoice(newDataSource(prefs, applied)), "English must be kept across a restart")

        choose(newDataSource(prefs, applied), language("SYSTEM_DEFAULT"))
        assertEquals(
            language("SYSTEM_DEFAULT"),
            currentChoice(newDataSource(prefs, applied)),
            "System default must clear the stored override",
        )
    }

    /** SharedPreferences over a map that outlives each data-source instance, as the file on disk does. */
    private class MapPrefs : SharedPreferences {
        val memory = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(memory)

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = memory[key] as String? ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = memory[key] as MutableSet<String>? ?: defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = memory[key] as Int? ?: defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = memory[key] as Long? ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = memory[key] as Float? ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = memory[key] as Boolean? ?: defValue

        override fun contains(key: String): Boolean = memory.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clear = false

            override fun putString(
                key: String,
                value: String?,
            ) = apply { changes[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = apply { changes[key] = values?.let { HashSet(it) } }

            override fun putInt(
                key: String,
                value: Int,
            ) = apply { changes[key] = value }

            override fun putLong(
                key: String,
                value: Long,
            ) = apply { changes[key] = value }

            override fun putFloat(
                key: String,
                value: Float,
            ) = apply { changes[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply { changes[key] = value }

            override fun remove(key: String) = apply { removals += key }

            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                if (clear) memory.clear()
                removals.forEach { memory.remove(it) }
                changes.forEach { (k, v) -> if (v == null) memory.remove(k) else memory[k] = v }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
