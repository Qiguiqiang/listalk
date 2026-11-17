package com.qi.phoneaudiobridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire protocol shared with the PC app. See PROTOCOL.md. */
object Protocol {
    const val PORT = 47731
    const val MAGIC = 0x5041
    const val HEADER_SIZE = 16

    const val TYPE_AUDIO = 0
    const val TYPE_DISCOVER = 1
    const val TYPE_DISCOVER_RESP = 2
    const val TYPE_HELLO = 3
    const val TYPE_HELLO_ACK = 4
    const val TYPE_PING = 5
    const val TYPE_PONG = 6
    const val TYPE_RATE_REQ = 7
    const val TYPE_RATE_ACK = 8
    const val TYPE_BYE = 9
    /** PC presence beacon, broadcast every 1 s: JSON {name, os, deviceId, tokenHash}. */
    const val TYPE_BEACON = 11

    /** Wire-protocol version exchanged in HELLO/HELLO_ACK (unified release versioning, v2). */
    const val PROTO_VERSION = 2

    // ---- Discovery transports (v2 addition, see PROTOCOL.md "Discovery") ----
    /** mDNS/Bonjour service type advertised by PCs and browsed by phones. */
    const val MDNS_SERVICE_TYPE = "_airbridge._udp"
    /**
     * BLE service UUID PCs advertise for proximity presence (audio stays on
     * WiFi). Canonical value, generated deterministically as the first
     * 128 bits of SHA-256("AirBridge-BLE-Service-v2") formatted as a UUID —
     * documented in PROTOCOL.md; every implementation must use it verbatim.
     */
    const val BLE_SERVICE_UUID = "9072f6d1-ec44-0c52-ad20-dc70f9bc5a38"

    const val CODEC_PCM16 = 0
    const val CODEC_OPUS_RESERVED = 1 // v2 only, never emitted in v1

    const val RATE_48K = 0
    const val RATE_24K = 1
    const val RATE_16K = 2

    fun rateHz(rateCode: Int): Int = when (rateCode) {
        RATE_24K -> 24000
        RATE_16K -> 16000
        else -> 48000
    }

    fun rateCodeForHz(hz: Int): Int = when (hz) {
        24000 -> RATE_24K
        16000 -> RATE_16K
        else -> RATE_48K
    }

    /** 10 ms frame length in samples for a rate code. */
    fun frameSamples(rateCode: Int): Int = rateHz(rateCode) / 100

    fun flags(codec: Int, rateCode: Int): Int = (codec and 0x03) or ((rateCode and 0x07) shl 2)

    data class Header(
        val type: Int,
        val flags: Int,
        val seq: Int,
        val timestampUs: Long
    ) {
        val codec: Int get() = flags and 0x03
        val rateCode: Int get() = (flags shr 2) and 0x07
    }

    fun pack(type: Int, flags: Int, seq: Int, timestampUs: Long, payload: ByteArray, payloadLen: Int = payload.size): ByteArray {
        val out = ByteArray(HEADER_SIZE + payloadLen)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(MAGIC.toShort())
        bb.put(type.toByte())
        bb.put(flags.toByte())
        bb.putInt(seq)
        bb.putLong(timestampUs)
        System.arraycopy(payload, 0, out, HEADER_SIZE, payloadLen)
        return out
    }

    fun unpack(data: ByteArray, len: Int): Header? {
        if (len < HEADER_SIZE) return null
        val bb = ByteBuffer.wrap(data, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        if ((bb.short.toInt() and 0xFFFF) != MAGIC) return null
        val type = bb.get().toInt() and 0xFF
        val flags = bb.get().toInt() and 0xFF
        val seq = bb.int
        val ts = bb.long
        return Header(type, flags, seq, ts)
    }

    fun nowUs(): Long = System.nanoTime() / 1000L

    /**
     * Pairing verification code, mirroring pc/phone_audio_pc.py
     * verify_code_for(): 6 digits from SHA-256("$token:$phoneDeviceId").
     * The phone uses it to CROSS-CHECK the code in the PC's HELLO_ACK
     * instead of trusting it blindly.
     */
    fun verifyCodeFor(token: String, phoneDeviceId: String): String {
        val hex = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$token:$phoneDeviceId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val n = java.math.BigInteger(hex, 16)
        return (n.mod(java.math.BigInteger.valueOf(900000)).toInt() + 100000).toString()
    }
}
