package com.trae.ridreceiver

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object MqttConfig {
    const val HOST = "broker.emqx.io"
    const val PORT = 8883          // MQTT over TLS
    const val TOPIC = "rid/trae-demo/drones"
    const val WSS_URL = "wss://broker.emqx.io:8084/mqtt" // 小程序查看端使用
}

/**
 * 极简 MQTT 3.1.1 发布端（QoS0，TLS）。
 * 所有 socket I/O 都在后台线程执行，主线程只入队。
 */
class MqttPublisher(
    private val host: String = MqttConfig.HOST,
    private val port: Int = MqttConfig.PORT,
    private val topic: String = MqttConfig.TOPIC,
    private val clientId: String = "rid-" + UUID.randomUUID().toString().replace("-", "").take(10)
) {

    private val queue = ArrayBlockingQueue<String>(8)

    @Volatile
    private var socket: SSLSocket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var running = false

    @Volatile
    var connected: Boolean = false
        private set

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "mqtt-publisher").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        connected = false
        queue.clear()
        closeQuietly()
        thread?.interrupt()
        thread = null
    }

    /** 非阻塞入队；队列满时丢弃最旧的一条 */
    fun publish(payload: String) {
        if (!running) return
        if (!queue.offer(payload)) {
            queue.poll()
            queue.offer(payload)
        }
    }

    private fun loop() {
        while (running) {
            try {
                open()
                connected = true
                var lastPing = System.currentTimeMillis()
                while (running) {
                    val payload = queue.poll(200, TimeUnit.MILLISECONDS)
                    if (payload != null) {
                        write(publishPacket(payload))
                    }
                    try {
                        val header = readPacket()
                        if (header < 0) break
                    } catch (_: SocketTimeoutException) {
                        // 空闲
                    }
                    if (System.currentTimeMillis() - lastPing > 30000) {
                        write(byteArrayOf(0xC0.toByte(), 0x00))
                        lastPing = System.currentTimeMillis()
                    }
                }
            } catch (_: Exception) {
                // 断线重连
            } finally {
                connected = false
                closeQuietly()
            }
            if (running) {
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun open() {
        val raw = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        raw.connect(InetSocketAddress(host, port), 8000)
        raw.soTimeout = 500
        try {
            val params = raw.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            raw.sslParameters = params
        } catch (_: Exception) {
        }
        raw.startHandshake()
        socket = raw
        input = raw.inputStream
        output = raw.outputStream
        write(connectPacket())
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }

    private fun write(bytes: ByteArray) {
        val out = output ?: throw IllegalStateException("not connected")
        synchronized(this) {
            out.write(bytes)
            out.flush()
        }
    }

    /** @return 报文类型；读到 EOF 返回 -1 */
    private fun readPacket(): Int {
        val ins = input ?: throw IllegalStateException("not connected")
        val header = ins.read()
        if (header < 0) return -1

        var multiplier = 1
        var remaining = 0
        var digit: Int
        do {
            digit = ins.read()
            if (digit < 0) return -1
            remaining += (digit and 0x7F) * multiplier
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) throw IllegalStateException("bad remaining length")
        } while (digit and 0x80 != 0)

        var left = remaining
        val buf = ByteArray(4096)
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(buf.size, left))
            if (n < 0) return -1
            left -= n
        }
        return header shr 4
    }

    private fun connectPacket(): ByteArray {
        val variable = mqttString("MQTT") + byteArrayOf(
            0x04, // protocol level 4 (MQTT 3.1.1)
            0x02, // clean session
            0x00, 0x3C // keepalive 60s
        )
        val body = variable + mqttString(clientId)
        return byteArrayOf(0x10) + encodeRemaining(body.size) + body
    }

    private fun publishPacket(payload: String): ByteArray {
        val body = mqttString(topic) + payload.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x30) + encodeRemaining(body.size) + body
    }

    private fun mqttString(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return byteArrayOf(
            ((b.size shr 8) and 0xFF).toByte(),
            (b.size and 0xFF).toByte()
        ) + b
    }

    private fun encodeRemaining(length: Int): ByteArray {
        var l = length
        val out = ArrayList<Byte>(4)
        do {
            var b = l % 128
            l /= 128
            if (l > 0) b = b or 0x80
            out.add(b.toByte())
        } while (l > 0)
        return out.toByteArray()
    }
}
