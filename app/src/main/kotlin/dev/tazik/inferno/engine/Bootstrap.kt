package dev.tazik.inferno.engine

import java.io.File

/**
 * Готовит папку документов при первом запуске.
 *
 * Создание дерева каталогов (и записки в нём) даёт образам гостя очевидное
 * место, куда их класть.
 */
object Bootstrap {

    fun prepareDocuments() {
        val documents = VMConfig.documents

        val directories = listOf(
            "InfernoData",
            "InfernoData/Restore",
            "InfernoData/Restore/Firmware",
            "InfernoData/Restore/Firmware/all_flash",
        )
        for (relative in directories) {
            val directory = File(documents, relative)
            if (!directory.isDirectory) directory.mkdirs()
        }

        // Черновое пространство для быстрых передач. Создаётся здесь, чтобы
        // существовать до построения командной строки эмулятора; машина берёт
        // его только когда файл уже на месте.
        VMConfig.ensureTransferImage()

        val readme = File(documents, L("КУДА КЛАСТЬ ФАЙЛЫ.txt"))
        if (!readme.isFile) {
            try {
                readme.writeText(note)
            } catch (_: Throwable) {
            }
        }
    }

    private val note = """
        Inferno — файлы гостевой системы
        ================================

        Разложите содержимое комплекта прямо в эту папку:

          AppleSEPROM-Cebu-B1
          InfernoData/root.qcow2        (или root — сырой образ)
          InfernoData/firmware
          InfernoData/syscfg
          InfernoData/ctrl_bits
          InfernoData/nvram
          InfernoData/effaceable
          InfernoData/panic_log
          InfernoData/sep_nvram
          InfernoData/sep_ssc
          InfernoData/root_ticket.der
          InfernoData/sep-firmware.n104.RELEASE.new.img4
          InfernoData/Restore/kernelcache.release.iphone12b
          InfernoData/Restore/Firmware/038-44135-124.dmg.trustcache
          InfernoData/Restore/Firmware/all_flash/DeviceTree.n104ap.im4p

        Пустые папки уже созданы — файлы можно класть прямо в них.

        Про образ диска: берите root.qcow2, а не сырой root. Сырой занимает 34 ГБ
        номинально при ~9 ГБ данных и держится на разрежённости файла, которую
        копирование на телефон теряет. qcow2 занимает свои реальные гигабайты при
        любом способе переноса.

        Этот файл можно удалить.
    """.trimIndent()
}
