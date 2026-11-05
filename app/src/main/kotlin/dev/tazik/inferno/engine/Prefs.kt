package dev.tazik.inferno.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * То, что в Swift было `UserDefaults` и `@AppStorage`: постоянные настройки
 * приложения.
 *
 * Плисты-массивы и словари здесь кодируются JSON в один ключ `json:<имя>` —
 * это ровно то, что делал `JSONEncoder().encode` поверх `Data` в UserDefaults.
 */
object Prefs {

    private val prefs: SharedPreferences?
        get() = EngineEnv.appContext?.getSharedPreferences("inferno", Context.MODE_PRIVATE)

    fun string(key: String, fallback: String = ""): String =
        prefs?.getString(key, fallback) ?: fallback

    fun setString(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun flag(key: String, fallback: Boolean = false): Boolean =
        prefs?.getBoolean(key, fallback) ?: fallback

    fun setFlag(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    fun number(key: String, fallback: Long = 0): Long =
        prefs?.getLong(key, fallback) ?: fallback

    fun setNumber(key: String, value: Long) {
        prefs?.edit()?.putLong(key, value)?.apply()
    }

    fun real(key: String, fallback: Double = 0.0): Double =
        prefs?.getFloat(key, fallback.toFloat())?.toDouble() ?: fallback

    fun setReal(key: String, value: Double) {
        prefs?.edit()?.putFloat(key, value.toFloat())?.apply()
    }

    /** Список словарей — JSON-массив в одном ключе. */
    fun listOfDicts(key: String): List<Map<String, Any?>> {
        val raw = prefs?.getString("json:$key", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.optJSONObject(index) ?: return@map emptyMap<String, Any?>()
                val out = linkedMapOf<String, Any?>()
                for (name in item.keys()) out[name] = item.opt(name)
                out
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun setListOfDicts(key: String, value: List<Map<String, Any?>>) {
        try {
            val array = org.json.JSONArray()
            for (item in value) array.put(org.json.JSONObject(item))
            prefs?.edit()?.putString("json:$key", array.toString())?.apply()
        } catch (_: Throwable) {
        }
    }
}
