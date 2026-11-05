package dev.tazik.inferno.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Указатель пакетов, читаемый прямо из репозитория джейлбрейка.
 *
 * Cydia в госте такому делу не доверяет: он блокирует собственный главный
 * поток, пока работает dpkg, а гость такой медленности блокирует его надолго
 * — настолько, что iOS его убивает. У телефона, напротив, настоящая сеть и
 * никто, следящий за часами, — поэтому просмотр и скачивание идут здесь, и
 * гостю вручается только готовый `.deb`.
 */
data class RepoPackage(
    val id: String,
    val name: String,
    val version: String,
    val section: String,
    val summary: String,
    val author: String,
    /** Где живёт `.deb`, относительно собственного адреса репозитория. */
    val path: String,
    val size: Long,
    val repo: URL,
) {
    val url: URL?
        get() = try {
            URL(repo, path)
        } catch (_: Throwable) {
            null
        }

    /** Пакеты, уносящие гостя с собой, и почему. */
    val refusal: String? get() = Companion.refusals[id]

    companion object {
        private val hookingRuntime: String
            get() = L("Внедряется в launchd, а гость этого не умеет: launchd падает, ядро уходит в панику.")

        private val refusals: Map<String, String> = mapOf(
            "mobilesubstrate" to hookingRuntime,
            "com.saurik.substrate.safemode" to hookingRuntime,
            "com.ex.substitute" to hookingRuntime,
            "com.ex.libsubstitute" to hookingRuntime,
            "org.coolstar.libhooker" to hookingRuntime,
            "org.coolstar.libhooker-utils" to hookingRuntime,
            "ellekit" to hookingRuntime,
            "com.ex.substitute-safemode" to hookingRuntime,
        )
    }
}

/** Один репозиторий и то, что о нём сказала последняя выгрузка. */
data class Repo(val url: String) {
    val id: String get() = url

    val address: URL?
        get() {
            // Репозитории называются каталогом, и пропущенный слэш превращает
            // последний компонент в соседа. Адрес, уже указывающий внутрь
            // дерева `dists`, обрезается до самого репозитория: относительно
            // него и даны пути пакетов.
            var text = url
            val cut = text.indexOf("/dists/")
            if (cut >= 0) text = text.substring(0, cut)
            return try {
                URL(if (text.endsWith("/")) text else "$text/")
            } catch (_: Throwable) {
                null
            }
        }
}

class RepoStore {

    sealed class State {
        object Idle : State()
        data class Loading(val host: String) : State()
        data class Failed(val why: String) : State()
    }

    private val _packages = MutableStateFlow<List<RepoPackage>>(emptyList())
    val packages: StateFlow<List<RepoPackage>> = _packages.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _repos = MutableStateFlow(remembered())
    val repos: StateFlow<List<Repo>> = _repos.asStateFlow()

    fun setRepos(list: List<Repo>) {
        _repos.value = list
        remember(list)
    }

    companion object {
        /** Репозитории, с которыми свежий бутстрап приходит, чтобы список был
         * полезен прежде, чем кто-то наберёт адрес.
         */
        val defaults = listOf(
            "https://apt.bingner.com/",
            // HTTP нарочно: его сертификат истёк, так что HTTPS отказывает сразу.
            // iOS отказывает HTTP, если не сказать иначе, и Info.plist делает
            // исключение для этого одного хоста.
            "http://apt.thebigboss.org/repofiles/cydia/",
            "https://repo.chariz.com/",
            "https://havoc.app/",
            // Filza живёт больше нигде.
            "https://tigisoftware.com/cydia/",
        )

        private const val key = "repositories"

        private fun remembered(): List<Repo> {
            val list = Prefs.listOfDicts(key)
            if (list.isEmpty()) return defaults.map { Repo(it) }
            return list.mapNotNull { it.str("url")?.let { url -> Repo(url) } }
        }

        private fun remember(list: List<Repo>) {
            Prefs.setListOfDicts(key, list.map { mapOf("url" to it.url) })
        }

        class NoIndex : Exception(L("нет читаемого указателя пакетов (Packages, Packages.gz, Packages.bz2)"))

        /** Где может лежать указатель, относительно собственного адреса
         * репозитория.
         *
         * Две раскладки и три кодировки. Плоская — указатель в корне — то, что
         * используют большинство репозиториев твиков. Старые — настоящие
         * дебиановские архивы с деревом `dists`, и их имена `stable`/`ios`
         * приходится угадывать, потому что ничто в корне не говорит, какое они
         * используют. `.bz2` первым: BigBoss не публикует ничего другого.
         * `.zst`, который используют несколько новейших, оставлен в стороне —
         * у iOS нет для него распаковщика.
         */
        private val indexPaths = listOf(
            "Packages.bz2", "Packages.gz", "Packages",
            "dists/stable/main/binary-iphoneos-arm/Packages.bz2",
            "dists/stable/main/binary-iphoneos-arm/Packages.gz",
            "dists/ios/1443.00/main/binary-iphoneos-arm/Packages.bz2",
            "dists/ios/1443.00/main/binary-iphoneos-arm/Packages.gz",
        )

        private val http = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Читает указатель одного репозитория.
         *
         * Собственный `Filename` пакетов относителен адресу репозитория,
         * никогда — тому месту, где указатель оказался.
         */
        private fun fetch(repo: URL): List<RepoPackage> {
            // Почему на запрос вообще никто не ответил. Когда ни один из путей
            // не был отозван, вот настоящая причина — отказ в соединении,
            // сертификат, iOS, блокирующий чистый HTTP, — и «нет указателя»
            // спрятал бы её.
            var unanswered: Throwable? = null
            var answered = false
            for (name in indexPaths) {
                val url = try {
                    URL(repo, name)
                } catch (_: Throwable) {
                    continue
                }
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Telesphoreo APT-HTTP/1.0.592")
                    .header("X-Machine", "iPhone12,1")
                    .header("X-Firmware", "14.0")
                    .build()

                val data = try {
                    http.newCall(request).execute().use { response ->
                        if (response.code != 200) return@use null
                        response.body?.bytes() ?: return@use null
                    }
                } catch (e: Throwable) {
                    unanswered = e
                    continue
                }
                answered = true
                data ?: continue

                val text = when {
                    name.endsWith(".bz2") -> Bzip2.decompress(data) ?: continue
                    name.endsWith(".gz") -> Gzip.inflate(data) ?: continue
                    else -> data
                }
                return parse(String(text, Charsets.UTF_8), repo)
            }
            if (!answered) unanswered?.let { throw it }
            throw NoIndex()
        }

        /** Формат контроля Debian: абзацы `Key: value`, разделённые пустыми
         * строками, с продолжениями с отступом.
         */
        private fun parse(text: String, repo: URL): List<RepoPackage> {
            val out = ArrayList<RepoPackage>()
            var field = linkedMapOf<String, String>()

            fun flush() {
                val id = field["package"]
                val path = field["filename"]
                try {
                    if (id.isNullOrEmpty() || path.isNullOrEmpty()) return
                    out.add(
                        RepoPackage(
                            id = id,
                            name = field["name"] ?: id,
                            version = field["version"] ?: "",
                            section = field["section"] ?: "",
                            summary = field["description"]?.split("\n")?.firstOrNull() ?: "",
                            author = field["author"] ?: field["maintainer"] ?: "",
                            path = path,
                            size = field["size"]?.toLongOrNull() ?: 0,
                            repo = repo,
                        ),
                    )
                } finally {
                    field = linkedMapOf()
                }
            }

            var lastKey = ""
            for (line in text.split("\n")) {
                if (line.isEmpty()) {
                    flush()
                    lastKey = ""
                    continue
                }
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    if (lastKey.isNotEmpty()) {
                        field[lastKey] = (field[lastKey] ?: "") + "\n" + line.trim()
                    }
                    continue
                }
                val colon = line.indexOf(':')
                if (colon < 0) continue
                lastKey = line.substring(0, colon).lowercase()
                field[lastKey] = line.substring(colon + 1).trim()
            }
            flush()
            return out
        }

        /** bzip2. Старые репозитории — среди них bingner'а и BigBoss — публикуют
         * свой указатель только как `.bz2`, и без этого они просто не
         * открываются. В Swift библиотеку находили dlopen'ом; на Android
         * libbz2 не в списке публичных библиотек, поэтому здесь честная
         * реализация Apache Commons Compress.
         */
        fun decompressBzip2(data: ByteArray): ByteArray? {
            if (data.size <= 4 || data[0] != 0x42.toByte() || data[1] != 0x5A.toByte() || data[2] != 0x68.toByte()) {
                return null
            }
            return try {
                BZip2CompressorInputStream(ByteArrayInputStream(data)).use { stream ->
                    val out = ByteArrayOutputStream()
                    stream.copyTo(out)
                    out.toByteArray()
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** Обходит репозитории и собирает общий список пакетов. Блокирует; звать
     * не с главного потока.
     */
    fun refresh() {
        val found = ArrayList<RepoPackage>()
        val complaints = ArrayList<String>()

        for (repo in _repos.value) {
            val address = repo.address ?: continue
            _state.value = State.Loading(address.host ?: repo.url)
            try {
                found += fetch(address)
            } catch (e: Throwable) {
                complaints.add("${address.host ?: repo.url}: ${e.message ?: L("ошибка")}")
            }
        }

        // Побеждает новейшая версия каждого пакета, а порядок решает имя —
        // указатели приходят в том порядке, каком репозиторию захотелось.
        val newest = linkedMapOf<String, RepoPackage>()
        for (package_ in found) {
            val have = newest[package_.id]
            if (have != null && have.version >= package_.version) continue
            newest[package_.id] = package_
        }
        _packages.value = newest.values.sortedBy { it.name.lowercase() }
        _state.value = if (complaints.isEmpty()) State.Idle else State.Failed(complaints.joinToString("\n"))
    }
}

/** Достаточно gzip, чтобы прочитать указатель пакетов. */
object Gzip {
    fun inflate(data: ByteArray): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(data)).use { stream ->
            val out = ByteArrayOutputStream()
            stream.copyTo(out)
            out.toByteArray()
        }
    } catch (_: Throwable) {
        null
    }
}

/** bzip2, для указателей старых репозиториев. */
object Bzip2 {
    fun decompress(data: ByteArray): ByteArray? = RepoStore.decompressBzip2(data)
}
