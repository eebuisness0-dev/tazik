package dev.tazik.inferno.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.io.StringReader

@Suppress("UNCHECKED_CAST")
object PlistParser {
    fun parse(stream: InputStream): Map<String, Any>? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, null)
        return parsePlist(parser) as? Map<String, Any>
    }

    private fun parse(fs: File): Map<String, Any>? = fs.inputStream().use { parse(it) }

    private fun parsePlist(p: XmlPullParser): Any? {
        var event = p.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = p.next()
        if (event == XmlPullParser.END_DOCUMENT) return null
        if (p.name == "plist") {
            // The cursor may land on whitespace/DOCDECL text first; skip to the root value.
            var e = p.next()
            while (e != XmlPullParser.START_TAG && e != XmlPullParser.END_DOCUMENT) e = p.next()
            return parseValue(p)
        }
        return parseValue(p)
    }

    private fun parseValue(p: XmlPullParser): Any? {
        val name = p.name
        return when (name) {
            "dict" -> parseDict(p)
            "array" -> parseArray(p)
            "string" -> p.nextText()
            "integer" -> p.nextText().trim().toLongOrNull() ?: 0L
            "real" -> p.nextText().trim().toDoubleOrNull() ?: 0.0
            "true" -> { p.next(); true }
            "false" -> { p.next(); false }
            "date", "data" -> p.nextText()
            else -> { p.next(); null }
        }
    }

    private fun parseArray(p: XmlPullParser): MutableList<Any?> {
        val list = mutableListOf<Any?>()
        var event = p.next()
        while (event != XmlPullParser.END_TAG) {
            if (event == XmlPullParser.START_TAG) list.add(parseValue(p))
            event = p.next()
        }
        return list
    }

    private fun parseDict(p: XmlPullParser): MutableMap<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        var event = p.next()
        while (event != XmlPullParser.END_TAG) {
            if (event == XmlPullParser.START_TAG) {
                if (p.name == "key") {
                    val key = p.nextText()
                    var e2 = p.next()
                    while (e2 != XmlPullParser.START_TAG) e2 = p.next()
                    map[key] = parseValue(p)
                } else {
                    parseValue(p)
                }
            }
            event = p.next()
        }
        return map
    }
}

data class BuildManifestInfo(
    val skipDmgPaths: Set<String>,
    val kernelPath: String,
    val deviceTreePath: String,
    val ramDiskPath: String,
    val trustCachePath: String,
    val version: String,
    val buildId: String,
) {
    companion object {
        fun from(plist: Map<String, Any>): BuildManifestInfo? {
            @Suppress("UNCHECKED_CAST")
            val idents = plist["BuildIdentities"] as? List<Map<String, Any?>> ?: return null
            val erase = idents.firstOrNull { ident ->
                val info = ident["Info"] as? Map<String, Any?>
                (info?.get("DeviceClass") as? String)?.lowercase() == "n104ap" &&
                    (info?.get("Variant") as? String)?.contains("Erase") == true
            } ?: return null
            @Suppress("UNCHECKED_CAST")
            val manifest = erase["Manifest"] as? Map<String, Map<String, Any?>> ?: return null

            fun pathOf(key: String): String =
                (manifest[key]?.get("Info") as? Map<String, Any?>)?.get("Path") as? String ?: ""

            val skip = manifest.entries
                .filter { (k, v) ->
                    (k == "OS" || k.startsWith("Cryptex1,")) &&
                        (v.get("Info") as? Map<String, Any?>)?.get("Path")?.toString()?.endsWith(".dmg") == true
                }
                .mapNotNull { (it.value.get("Info") as? Map<String, Any?>)?.get("Path")?.toString() }
                .toSet()

            return BuildManifestInfo(
                skipDmgPaths = skip,
                kernelPath = pathOf("KernelCache"),
                deviceTreePath = pathOf("DeviceTree"),
                ramDiskPath = pathOf("RestoreRamDisk"),
                trustCachePath = pathOf("RestoreTrustCache").ifBlank {
                    "Firmware/${pathOf("RestoreRamDisk").substringAfterLast('/')}.trustcache"
                },
                version = ((plist["ProductVersion"] as? String) ?: ""),
                buildId = (plist["ProductBuildVersion"] as? String) ?: "",
            )
        }
    }
}
