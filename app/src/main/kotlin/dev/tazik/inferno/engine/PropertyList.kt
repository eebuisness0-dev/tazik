package dev.tazik.inferno.engine

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64

/**
 * Чтение и запись plist'ов — замена `PropertyListSerialization`.
 *
 * Восстановление говорит плистами: `restored`, ASR, `BuildManifest.plist`,
 * `ticket.shsh2`. Системный `XmlPullParser` разбирает XML, но `data` в нём —
 * просто текст, а тикет лежит именно в `data`; поэтому здесь base64
 * раскодируется в байты, а на записи кодируется обратно.
 *
 * Значения: `Map<String, Any?>`, `List<Any?>`, `String`, `Long`/`Int`,
 * `Double`/`Float`, `Boolean`, `ByteArray`.
 */
object PropertyList {

    // MARK: - Чтение

    private val binaryMagic = "bplist".toByteArray(Charsets.US_ASCII)

    /** `restored` отвечает и XML, и бинарным plist'ом — `PropertyListSerialization`
     * умеет и то и другое, и разбор здесь должен уметь тоже: иначе встреча с
     * бинарным ответом роняет весь разговор с гостем.
     */
    fun parse(bytes: ByteArray): Any? {
        if (bytes.size >= binaryMagic.size && binaryMagic.indices.all { bytes[it] == binaryMagic[it] }) {
            return parseBinary(bytes)
        }
        return parse(ByteArrayInputStream(bytes))
    }

    fun parse(stream: InputStream): Any? {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, null)
        return parseDocument(parser)
    }

    private fun parseDocument(p: XmlPullParser): Any? {
        var event = p.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = p.next()
        if (event == XmlPullParser.END_DOCUMENT) return null
        if (p.name == "plist") {
            var e = p.next()
            while (e != XmlPullParser.START_TAG && e != XmlPullParser.END_DOCUMENT) e = p.next()
            return parseValue(p)
        }
        return parseValue(p)
    }

    private fun parseValue(p: XmlPullParser): Any? {
        return when (p.name) {
            "dict" -> parseDict(p)
            "array" -> parseArray(p)
            "string" -> p.nextText()
            "integer" -> p.nextText().trim().toLongOrNull() ?: 0L
            "real" -> p.nextText().trim().toDoubleOrNull() ?: 0.0
            "true" -> {
                p.next()
                true
            }
            "false" -> {
                p.next()
                false
            }
            "data" -> decodeData(p.nextText())
            "date" -> p.nextText()
            else -> {
                p.next()
                null
            }
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
                    while (e2 != XmlPullParser.START_TAG && e2 != XmlPullParser.END_DOCUMENT) e2 = p.next()
                    map[key] = parseValue(p)
                } else {
                    parseValue(p)
                }
            }
            event = p.next()
        }
        return map
    }

    private fun decodeData(text: String): ByteArray = try {
        Base64.getMimeDecoder().decode(text.trim())
    } catch (_: Throwable) {
        ByteArray(0)
    }

    // MARK: - Бинарный plist (bplist00)

    private class BinaryPlist(private val b: ByteArray, private val offsets: LongArray, private val refSize: Int) {
        private val cache = arrayOfNulls<Any?>(offsets.size)
        private val busy  = BooleanArray(offsets.size)

        fun root(index: Long): Any? = if (index in 0 until offsets.size) readObject(index.toInt()) else null

        private fun readBytes(at: Long, size: Int): ByteArray {
            val from = at.toInt()
            require(from >= 0 && from + size <= b.size) { L("bplist: выход за границу") }
            return b.copyOfRange(from, from + size)
        }

        private fun readUInt(at: Long, size: Int): Long {
            var v = 0L
            for (i in 0 until size) v = (v shl 8) or u8(b, (at + i).toInt()).toLong()
            return v
        }

        /** Знаковое целое: старший бит — знак. */
        private fun readSInt(at: Long, size: Int): Long {
            val raw = readUInt(at, size)
            val sign = 1L shl (size * 8 - 1)
            return if (raw and sign != 0L) raw - (sign shl 1) else raw
        }

        private fun readRef(at: Int): Int = readUInt(at.toLong(), refSize).toInt()

        /** Длина значения: байт маркера либо следующий за ним целый объект. */
        private fun readLength(pos: Int): Pair<Long, Int> {
            val marker = u8(b, pos)
            if ((marker and 0x0F) != 0x0F) return (marker and 0x0F).toLong() to (pos + 1)
            var p = pos + 1
            val sizeMarker = u8(b, p)
            p++
            val size = 1 shl (sizeMarker and 0x0F)
            val len = readUInt(p.toLong(), size)
            p += size
            return len to p
        }

        private fun readObject(index: Int): Any? {
            if (index < 0 || index >= offsets.size) return null
            cache[index]?.let { return it }
            if (busy[index]) return null
            busy[index] = true

            val at   = offsets[index].toInt()
            val mark = u8(b, at)
            val type = mark ushr 4
            val info = mark and 0x0F
            val next = at + 1

            val value: Any? = when (type) {
                0x0 -> null
                0x1 -> info == 1
                0x2 -> readSInt(next.toLong(), 1 shl info)
                0x3 -> {
                    val size = 1 shl info
                    if (size == 4) java.lang.Float.intBitsToFloat(readUInt(next.toLong(), 4).toInt()).toDouble()
                    else readUInt(next.toLong(), size).let { raw ->
                        java.lang.Double.longBitsToDouble(raw)
                    }
                }
                0x4 -> readUInt(next.toLong(), 8).let { raw ->
                    java.lang.Double.longBitsToDouble(raw)
                }
                0x5 -> {
                    val (len, p) = readLength(next)
                    readBytes(p.toLong(), len.toInt())
                }
                0x6 -> {
                    val (len, p) = readLength(next)
                    String(readBytes(p.toLong(), len.toInt()), Charsets.US_ASCII)
                }
                0x7 -> {
                    val (len, p) = readLength(next)
                    val raw = readBytes(p.toLong(), (len.toInt() * 2))
                    buildString(len.toInt()) {
                        for (i in 0 until len.toInt()) {
                            val ch = ((raw[i * 2].toInt() and 0xFF) shl 8) or (raw[i * 2 + 1].toInt() and 0xFF)
                            append(ch.toChar())
                        }
                    }
                }
                0x8 -> readUInt(next.toLong(), info + 1)
                0xA, 0xB, 0xC -> {
                    val (len, p) = readLength(next)
                    val list = ArrayList<Any?>(len.toInt())
                    var atRef = p
                    for (i in 0 until len) {
                        list.add(readObject(readRef(atRef)))
                        atRef += refSize
                    }
                    list
                }
                0xD -> {
                    val (len, p) = readLength(next)
                    val map = LinkedHashMap<String, Any?>(len.toInt())
                    var atKey = p
                    val keys = ArrayList<String>(len.toInt())
                    for (i in 0 until len) {
                        keys.add(readObject(readRef(atKey))?.toString() ?: "")
                        atKey += refSize
                    }
                    var atValue = atKey
                    for (i in 0 until len) {
                        map[keys[i.toInt()]] = readObject(readRef(atValue))
                        atValue += refSize
                    }
                    map
                }
                else -> null
            }

            cache[index] = value
            return value
        }
    }

    private fun parseBinary(bytes: ByteArray): Any? {
        // Хвост: 32 байта трейлера.
        if (bytes.size < 40) return null
        val tail = bytes.size - 32
        val offsetIntSize = u8(bytes, tail)
        val refSize       = u8(bytes, tail + 1)
        val numObjects    = u64be(bytes, tail + 2)
        val topObject     = u64be(bytes, tail + 10)
        val tableOffset   = u64be(bytes, tail + 18)
        if (offsetIntSize !in 1..8 || refSize !in 1..8) return null
        if (numObjects <= 0 || numObjects > 1_000_000) return null
        if (tableOffset < 8 || tableOffset + numObjects * offsetIntSize > bytes.size.toLong()) return null

        val offsets = LongArray(numObjects.toInt()) { i ->
            uBE(bytes, (tableOffset + i * offsetIntSize).toInt(), offsetIntSize)
        }
        return BinaryPlist(bytes, offsets, refSize).root(topObject)
    }

    // MARK: - Запись

    fun data(value: Any?): ByteArray = buildString { writeValue(this, value, 0) }.toByteArray(Charsets.UTF_8)

    private fun writeValue(out: StringBuilder, value: Any?, depth: Int) {
        val pad = "\t".repeat(depth)
        when (value) {
            is Map<*, *> -> {
                out.append(pad).append("<dict>\n")
                for ((k, v) in value) {
                    out.append(pad).append("\t<key>").append(escape(k.toString())).append("</key>\n")
                    writeValue(out, v, depth + 1)
                }
                out.append(pad).append("</dict>\n")
            }
            is List<*> -> {
                out.append(pad).append("<array>\n")
                for (item in value) writeValue(out, item, depth + 1)
                out.append(pad).append("</array>\n")
            }
            is Boolean -> out.append(pad).append(if (value) "<true/>\n" else "<false/>\n")
            is ByteArray -> {
                out.append(pad).append("<data>")
                out.append(Base64.getEncoder().encodeToString(value))
                out.append("</data>\n")
            }
            is Int, is Long -> out.append(pad).append("<integer>").append(value).append("</integer>\n")
            is Double, is Float -> out.append(pad).append("<real>").append(value).append("</real>\n")
            is String -> out.append(pad).append("<string>").append(escape(value)).append("</string>\n")
            else -> out.append(pad).append("<string>").append(escape(value?.toString() ?: "")).append("</string>\n")
        }
    }

    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(ch)
            }
        }
    }

    /** Полный документ, каким его ждёт `PropertyListSerialization`. */
    fun document(value: Any?): ByteArray {
        val head = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
            append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
            append("<plist version=\"1.0\">\n")
        }
        val body = StringBuilder()
        writeValue(body, value, 0)
        return (head + body + "</plist>\n").toByteArray(Charsets.UTF_8)
    }
}

// MARK: - Короткие обращения к разобранному плисту

@Suppress("UNCHECKED_CAST")
fun Any?.asDict(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asList(): List<Any?>? = this as? List<Any?>

fun Map<String, Any?>.dict(key: String): Map<String, Any?>? = this[key].asDict()

fun Map<String, Any?>.list(key: String): List<Any?>? = this[key].asList()

fun Map<String, Any?>.str(key: String): String? = this[key] as? String

fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Number -> v.toLong()
    else -> null
}

fun Map<String, Any?>.double(key: String): Double? = when (val v = this[key]) {
    is Double -> v
    is Float -> v.toDouble()
    is Number -> v.toDouble()
    else -> null
}

fun Map<String, Any?>.flag(key: String): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is Number -> v.toLong() != 0L
    is String -> v == "true" || v == "1"
    else -> false
}

fun Map<String, Any?>.bytes(key: String): ByteArray? = this[key] as? ByteArray

fun Map<String, Any?>.hex64(key: String, fallback: Long): Long {
    return when (val raw = this[key]) {
        is Long -> raw
        is Int -> raw.toLong()
        is Number -> raw.toLong()
        is String -> {
            val trimmed = if (raw.startsWith("0x")) raw.substring(2) else raw
            trimmed.toLongOrNull(16) ?: fallback
        }
        else -> fallback
    }
}
