package dev.tazik.inferno.engine

/**
 * Порт `Bytes`-хелперов из Swift-версии Inferno.
 *
 * В Swift беззнаковые типы (`UInt8`/`UInt16`/`UInt32`/`UInt64`) были обычными
 * числами. В Kotlin беззнаковые операции делаются масками, поэтому все сдвиги
 * и сборки собраны здесь, а не разбросаны по коду.
 */

fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

fun le32(v: Int): ByteArray = ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() }

fun le64(v: Long): ByteArray = ByteArray(8) { ((v ushr (8 * it)) and 0xFF).toByte() }

fun be16(v: Int): ByteArray = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

fun be32(v: Int): ByteArray = ByteArray(4) { ((v ushr (8 * (3 - it))) and 0xFF).toByte() }

fun be64(v: Long): ByteArray = ByteArray(8) { ((v ushr (8 * (7 - it))) and 0xFF).toByte() }

fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF

fun u16(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)

fun u24(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16)

fun u32(b: ByteArray, at: Int): Long =
    (u8(b, at).toLong()) or
        (u8(b, at + 1).toLong() shl 8) or
        (u8(b, at + 2).toLong() shl 16) or
        (u8(b, at + 3).toLong() shl 24)

fun u64(b: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or (u8(b, at + i).toLong() shl (8 * i))
    return v
}

fun u16be(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)

fun u32be(b: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 0 until 4) v = (v shl 8) or u8(b, at + i).toLong()
    return v
}

fun u64be(b: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or u8(b, at + i).toLong()
    return v
}

/** Беззнаковое big-endian длиной `size` байт — для трейлера бинарного plist. */
fun uBE(b: ByteArray, at: Int, size: Int): Long {
    var v = 0L
    for (i in 0 until size) v = (v shl 8) or u8(b, at + i).toLong()
    return v
}

/** `Int32` из little-endian беззнакового 32-битного. */
fun i32(b: ByteArray, at: Int): Int = u32(b, at).toInt()

fun i64(b: ByteArray, at: Int): Long = u64(b, at)

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun ByteArray.readExactly(count: Int): ByteArray {
    require(count >= 0 && count <= size) { L("readExactly(%d) из %d", count, size) }
    return copyOfRange(0, count)
}

fun ByteArray.sliceFrom(at: Int): ByteArray =
    if (at <= 0) copyOf() else copyOfRange(minOf(at, size), size)

operator fun ByteArray.plus(other: ByteArray): ByteArray = concat(other)

fun ByteArray.concat(other: ByteArray): ByteArray {
    if (isEmpty()) return other.copyOf()
    if (other.isEmpty()) return copyOf()
    val out = ByteArray(size + other.size)
    System.arraycopy(this, 0, out, 0, size)
    System.arraycopy(other, 0, out, size, other.size)
    return out
}

fun ByteArray.getU8(at: Int): Int = u8(this, at)
fun ByteArray.getU16(at: Int): Int = u16(this, at)
fun ByteArray.getU32(at: Int): Long = u32(this, at)
fun ByteArray.getU64(at: Int): Long = u64(this, at)

/** Строка из байтов; недопустимые последовательности заменяются. */
fun bytesToText(b: ByteArray): String = String(b, Charsets.UTF_8)

fun textToBytes(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

/** Удобный `Int` → `UInt32` без знака. */
fun Int.toUnsigned(): Long = toLong() and 0xFFFF_FFFFL

fun Long.toInt32(): Int = toInt()

fun ByteArray.equalsBytes(other: ByteArray): Boolean = contentEquals(other)
