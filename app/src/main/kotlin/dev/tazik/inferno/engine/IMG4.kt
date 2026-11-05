package dev.tazik.inferno.engine

/**
 * Контейнеры IMG4 от Apple и ровно столько DER, сколько нужно с ними работать.
 *
 * Всё, что восстановление отдаёт устройству, — один из них: ядро, дерево
 * устройства, рамдиск, прошивка SEP, сам тикет. Компонент приходит из IPSW как
 * `IM4P` — тип, версия и payload — и уходит на устройство как `IMG4`, то есть
 * этот payload плюс тикет (`IM4M`), который за него ручается.
 * Персонализировать компонент — ровно эта обёртка, а подделка тикета — правка
 * дайджестов внутри него.
 *
 * DER здесь нарочно маленький: дерево узлов тег/значение, которое переживает
 * декодирование и кодирование байт в байт, поэтому всё непонятое проходит
 * насквозь нетронутым. Это важно — в тикете есть поля, которые никто вне Apple
 * не может назвать, и они должны выйти так, как вошли.
 */
object DER {

    /**
     * Один узел тег-длина-значение. Составные узлы хранят детей; примитивные —
     * свои байты.
     */
    class Node {
        /**
         * Октеты идентификатора дословно: класс, бит составности и номер тега,
         * который для приватных тегов Apple — четырёхсимвольный код вроде
         * `MANB` и в один байт не влезает.
         */
        var identifier: ByteArray
        var children: MutableList<Node>?
        var value: ByteArray

        constructor(identifier: ByteArray, children: MutableList<Node>) {
            this.identifier = identifier
            this.children = children
            this.value = ByteArray(0)
        }

        constructor(identifier: ByteArray, value: ByteArray) {
            this.identifier = identifier
            this.children = null
            this.value = value
        }

        val isConstructed: Boolean get() = (identifier[0].toInt() and 0x20) != 0

        val tagClass: Int get() = identifier[0].toInt() and 0xC0

        /** Номер тега — и для однобайтовой, и для длинной формы. */
        val tagNumber: Long
            get() {
                if ((identifier[0].toInt() and 0x1F) != 0x1F) {
                    return (identifier[0].toInt() and 0x1F).toLong()
                }
                var number = 0L
                for (i in 1 until identifier.size) {
                    number = (number shl 7) or (identifier[i].toLong() and 0x7FL)
                }
                return number
            }

        /** Приватный тег, прочитанный как четыре символа, которыми его пишет Apple. */
        val fourCC: String?
            get() {
                if (tagClass != 0xC0) return null
                val number = tagNumber
                if (number > 0xFF_FFFFL) return null
                val bytes = ByteArray(4) { i -> ((number ushr (8 * (3 - i))) and 0xFF).toByte() }
                if (bytes.any { (it.toInt() and 0xFF) < 0x20 || (it.toInt() and 0xFF) >= 0x7F }) return null
                return String(bytes, Charsets.UTF_8)
            }

        /** Текст IA5String или похожего, когда это он. */
        val text: String?
            get() {
                if (children != null) return null
                return String(value, Charsets.UTF_8)
            }

        /** Непосредственный ребёнок под этим четырёхсимвольным кодом. */
        fun child(name: String): Node? = children?.firstOrNull { it.fourCC == name }

        /**
         * Первый узел с этим четырёхсимвольным кодом где угодно ниже, в
         * глубину. Удобно для взгляда, слишком расплывчато, чтобы на это
         * опираться: всё, что правит тикет, ходит по ожидаемой форме.
         */
        fun firstTagged(name: String): Node? {
            if (fourCC == name) return this
            for (child in children ?: emptyList<Node>()) {
                val found = child.firstTagged(name)
                if (found != null) return found
            }
            return null
        }
    }

    class Failure(message: String) : Exception(message)

    // MARK: - Декодирование

    fun parse(bytes: ByteArray): Node = parseOne(bytes, 0).first

    fun parseAll(bytes: ByteArray): List<Node> {
        val nodes = mutableListOf<Node>()
        var at = 0
        while (at < bytes.size) {
            val (node, next) = parseOne(bytes, at)
            nodes.add(node)
            at = next
        }
        return nodes
    }

    private fun parseOne(bytes: ByteArray, start: Int): Pair<Node, Int> {
        var at = start
        if (at >= bytes.size) throw Failure(L("DER: %@", "ran out of bytes"))
        val identifier = ArrayList<Byte>()
        identifier.add(bytes[at])
        at += 1
        if ((identifier[0].toInt() and 0x1F) == 0x1F) {
            // Длинная форма тега: семь бит на байт, верхний бит говорит «ещё».
            while (at < bytes.size) {
                val byte = bytes[at]
                identifier.add(byte)
                at += 1
                if ((byte.toInt() and 0x80) == 0) break
            }
        }

        if (at >= bytes.size) throw Failure(L("DER: %@", "no length"))
        var length = bytes[at].toInt() and 0xFF
        at += 1
        if ((length and 0x80) != 0) {
            val count = length and 0x7F
            if (count <= 0 || count > 8 || at + count > bytes.size) {
                throw Failure(L("DER: %@", "bad length"))
            }
            length = 0
            repeat(count) {
                length = (length shl 8) or (bytes[at].toInt() and 0xFF)
                at += 1
            }
        }
        if (at + length > bytes.size) throw Failure(L("DER: %@", "length past the end"))
        val body = bytes.copyOfRange(at, at + length)
        at += length

        val idArray = identifier.toByteArray()
        if ((idArray[0].toInt() and 0x20) != 0) {
            val children = mutableListOf<Node>()
            var inner = 0
            while (inner < body.size) {
                val (child, next) = parseOne(body, inner)
                children.add(child)
                inner = next
            }
            return Node(idArray, children) to at
        }
        return Node(idArray, body) to at
    }

    // MARK: - Кодирование

    fun encode(node: Node): ByteArray {
        val body: ByteArray = if (node.children != null) {
            var out = ByteArray(0)
            for (child in node.children!!) out += encode(child)
            out
        } else {
            node.value
        }
        return node.identifier + length(body.size) + body
    }

    private fun length(count: Int): ByteArray {
        if (count < 0x80) return byteArrayOf(count.toByte())
        var bytes = ByteArray(0)
        var left = count
        while (left > 0) {
            bytes = byteArrayOf((left and 0xFF).toByte()) + bytes
            left = left ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes
    }

    // MARK: - Сборка

    fun sequence(children: List<Node>): Node = Node(byteArrayOf(0x30), children.toMutableList())

    fun set(children: List<Node>): Node = Node(byteArrayOf(0x31), children.toMutableList())

    fun ia5(text: String): Node = Node(byteArrayOf(0x16), text.toByteArray(Charsets.UTF_8))

    fun octets(bytes: ByteArray): Node = Node(byteArrayOf(0x04), bytes)

    fun integer(value: Long): Node {
        var bytes = ByteArray(0)
        var left = value
        do {
            bytes = byteArrayOf((left and 0xFF).toByte()) + bytes
            left = left ushr 8
        } while (left > 0)
        if ((bytes[0].toInt() and 0x80) != 0) bytes = byteArrayOf(0) + bytes
        return Node(byteArrayOf(0x02), bytes)
    }

    /**
     * Узел под одним из приватных четырёхсимвольных тегов Apple — явное
     * тегирование, как на самом деле построено каждое поле тикета: тег
     * оборачивает ровно одного ребёнка, простой SEQUENCE, и уже этот SEQUENCE
     * держит `children` (обычно имя и значение). Сверено с настоящим Cryptex1
     * IM4M байт в байт — каждое поле `MANP`, `CHIP`, `caos`, `DGST`, ...
     * вложено именно так, на один SEQUENCE под своим тегом.
     */
    fun tagged(name: String, children: List<Node>): Node {
        var number = 0L
        for (byte in name.toByteArray(Charsets.UTF_8)) number = (number shl 8) or (byte.toLong() and 0xFF)
        var identifier = byteArrayOf((0xE0 or 0x1F).toByte())
        var seven = ByteArray(0)
        var left = number
        do {
            seven = byteArrayOf((left and 0x7F).toByte()) + seven
            left = left ushr 7
        } while (left > 0)
        for (index in 0 until seven.size - 1) {
            seven[index] = (seven[index].toInt() or 0x80).toByte()
        }
        identifier += seven
        return Node(identifier, mutableListOf(sequence(children)))
    }
}

/**
 * `IM4P` из IPSW и `IMG4`, который устройство хочет вместо него.
 */
object IMG4 {

    enum class FailureKind { NOT_IM4P, NOT_TICKET }

    class Failure(message: String, val kind: FailureKind) : Exception(message)

    /**
     * `IM4P ::= SEQUENCE { "IM4P", type, version, payload, … }`
     */
    data class Payload(
        var type: String,
        var version: String,
        var data: ByteArray,
        /**
         * Всё, что шло после payload — keybags и свойства новых сборок.
         * Хранится, чтобы перепакованный файл выглядел как оригинал.
         */
        var extras: List<DER.Node>,
    )

    fun readIM4P(bytes: ByteArray): Payload {
        val root = DER.parse(bytes)
        val children = root.children
        if (children == null || children.size < 4 || children[0].text != "IM4P") {
            throw Failure(L("Это не IM4P: %@", L("нет заголовка")), FailureKind.NOT_IM4P)
        }
        return Payload(
            type = children[1].text ?: "",
            version = children[2].text ?: "",
            data = children[3].value,
            extras = children.drop(4),
        )
    }

    /**
     * Оборачивает payload и тикет в то, что отдают устройству: это и значит
     * «персонализировать» компонент.
     */
    fun make(payload: Payload, ticket: ByteArray): ByteArray {
        val im4p = DER.sequence(
            listOf(
                DER.ia5("IM4P"),
                DER.ia5(payload.type),
                DER.ia5(payload.version),
                DER.octets(payload.data),
            ) + payload.extras,
        )

        val ticketNode = DER.parse(ticket)
        if (ticketNode.children?.firstOrNull()?.text != "IM4M") {
            throw Failure(L("В файле нет тикета IM4M"), FailureKind.NOT_TICKET)
        }

        // Тикет едет в явном [0], как говорит определение IMG4.
        val wrapped = DER.Node(byteArrayOf(0xA0.toByte()), mutableListOf(ticketNode))
        return DER.encode(DER.sequence(listOf(DER.ia5("IMG4"), im4p, wrapped)))
    }

    /**
     * Пересобирает IM4P вокруг новых байт payload — к этому сводится
     * перепаковка расшифрованной прошивки SEP.
     */
    fun makeIM4P(type: String, version: String, data: ByteArray): ByteArray =
        DER.encode(
            DER.sequence(
                listOf(
                    DER.ia5("IM4P"),
                    DER.ia5(type),
                    DER.ia5(version),
                    DER.octets(data),
                ),
            ),
        )
}
