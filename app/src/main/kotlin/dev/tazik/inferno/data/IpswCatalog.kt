package dev.tazik.inferno.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class FirmwareEntry(
    val version: String = "",
    val buildid: String = "",
    val url: String = "",
    val filesize: Long = 0L,
    val signed: Boolean = false,
    val name: String = "",
    val devices: String = "",
    val sha1sum: String = "",
)

@Serializable
data class DeviceCatalog(
    val identifier: String = "iPhone12,1",
    val name: String = "",
    val firmwares: List<FirmwareEntry> = emptyList(),
)

object IpswCatalog {
    const val TARGET_DEVICE = "iPhone12,1"

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(deviceId: String = TARGET_DEVICE): Result<List<FirmwareEntry>> = try {
        val body = http.newCall(
            Request.Builder()
                .header("User-Agent", "tazik/1.0 (android)")
                .url("https://api.ipsw.me/v4/device/$deviceId?type=ipsw")
                .build()
        ).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            resp.body!!.string()
        }
        val parsed = json.decodeFromString(DeviceCatalog.serializer(), body)
        Result.success(parsed.firmwares.filter { it.url.startsWith("http") })
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
