package dev.tazik.inferno.engine

/**
 * Подделка тикета Cryptex1, который iOS 16+ просит посреди восстановления.
 *
 * Начиная с iOS 16 система поставляется в дисковых образах Cryptex1 (system- и
 * app-«криптексы»), и каждому нужен свой тикет — устройство просит его в
 * середине восстановления, а не отдаёт заранее, как тикет AP. `restored`
 * запрашивает `FirmwareUpdaterData` с `MessageArgUpdaterName` = "Cryptex1", что
 * при настоящем восстановлении превращается в запрос TSS к Apple; неподписанная
 * сборка не получает ничего (ChefKiss issue #305).
 *
 * Это `netlab/tssd.py`, на Kotlin, чтобы телефон мог сделать это без
 * компьютера: собственный тикет Cryptex1 настоящего устройства разбирается,
 * его дайджесты заменяются дайджестами этой сборки, а поля тождества
 * подправляются под неё — подпись под всем этим остаётся ровно как была, её не
 * проверяют патчи ядра в эмуляторе, тот же трюк, что и `Ticket.forgeAP`.
 */
object Cryptex1 {

    class Failure(message: String) : Exception(message)

    /** Четырёхсимвольный код компонента Cryptex1 -> ключ манифеста тождества сборки. */
    private val components: Map<String, String> = mapOf(
        "caos" to "Cryptex1,AppOS",
        "casy" to "Cryptex1,AppVolume",
        "trca" to "Cryptex1,AppTrustCache",
        "csos" to "Cryptex1,SystemOS",
        "cssy" to "Cryptex1,SystemVolume",
        "trcs" to "Cryptex1,SystemTrustCache",
        "cros" to "Cryptex1,RosettaOS",
        "crsy" to "Cryptex1,RosettaVolume",
        "trcr" to "Cryptex1,RosettaTrustCache",
    )

    /**
     * Подделывает Cryptex1 IM4M для этого тождества сборки из собственного
     * тикета Cryptex1 любого настоящего устройства. Форма и подпись шаблона
     * проходят нетронутыми — каждое поле, которое говорит, чей это тикет и за
     * что он ручается, перезаписывается.
     */
    fun forge(identity: Map<String, Any?>, template: ByteArray): ByteArray {
        val ticket = DER.parse(template)
        if (ticket.children?.firstOrNull()?.text != "IM4M") throw shape("not an IM4M")
        val top = ticket.children ?: throw shape("no MANB")
        if (top.size <= 2) throw shape("no MANB")
        val manb = top[2].children?.firstOrNull() ?: throw shape("no MANB")
        val manbBody = manb.children?.firstOrNull()?.children ?: throw shape("no MANB")
        if (manbBody.size <= 1) throw shape("no MANB")
        val payloadSet = manbBody[1]
        val items = payloadSet.children ?: throw shape("no MANP")
        val manp = items.firstOrNull { it.fourCC == "MANP" } ?: throw shape("no MANP")

        patchManp(manp, identity)

        // Собственные компоненты cryptex шаблона принадлежат какой-то другой
        // сборке — выбрасываются и заменяются одной записью на компонент,
        // который эта сборка действительно несёт, с дайджестом прямо из её
        // собственного манифеста.
        val manifest = identity.dict("Manifest") ?: emptyMap()
        val newItems = mutableListOf(manp)
        for ((fourcc, key) in components) {
            val entry = manifest[key].asDict() ?: continue
            val digest = entry["Digest"] as? ByteArray ?: continue
            newItems.add(component(fourcc, digest))
        }
        payloadSet.children = newItems

        return DER.encode(ticket)
    }

    /** Компонент cryptex: `fourcc { SEQUENCE { fourcc, SET { DGST { SEQUENCE { "DGST", digest } } } } }`. */
    private fun component(fourcc: String, digest: ByteArray): DER.Node =
        DER.tagged(
            fourcc,
            listOf(
                DER.ia5(fourcc),
                DER.set(listOf(DER.tagged("DGST", listOf(DER.ia5("DGST"), DER.octets(digest))))),
            ),
        )

    /**
     * Правит поля тождества внутри собственного MANP шаблона на месте — те же
     * поля, что переписывает собственный сервер подписи ChefKiss'а, по той же
     * причине: `fchp`/`clas`/`type`/`styp` говорят, для какого чипа и класса
     * cryptex этот тикет, а `pave`/`vnum` несут строку версии самой сборки.
     */
    private fun patchManp(manp: DER.Node, identity: Map<String, Any?>) {
        val fields = manp.children?.firstOrNull()?.children ?: throw shape("MANP payload")
        if (fields.size <= 1) throw shape("MANP payload")
        val props = fields[1].children ?: throw shape("MANP payload")

        val overrides = mapOf(
            "fchp" to identity.hex64("Cryptex1,ChipID", 0xFF10),
            "clas" to identity.hex64("Cryptex1,ProductClass", 0xF1),
            "type" to identity.hex64("Cryptex1,Type", 1),
            "styp" to identity.hex64("Cryptex1,SubType", 1),
        )
        val version = identity.str("Cryptex1,Version")

        for (prop in props) {
            val pair = prop.children?.firstOrNull()?.children ?: continue
            if (pair.size <= 1) continue
            val name = pair[0].text ?: continue
            val value = overrides[name]
            if (value != null) {
                val encoded = DER.integer(value)
                pair[1].identifier = encoded.identifier
                pair[1].value = encoded.value
            } else if ((name == "pave" || name == "vnum") && version != null) {
                pair[1].identifier = byteArrayOf(0x04)
                pair[1].value = version.toByteArray(Charsets.UTF_8)
            }
        }
    }

    private fun shape(what: String): Failure =
        Failure(L("Шаблон Cryptex1 устроен не так, как ожидалось: %@", what))
}
