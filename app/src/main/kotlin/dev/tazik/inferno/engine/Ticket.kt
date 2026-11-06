package dev.tazik.inferno.engine

/**
 * Подделка тикетов, нужных восстановлению.
 *
 * Версии, которые запускает этот эмулятор, Apple больше не подписывает, поэтому
 * тикет, ручающийся за них, делается, а не скачивается: тикет, который Apple
 * когда-то подписала, разбирается, дайджесты внутри заменяются дайджестами
 * устанавливаемой прошивки, и он собирается обратно. SEP ROM эмулируемого
 * устройства пропатчен, чтобы его принять, — в этом весь трюк, и поэтому здесь
 * никто не разговаривает с Apple.
 *
 * Это `create_apticket.py` и `create_septicket.py` ChefKiss'а, на Kotlin, чтобы
 * телефон мог сделать это без компьютера. Совпадает с ними байт в байт.
 */
object Ticket {

    enum class FailureKind { NO_TICKET, SHAPE, NO_DIGEST }

    class Failure(message: String, val kind: FailureKind) : Exception(message)

    /** Какой записи манифеста соответствует каждый четырёхсимвольный код тикета. */
    val apComponents: Map<String, String> = mapOf(
        "rosi" to "OS",
        "krnl" to "KernelCache",
        "dtre" to "DeviceTree",
        "rdtr" to "RestoreDeviceTree",
        "trst" to "StaticTrustCache",
        "rtsc" to "RestoreTrustCache",
        "mtfw" to "Multitouch",
        "anef" to "ANE",
        "aopf" to "AOP",
        "avef" to "AVE",
        "hpas" to "Ap,HapticAssets",
        "acfw" to "AudioCodecFirmware",
        "gfxf" to "GFX",
        "ispf" to "ISP",
        "lphp" to "LeapHaptics",
        "pmpf" to "PMP",
        "siof" to "SIO",
        "sepi" to "SEP",
        "rsep" to "RestoreSEP",
        "rdsk" to "RestoreRamDisk",
        "isys" to "SystemVolume",
        "msys" to "SystemVolumeCanonicalMetadata",
    )

    /** Собственный тикет SEP ручается за меньшее. */
    val sepComponents: Map<String, String> = mapOf(
        "rosi" to "OS",
        "krnl" to "KernelCache",
        "dtre" to "DeviceTree",
        "rdtr" to "RestoreDeviceTree",
        "trst" to "StaticTrustCache",
        "rtsc" to "RestoreTrustCache",
        "sepi" to "SEP",
        "rsep" to "RestoreSEP",
    )

    /**
     * Коды, чьи записи должны перестать с чем-либо совпадать: имя внутри
     * компонента переворачивается, что оставляет правильный по форме тикет,
     * который больше не заявляет эти прошивки.
     */
    private val corrupted = setOf("rfta", "ftap", "rfts", "ftsp")

    /**
     * ECID, который сообщает эмулируемое устройство, и nonce, который SEP ждёт
     * вместе с ним. Оба принадлежат машине, а не Apple.
     */
    const val ecid: Long = 0x1122_3344_5566_7788L

    private val nonce: ByteArray = run {
        val out = ByteArray(20)
        val piece = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte())
        for (i in 0 until 5) System.arraycopy(piece, 0, out, i * 4, 4)
        out
    }

    // MARK: - Два тикета

    fun forgeAP(shsh: ByteArray, identity: Map<String, Any?>): ByteArray {
        val ticket = root(shsh)
        rewrite(components(ticket), identity, apComponents, null)
        return DER.encode(ticket)
    }

    fun forgeSEP(shsh: ByteArray, identity: Map<String, Any?>, chipID: Long = 0x8030): ByteArray {
        val ticket = root(shsh)
        rewrite(components(ticket), identity, sepComponents, chipID)

        // Те же правки ещё раз, внутри сертификата, который несёт копию
        // манифеста: расширение номер четыре держит DER SET тех же самых
        // компонентов. Устройство проверяет эту копию, поэтому она должна
        // согласовать — но её ECID и nonce остаются как были.
        val extensionValue = certificateManifest(ticket)
        val inner = DER.parse(extensionValue.value)
        val items = inner.children ?: throw Failure(
            L("Тикет устроен не так, как ожидалось: %@", "certificate manifest"),
            FailureKind.SHAPE,
        )
        rewrite(items, identity, sepComponents, chipID, touchDeviceIdentity = false)
        extensionValue.value = DER.encode(inner)

        return DER.encode(ticket)
    }

    // MARK: - Обход тикета

    private fun root(shsh: ByteArray): DER.Node {
        val plist = PropertyList.parse(shsh).asDict()
            ?: throw Failure(L("В ticket.shsh2 нет ApImg4Ticket"), FailureKind.NO_TICKET)
        val ticket = plist.bytes("ApImg4Ticket")
            ?: throw Failure(L("В ticket.shsh2 нет ApImg4Ticket"), FailureKind.NO_TICKET)
        val node = DER.parse(ticket)
        if (node.children?.firstOrNull()?.text != "IM4M") {
            throw Failure(L("Тикет устроен не так, как ожидалось: %@", "not an IM4M"), FailureKind.SHAPE)
        }
        return node
    }

    /** `IM4M ::= SEQUENCE { "IM4M", version, SET { MANB { SEQUENCE { "MANB", SET { component… } } } }, … }` */
    private fun components(ticket: DER.Node): List<DER.Node> {
        val top = ticket.children ?: return emptyList()
        if (top.size <= 2) return emptyList()
        val manb = top[2].children?.firstOrNull() ?: return emptyList()
        val body = manb.children?.firstOrNull()?.children ?: return emptyList()
        if (body.size <= 1) return emptyList()
        return body[1].children ?: emptyList()
    }

    /**
     * Одно поле компонента: `'DGST' { SEQUENCE { "DGST", value } }`, и
     * возвращается узел значения, чтобы его переписать на месте.
     */
    private fun field(name: String, component: DER.Node): DER.Node? {
        val entries = component.children?.firstOrNull()?.children ?: return null
        if (entries.size <= 1) return null
        val fields = entries[1].children ?: return null
        for (field in fields) {
            if (field.fourCC != name) continue
            val pair = field.children?.firstOrNull()?.children ?: continue
            if (pair.size > 1) return pair[1]
        }
        return null
    }

    private fun rewrite(
        components: List<DER.Node>,
        identity: Map<String, Any?>,
        map: Map<String, String>,
        chipID: Long?,
        touchDeviceIdentity: Boolean = true,
    ) {
        val manifest = identity.dict("Manifest") ?: emptyMap()
        for (component in components) {
            val code = component.fourCC ?: continue

            val key = map[code]
            if (key != null) {
                // Компонент, которого нет в прошивке, сохраняет то, что о нём
                // сказал подписанный тикет: установка, где такой прошивки
                // никогда нет, о ней и не спрашивает. В iOS 16 нет
                // SystemVolumeCanonicalMetadata, например.
                // BuildManifest называет ключи с префиксом «Ap,» — берём его
                // фолбэком, иначе msys-дайджест никогда не переписывается.
                val entry = (manifest[key].asDict() ?: manifest["Ap,$key"].asDict()) ?: continue
                val digest = entry["Digest"] as? ByteArray ?: continue
                val value = field("DGST", component) ?: continue
                value.identifier = byteArrayOf(0x04)
                value.value = digest
                continue
            }

            if (code in corrupted) {
                // Имя внутри компонента, а не тег вокруг него: именно на него
                // смотрит устройство.
                val name = component.children?.firstOrNull()?.children?.firstOrNull() ?: continue
                name.value = name.value.reversedArray()
                continue
            }

            if (code == "MANP" && chipID != null) {
                field("CHIP", component)?.let { chip ->
                    val encoded = DER.integer(chipID)
                    chip.identifier = encoded.identifier
                    chip.value = encoded.value
                }
                if (touchDeviceIdentity) {
                    field("ECID", component)?.let { value ->
                        val encoded = DER.integer(ecid)
                        value.identifier = encoded.identifier
                        value.value = encoded.value
                    }
                    field("snon", component)?.let { value ->
                        value.identifier = byteArrayOf(0x04)
                        value.value = nonce
                    }
                }
            }
        }
    }

    /**
     * Пятое расширение первого сертификата, где лежит вторая копия манифеста.
     */
    private fun certificateManifest(ticket: DER.Node): DER.Node {
        val top = ticket.children ?: throw shape("certificate")
        if (top.size <= 4) throw shape("certificate")
        val certificate = top[4].children?.firstOrNull() ?: throw shape("certificate")
        val tbs = certificate.children?.firstOrNull() ?: throw shape("certificate")
        val tbsFields = tbs.children ?: throw shape("certificate")

        // Расширения едут в явном [3] на TBS-сертификате.
        val extensions = tbsFields
            .firstOrNull { it.identifier.contentEquals(byteArrayOf(0xA3.toByte())) }
            ?.children?.firstOrNull()?.children
            ?: throw shape("extensions")
        if (extensions.size <= 4) throw shape("extensions")
        val fields = extensions[4].children ?: throw shape("extensions")

        // Extension ::= SEQUENCE { extnID, critical DEFAULT FALSE, extnValue }
        val value = fields.lastOrNull() ?: throw shape("extnValue")
        if (!value.identifier.contentEquals(byteArrayOf(0x04))) throw shape("extnValue")
        return value
    }

    private fun shape(what: String): Failure =
        Failure(L("Тикет устроен не так, как ожидалось: %@", what), FailureKind.SHAPE)
}
