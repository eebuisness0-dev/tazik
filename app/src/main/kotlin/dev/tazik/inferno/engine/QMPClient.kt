package dev.tazik.inferno.engine


/**
 * Говорит с QMP-сокетом эмулятора.
 *
 * Это единственный способ спросить работающую машину, чем она занята на самом
 * деле — исполняют ли vCPU команды или стоит на месте, — чего догадка снаружи
 * не ответит.
 */
class QMPClient(private val port: Int = 4556) {

    /** Прогоняет короткую последовательность запросов и докладывает ответы текстом. */
    fun inspect(completion: (String) -> Unit) {
        engineThread("inferno.qmp", 256L * 1024) {
            val sock = Sock()
            try {
                val problem = sock.connect(port)
                if (problem != null) return@engineThread completion("QMP: $problem")

                val lines = mutableListOf("QMP:")

                // Приветственный баннер приходит без запроса.
                val greeting = sock.readSome()
                    ?: return@engineThread completion(L("QMP: сокет открыт, но приветствия нет"))
                lines.add("greeting → " + String(greeting, Charsets.UTF_8).trim().take(200))

                for (command in listOf("qmp_capabilities", "query-status", "query-cpus-fast", "query-vnc")) {
                    if (!sock.write("{\"execute\":\"$command\"}\n".toByteArray(Charsets.UTF_8))) {
                        lines.add(L("%@ → не удалось отправить", command))
                        break
                    }
                    val reply = sock.readSome(64 * 1024)
                    if (reply == null) {
                        lines.add(L("%@ → нет ответа", command))
                        break
                    }
                    if (command != "qmp_capabilities") {
                        lines.add("$command → " + String(reply, Charsets.UTF_8).trim().take(700))
                    }
                }

                completion(lines.joinToString("\n  "))
            } finally {
                sock.close()
            }
        }
    }

    /** Просит машину выключиться.
     *
     * Это единственный безопасный способ её остановить. `quit` разворачивает
     * главный цикл QEMU, который сбрасывает блочный слой на файлы. Убийство
     * процесса вместо этого оставляет метаданные qcow2 наполовину записанными,
     * и диск гостя тогда расходится со счётчиками повтора Secure Enclave —
     * расхождение, на которое гость отвечает паникой SEP при следующей
     * загрузке.
     */
    fun quit(completion: (String) -> Unit) {
        engineThread("inferno.qmp", 256L * 1024) {
            val sock = Sock()
            try {
                val problem = sock.connect(port)
                if (problem != null) return@engineThread completion(L("Выключение: %@", problem))
                if (sock.readSome() == null) {
                    return@engineThread completion(L("Выключение: приветствия от QMP нет"))
                }
                if (!sock.write("{\"execute\":\"qmp_capabilities\"}\n".toByteArray(Charsets.UTF_8)) ||
                    sock.readSome() == null ||
                    !sock.write("{\"execute\":\"quit\"}\n".toByteArray(Charsets.UTF_8))
                ) {
                    return@engineThread completion(L("Выключение: команда не ушла"))
                }
                // Ответа может не быть вовсе: QEMU исполняет quit и уходит.
                sock.readSome()
                completion(L("Выключение: команда отправлена, машина сбрасывает диски на файлы"))
            } finally {
                sock.close()
            }
        }
    }
}
