package dev.tazik.inferno.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Каталог приложений, которые действительно можно поставить в гостя.
 *
 * Не App Store, и им быть не может. `.ipa`, купленный у Apple, несёт главный
 * бинарник, зашифрованный FairPlay, и это отменяется при установке собственным
 * железом настоящего устройства против аккаунта, которым куплено, — оттого
 * даунгрейдеры вроде PancakeStore просят сам iOS ставить, на телефоне, на
 * котором работают, а не вручают кому-то файл. У эмулируемого гостя этого нет,
 * так что `.ipa` из App Store встал бы и умер при запуске.
 *
 * Работает всё, что распространяется мимо App Store: эмуляторы, утилиты,
 * инструменты джейлбрейка, open-source приложения. Те идут незашифрованными —
 * измерено, не предположено: DolphiniOS из источника OatmealDome имеет
 * `cryptid 0`, никаких `SC_Info` и `MinimumOSVersion 14.0`. Эта полка и
 * заполняется.
 *
 * Формат — AltStore, которым пользуются сразу два поколения источников —
 * сверено с пятью живыми: часть несёт только современный массив `versions`,
 * часть только старые плоские поля, часть и то и другое. Читаются оба, потому
 * что выбросить любое — значит опустошить половину каталога.
 */
data class CatalogVersion(
    val version: String,
    val date: String,
    val size: Long,
    val downloadURL: URL,
    val minOS: String?,
    val maxOS: String?,
    val notes: String,
) {

    /** Запускается ли эта сборка на iOS 14 гостя.
     *
     * Три ответа, а не два. Немало источников вовсе не объявляют
     * `minOSVersion` — Taurine, джейлбрейк, написанный *под* iOS 14, один из
     * них, — так что «не сказал» не должно считаться «не пойдёт», иначе фильтр
     * спрятал бы ровно то, что стоит иметь.
     */
    enum class Fit { YES, NO, UNKNOWN }

    val fit: Fit
        get() {
            val low = minOS?.let { major(it) } ?: return Fit.UNKNOWN
            if (low > 14) return Fit.NO
            if (maxOS?.let { major(it) } ?: Int.MAX_VALUE < 14) return Fit.NO
            return Fit.YES
        }

    companion object {
        private fun major(text: String): Int? = text.split(".").firstOrNull()?.toIntOrNull()
    }
}

data class CatalogApp(
    val id: String,
    val name: String,
    val developer: String,
    val summary: String,
    val iconURL: URL?,
    val versions: List<CatalogVersion>,
    val source: String,
) {
    /** Новейшая сборка, признающаяся, что идёт на iOS 14, или новейшая,
     * молчащая об этом. Null, когда каждая версия гостя отсекла.
     */
    val best: CatalogVersion?
        get() = versions.firstOrNull { it.fit == CatalogVersion.Fit.YES }
            ?: versions.firstOrNull { it.fit == CatalogVersion.Fit.UNKNOWN }

    val newest: CatalogVersion? get() = versions.firstOrNull()
}

/** Один источник, хранимый простым адресом, как репозитории пакетов. */
data class AppSource(val url: String) {
    val id: String get() = url
}

class CatalogStore {

    sealed class State {
        object Idle : State()
        data class Loading(val host: String) : State()
        data class Failed(val why: String) : State()
    }

    private val _apps = MutableStateFlow<List<CatalogApp>>(emptyList())
    val apps: StateFlow<List<CatalogApp>> = _apps.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _sources = MutableStateFlow(remembered())
    val sources: StateFlow<List<AppSource>> = _sources.asStateFlow()

    /** Меняет список источников и запоминает его. */
    fun setSources(list: List<AppSource>) {
        _sources.value = list
        remember(list)
    }

    /** Источники, которые реально откликались и разбирались на момент записи,
     * и несущие то, что гость iOS 14 сможет запустить. Нарочно короткий: список
     * мёртвых адресов хуже отсутствия списка.
     */
    companion object {
        val defaults = listOf(
            // DolphiniOS — его публичная бета прямо объявляет iOS 14.0.
            "https://altstore.oatmealdome.me",
            // Taurine — джейлбрейк для 14.0–14.3.
            "https://taurine.app/altstore/taurinestore.json",
            // iSH — шелл Linux, идущий на старом iOS.
            "https://ish.app/altstore.json",
            // Provenance — эмуляторы.
            "https://provenance-emu.com/apps.json",
        )

        private const val key = "appSources"

        private fun remembered(): List<AppSource> {
            val list = Prefs.listOfDicts(key)
            if (list.isEmpty()) return defaults.map { AppSource(it) }
            return list.mapNotNull { it.str("url")?.let { url -> AppSource(url) } }
        }

        private fun remember(list: List<AppSource>) {
            Prefs.setListOfDicts(key, list.map { mapOf("url" to it.url) })
        }

        class NotASource : Exception(L("это не источник приложений (нет списка apps)"))

        private val http = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun fetch(url: URL): List<CatalogApp> {
            val request = okhttp3.Request.Builder().url(url).build()
            http.newCall(request).execute().use { response ->
                if (response.code != 200) throw NotASource()
                val body = response.body?.string() ?: throw NotASource()
                val root = Json.toMap(JSONObject(body))
                val list = (root["apps"] as? List<*>) ?: throw NotASource()
                val name = root.str("name") ?: (url.host ?: "")
                return list.mapNotNull { item ->
                    (item as? Map<String, Any?>)?.let { parse(it, name) }
                }
            }
        }

        private fun parse(item: Map<String, Any?>, source: String): CatalogApp? {
            val id = item.str("bundleIdentifier") ?: return null
            if (id.isEmpty()) return null
            val name = item.str("name") ?: return null

            var versions = ((item["versions"] as? List<*>)
                ?.mapNotNull { (it as? Map<String, Any?>)?.let { one -> version(one) } }
                ?: emptyList())
            // Старая форма держит поля новейшей сборки на самом приложении.
            // Источники, публикующие оба, согласны, так что это лишь заполнение
            // для тех, кто не публикует ничего другого.
            if (versions.isEmpty()) {
                listOfNotNull(
                    version(
                        item,
                        fallbackDate = item.str("versionDate"),
                        fallbackNotes = item.str("versionDescription"),
                    ),
                ).also { versions = it }
            }
            if (versions.isEmpty()) return null

            val summary = item.str("subtitle")
                ?: item.str("localizedDescription")?.split("\n")?.firstOrNull()
                ?: ""
            return CatalogApp(
                id = id,
                name = name,
                developer = item.str("developerName") ?: "",
                summary = summary,
                iconURL = item.str("iconURL")?.let { runCatching { URL(it) }.getOrNull() },
                versions = versions,
                source = source,
            )
        }

        private fun version(
            item: Map<String, Any?>,
            fallbackDate: String? = null,
            fallbackNotes: String? = null,
        ): CatalogVersion? {
            val link = item.str("downloadURL") ?: return null
            val url = try {
                URL(link)
            } catch (_: Throwable) {
                return null
            }
            val size = when (val raw = item["size"]) {
                is Long -> raw
                is Int -> raw.toLong()
                is String -> raw.toLongOrNull() ?: 0
                else -> 0
            }
            return CatalogVersion(
                version = item.str("version") ?: "",
                date = item.str("date") ?: fallbackDate ?: "",
                size = size,
                downloadURL = url,
                minOS = item.str("minOSVersion"),
                maxOS = item.str("maxOSVersion"),
                notes = item.str("localizedDescription") ?: fallbackNotes ?: "",
            )
        }
    }

    /** Обходит источники и собирает каталог. Блокирует; звать не с главного
     * потока.
     */
    fun refresh() {
        val found = ArrayList<CatalogApp>()
        val complaints = ArrayList<String>()

        for (source in _sources.value) {
            val url = try {
                URL(source.url)
            } catch (_: Throwable) {
                continue
            }
            _state.value = State.Loading(url.host ?: source.url)
            try {
                found += fetch(url)
            } catch (e: Throwable) {
                complaints.add("${url.host ?: source.url}: ${e.message ?: L("ошибка")}")
            }
        }

        // Одна запись на bundle id; порядок решает имя.
        val byID = linkedMapOf<String, CatalogApp>()
        for (app in found) if (byID[app.id] == null) byID[app.id] = app
        _apps.value = byID.values.sortedBy { it.name.lowercase() }
        _state.value = if (complaints.isEmpty()) State.Idle else State.Failed(complaints.joinToString("\n"))
    }
}
