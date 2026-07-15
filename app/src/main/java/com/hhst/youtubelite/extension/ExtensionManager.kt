package com.hhst.youtubelite.extension

/** MMKV-backed toggle store under the `preferences:` prefix. */
class ExtensionManager(private val store: PrefStore) {

    init {
        seedDefaults()
    }

    fun isEnabled(key: String): Boolean {
        val pref = prefKey(key)
        val default = PreferenceKeys.DEFAULTS[key] == true
        return store.getBool(pref, default)
    }

    fun setEnabled(key: String, enabled: Boolean) {
        val pref = prefKey(key)
        val default = PreferenceKeys.DEFAULTS[key] == true
        val previous = if (store.contains(pref)) store.getBool(pref, default) else default
        store.putBool(pref, enabled)
        if (previous != enabled) bumpVersion()
    }

    fun resetToDefault() {
        var changed = false
        for ((key, value) in PreferenceKeys.DEFAULTS) {
            val pref = prefKey(key)
            if (!store.contains(pref) || store.getBool(pref, value) != value) {
                changed = true
            }
            store.putBool(pref, value)
        }
        if (changed) bumpVersion()
    }

    fun allPreferences(): Map<String, Boolean> =
        PreferenceKeys.DEFAULTS.keys.associateWith { isEnabled(it) }

    fun version(): Long = store.getLong(KEY_VERSION, 0L)

    private fun seedDefaults() {
        migrateGestures()
        for ((key, value) in PreferenceKeys.DEFAULTS) {
            val pref = prefKey(key)
            if (!store.contains(pref)) {
                store.putBool(pref, value)
            }
        }
    }

    private fun migrateGestures() {
        if (PreferenceKeys.GESTURE_KEYS.any { store.contains(prefKey(it)) }) return
        val legacy = prefKey(PreferenceKeys.ENABLE_PLAYER_GESTURES)
        val enabled = if (store.contains(legacy)) {
            store.getBool(legacy, true)
        } else {
            true
        }
        for (gesture in PreferenceKeys.GESTURE_KEYS) {
            store.putBool(prefKey(gesture), enabled)
        }
    }

    private fun bumpVersion() {
        store.putLong(KEY_VERSION, version() + 1L)
    }

    private fun prefKey(key: String): String = "$PREFIX$key"

    private companion object {
        const val PREFIX = "preferences:"
        const val KEY_VERSION = "preferences:version"
    }
}
