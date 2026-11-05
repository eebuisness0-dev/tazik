package dev.tazik.inferno.engine

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Блокирующий loopback-сокет — порт `Sock.swift`.
 *
 * В Swift это были BSD-сокеты, чтобы видеть `errno` вместо неопределённого
 * состояния высокоуровневого стека. На Android тот же результат даёт
 * `java.net.Socket` с таймаутами и `SO_NOSIGPIPE` не нужен вовсе: SIGPIPE
 * здесь не убивает процесс.
 */
class Sock(private var socket: Socket? = null) {

    val isOpen: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Подключается к 127.0.0.1:[port]. Возвращает null при успехе или описание
     * ошибки — как в Swift-версии.
     */
    fun connect(port: Int, timeout: Double = 20.0): String? {
        close()
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.soTimeout = (timeout * 1000).toInt()
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), (timeout * 1000).toInt())
            socket = s
            null
        } catch (e: IOException) {
            close()
            "connect(127.0.0.1:$port): ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Читает ровно [count] байт или возвращает null. */
    fun readExactly(count: Int): ByteArray? {
        if (count <= 0) return ByteArray(0)
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val n = try {
                socket?.getInputStream()?.read(out, filled, count - filled) ?: -1
            } catch (_: SocketTimeoutException) {
                return null
            } catch (_: IOException) {
                return null
            }
            if (n <= 0) return null
            filled += n
        }
        return out
    }

    /** Читает сколько есть, не больше [max]. */
    fun readSome(max: Int = 16 * 1024): ByteArray? {
        val out = ByteArray(max)
        val n = try {
            socket?.getInputStream()?.read(out, 0, max) ?: -1
        } catch (_: SocketTimeoutException) {
            return null
        } catch (_: IOException) {
            return null
        }
        if (n <= 0) return null
        return out.copyOfRange(0, n)
    }

    fun write(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return try {
            val out = socket?.getOutputStream() ?: return false
            out.write(bytes)
            out.flush()
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Заставляет `readSome` ждать сколько угодно. Для читателя, чьё молчание
     * нормально (консоль, в которой ничего не происходит), таймаут приёма — не
     * ошибка, а ловушка: первая тишина навсегда завершает цикл.
     */
    fun waitIndefinitely() {
        try {
            socket?.soTimeout = 0
        } catch (_: IOException) {
            // Сокет уже закрыт — ждать нечего.
        }
    }

    /** Таймаут приёма в миллисекундах; 0 — бесконечно. */
    fun setReadTimeout(millis: Int) {
        try {
            socket?.soTimeout = millis
        } catch (_: IOException) {
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }
}

/** Минимальный слушатель на loopback: нужен, где Swift открывал BSD-сокет. */
class LoopbackListener(port: Int = 0) {
    private val server = java.net.ServerSocket()
    val port: Int

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        this.port = server.localPort
    }

    fun accept(timeout: Double): Socket? {
        return try {
            server.soTimeout = (timeout * 1000).toInt()
            server.accept()
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    fun close() {
        try {
            server.close()
        } catch (_: IOException) {
        }
    }
}
