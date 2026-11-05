package dev.tazik.inferno.engine

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Прошивка SEP, приготовленная для эмулируемого Secure Enclave.
 *
 * Apple поставляет её зашифрованной. Ключ больше не секрет — он публикуется
 * для каждой сборки на The Apple Wiki, и человек, делающий восстановление,
 * смотрит его для своей прошивки и передаёт сюда. С открытым payload она
 * пакуется заново как `IMG4`, на этот раз под поручительством подделанного
 * тикета SEP, — это и принимает пропатченный SEP ROM эмулятора.
 *
 * Сверено с `img4lib`, инструментом из руководства ChefKiss'а:
 * расшифрованный payload и готовый контейнер выходят байт в байт теми же.
 */
object SEPFirmware {

    enum class FailureKind { BAD_KEY, NOT_BLOCK_SIZED, DECRYPTION }

    class Failure(message: String, val kind: FailureKind) : Exception(message)

    /**
     * IV и ключ, как их пишет вики: одна строка hex, IV первым.
     */
    class Key private constructor(val iv: ByteArray, val key: ByteArray) {

        companion object {
            /**
             * Принимает то, что вставил человек. Пробелы и ведущий `0x`
             * игнорируются, потому что именно так это копируют.
             */
            fun parse(text: String): Key {
                val cleaned = text.lowercase()
                    .replace("0x", "")
                    .filter { it in "0123456789abcdef" }
                if (cleaned.length != 96) {
                    throw Failure(
                        L("Ключ SEP должен быть 96 шестнадцатеричных цифр: 32 на IV и 64 на ключ"),
                        FailureKind.BAD_KEY,
                    )
                }
                val bytes = ByteArray(48)
                for (i in 0 until 48) {
                    bytes[i] = cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                return Key(bytes.copyOfRange(0, 16), bytes.copyOfRange(16, 48))
            }
        }
    }

    /**
     * Расшифровывает payload `IM4P` алгоритмом AES-256-CBC без выравнивания:
     * прошивка — целое число блоков, и каждый её байт — это прошивка.
     */
    fun decrypt(payload: ByteArray, key: Key): ByteArray {
        if (payload.size % 16 != 0) {
            throw Failure(
                L("Прошивка SEP не кратна 16 байтам (%d) — это не шифрованный payload", payload.size),
                FailureKind.NOT_BLOCK_SIZED,
            )
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.key, "AES"), IvParameterSpec(key.iv))
            cipher.doFinal(payload)
        } catch (e: Throwable) {
            throw Failure(
                L("Расшифровка SEP не удалась (%d)", 0),
                FailureKind.DECRYPTION,
            )
        }
    }

    /**
     * Весь шаг: от того, что несёт IPSW, до того, что загружает машина.
     *
     * Пересобранный payload — это `rsep`, restore SEP, и он не несёт ни версии,
     * ни свойств, которые были у шифрованного оригинала: они описывали
     * шифрование, а его больше нет.
     */
    fun rebuild(encrypted: ByteArray, key: Key, ticket: ByteArray, type: String = "rsep"): ByteArray {
        val payload = IMG4.readIM4P(encrypted)
        val plain = decrypt(payload.data, key)
        return IMG4.make(
            IMG4.Payload(type = type, version = "none", data = plain, extras = emptyList()),
            ticket,
        )
    }
}
