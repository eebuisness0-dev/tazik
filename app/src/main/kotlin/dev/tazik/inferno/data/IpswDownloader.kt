package dev.tazik.inferno.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import dev.tazik.inferno.engine.L

sealed interface DownloadPhase {
    data object Idle : DownloadPhase
    data class Downloading(val bytesDone: Long, val bytesTotal: Long, val url: String, val version: String, val buildId: String) : DownloadPhase
    data class Verify(val percent: Int, val description: String) : DownloadPhase
    data class Done(val file: File, val version: String, val buildId: String) : DownloadPhase
    data class Failed(val message: String, val file: File? = null, val buildId: String = "", val version: String = "") : DownloadPhase
}

object IpswManager {
    const val TAG = "tazik-ipsw"
    internal val _phase = MutableStateFlow<DownloadPhase>(DownloadPhase.Idle)
    val phase: StateFlow<DownloadPhase> = _phase

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun reset() {
        _phase.value = DownloadPhase.Idle
    }

    suspend fun verify(context: Context, file: File, expectedSha1: String?): Boolean = withContext(Dispatchers.IO) {
        if (expectedSha1.isNullOrBlank()) return@withContext true
        runCatching {
            val md = MessageDigest.getInstance("SHA-1")
            file.inputStream().buffered(1 shl 16).use { input ->
                val buf = ByteArray(1 shl 16)
                var n = input.read(buf)
                while (n > 0) {
                    md.update(buf, 0, n)
                    n = input.read(buf)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            Log.d(TAG, "sha1 $hex")
            hex.equals(expectedSha1, ignoreCase = true)
        }.getOrDefault(false)
    }
}

class IpswDownloader(private val context: Context, private val onUpdate: (DownloadPhase) -> Unit) {
    private val dir = File(context.filesDir, "ipsw").apply { mkdirs() }

    suspend fun download(
        url: String,
        version: String,
        buildId: String,
        sha1: String,
        cancelToken: DownloadToken,
    ): File? = withContext(Dispatchers.IO) {
        val destTmp = File(dir, "$buildId.ipsw.part")
        val dest = File(dir, "$buildId.ipsw")
        var attempt = 0
        while (!cancelToken.cancelled) {
            attempt++
            val bytesDone = if (destTmp.exists()) destTmp.length() else 0L
            val req = Request.Builder()
                .header("User-Agent", "tazik/1.0 (android)")
                .header("If-Range", "\"$buildId\"")
                .header("Range", "bytes=$bytesDone-")
                .url(url)
                .build()
            try {
                IpswManager.http.newCall(req).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) throw IllegalStateException(L("HTTP %d на Range=%d", resp.code, bytesDone))
                    val total = resp.headers["Content-Range"]?.substringAfter("/")?.toLongOrNull()
                        ?: resp.headers["Content-Length"]?.let { it.toLong() + bytesDone }
                        ?: 0L
                    if (total in 1..bytesDone) throw IllegalStateException(L("Сервер вернул меньше ожидаемого"))
                    IpswManager._phase.value = DownloadPhase.Downloading(bytesDone, total, url, version, buildId)
                    RandomAccessFile(destTmp, "rw").use { raf ->
                        raf.seek(bytesDone)
                        val src = resp.body!!.byteStream()
                        val buf = ByteArray(1 shl 18)
                        var written = bytesDone
                        while (!cancelToken.cancelled) {
                            var n = src.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            written += n
                            IpswManager._phase.value = DownloadPhase.Downloading(written, total, url, version, buildId)
                        }
                    }
                    if (cancelToken.cancelled) break
                    if (destTmp.length() < total) {
                        if (attempt < 8) continue
                        throw IllegalStateException(L("Докачка не завершилась за %d попытки", attempt))
                    }
                    break
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (cancelToken.cancelled) break
                if (attempt >= 8) {
                    IpswManager._phase.value = DownloadPhase.Failed(t.message ?: "$t", destTmp, buildId, version)
                    return@withContext null
                }
                kotlinx.coroutines.delay(2500L * attempt)
            }
        }
        if (cancelToken.cancelled) {
            IpswManager._phase.value = DownloadPhase.Idle
            return@withContext null
        }
        dest.delete()
        destTmp.renameTo(dest)
        IpswManager._phase.value = DownloadPhase.Verify(35, L("Проверка SHA-1"))
        val shaOk = IpswManager.verify(context, dest, sha1)
        if (sha1.isNotBlank() && !shaOk) {
            IpswManager._phase.value = DownloadPhase.Failed(L("Контрольная сумма SHA-1 не совпала"), dest, buildId, version)
            return@withContext null
        }
        IpswManager._phase.value = DownloadPhase.Done(dest, version, buildId)
        dest
    }
}

class DownloadToken {
    @Volatile
    var cancelled: Boolean = false
}
