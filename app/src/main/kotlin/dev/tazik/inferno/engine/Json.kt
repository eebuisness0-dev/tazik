package dev.tazik.inferno.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON в обе стороны — агент гостя, каталог приложений и указатели репозиториев
 * говорят им. Разобранный документ — те же `Map`/`List`, что у `PropertyList`,
 * поэтому везде дальше работают одни и те же короткие обращения.
 */
object Json {

    fun toMap(obj: JSONObject): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for (key in obj.keys()) out[key] = value(obj.opt(key))
        return out
    }

    fun toList(array: JSONArray): List<Any?> =
        (0 until array.length()).map { value(array.opt(it)) }

    private fun value(any: Any?): Any? = when (any) {
        is JSONObject -> toMap(any)
        is JSONArray -> toList(any)
        else -> any
    }
}
