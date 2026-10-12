package de.bmclink

/**
 * Minimaler DHCP-Server-Kern (RFC 2131) ohne Android-Abhängigkeiten,
 * damit er auch auf dem PC getestet werden kann.
 */
class DhcpCore(
    private val serverIp: Int,
    private val prefix: Int,
    private val poolStart: Int,
    private val poolEnd: Int,
    private val leaseSeconds: Int = 86400,
    private val log: (String) -> Unit = {},
) {
    private val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
    private val byMac = LinkedHashMap<String, Int>()

    /** Ausgehendes Paket inkl. Ziel. */
    class Reply(val data: ByteArray, val destIp: Int, val broadcast: Boolean)

    /** Callback, wenn ein Gerät eine Adresse bestätigt bekommen hat. */
    var onLease: (mac: String, ip: Int, hostname: String?) -> Unit = { _, _, _ -> }

    fun handle(pkt: ByteArray, len: Int): Reply? {
        if (len < 240) return null
        if (pkt[0].toInt() != 1) return null                        // nur BOOTREQUEST
        if (pkt[1].toInt() != 1 || pkt[2].toInt() != 6) return null // Ethernet, MAC-Länge 6
        if (u32(pkt, 236) != 0x63825363) return null                // Magic Cookie

        val opts = parseOptions(pkt, len)
        val type = opts[53]?.getOrNull(0)?.toInt() ?: return null
        val mac = (0 until 6).joinToString(":") { "%02x".format(pkt[28 + it].toInt() and 255) }
        val ciaddr = u32(pkt, 12)
        val requested = opts[50]?.takeIf { it.size == 4 }?.let { u32(it, 0) }
        val serverId = opts[54]?.takeIf { it.size == 4 }?.let { u32(it, 0) }
        val hostname = opts[12]?.let { String(it, Charsets.US_ASCII).trim { c -> c <= ' ' } }

        return when (type) {
            1 -> { // DISCOVER
                val ip = allocate(mac, requested) ?: run { log("Pool voll – keine Adresse für $mac"); return null }
                log("DISCOVER von $mac${hostname?.let { " ($it)" } ?: ""} → OFFER ${intToIp(ip)}")
                build(pkt, 2, ip)
            }
            3 -> { // REQUEST
                if (serverId != null && serverId != serverIp) {
                    log("REQUEST von $mac gilt einem anderen DHCP-Server (${intToIp(serverId)})")
                    return null
                }
                val want = requested ?: ciaddr
                val ok = want != 0 && inPool(want) && (byMac[mac] == want || (!byMac.containsValue(want)))
                if (ok) {
                    byMac[mac] = want
                    log("REQUEST von $mac → ACK ${intToIp(want)}")
                    onLease(mac, want, hostname)
                    build(pkt, 5, want, unicastTo = if (ciaddr != 0) ciaddr else 0)
                } else {
                    log("REQUEST von $mac für ${intToIp(want)} → NAK")
                    build(pkt, 6, 0)
                }
            }
            4, 7 -> { // DECLINE / RELEASE
                log((if (type == 4) "DECLINE" else "RELEASE") + " von $mac")
                byMac.remove(mac)
                null
            }
            8 -> { // INFORM
                build(pkt, 5, 0, unicastTo = ciaddr)
            }
            else -> null
        }
    }

    private fun inPool(ip: Int) =
        ip.toUInt() >= poolStart.toUInt() && ip.toUInt() <= poolEnd.toUInt() && ip != serverIp

    private fun allocate(mac: String, requested: Int?): Int? {
        byMac[mac]?.let { return it }
        if (requested != null && inPool(requested) && !byMac.containsValue(requested)) {
            byMac[mac] = requested; return requested
        }
        var ip = poolStart
        while (ip.toUInt() <= poolEnd.toUInt()) {
            if (ip != serverIp && !byMac.containsValue(ip)) {
                byMac[mac] = ip; return ip
            }
            ip++
        }
        return null
    }

    private fun build(req: ByteArray, msgType: Int, yiaddr: Int, unicastTo: Int = 0): Reply {
        val out = ByteArray(548)
        out[0] = 2; out[1] = 1; out[2] = 6; out[3] = 0
        System.arraycopy(req, 4, out, 4, 4)         // xid
        System.arraycopy(req, 10, out, 10, 2)       // flags
        System.arraycopy(req, 12, out, 12, 4)       // ciaddr
        put32(out, 16, yiaddr)
        put32(out, 20, serverIp)                    // siaddr
        System.arraycopy(req, 24, out, 24, 4)       // giaddr
        System.arraycopy(req, 28, out, 28, 16)      // chaddr
        put32(out, 236, 0x63825363)
        var p = 240
        fun opt(code: Int, vararg data: Byte) {
            out[p++] = code.toByte(); out[p++] = data.size.toByte()
            for (b in data) out[p++] = b
        }
        fun opt32(code: Int, v: Int) = opt(code, (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        opt(53, msgType.toByte())
        opt32(54, serverIp)
        if (msgType != 6) {
            if (yiaddr != 0) {
                opt32(51, leaseSeconds)
                opt32(58, leaseSeconds / 2)
                opt32(59, leaseSeconds / 8 * 7)
            }
            opt32(1, mask)
            opt32(3, serverIp)                      // Gateway = Handy (schadet nicht, manche BMCs wollen eins)
            opt32(28, (serverIp and mask) or mask.inv())
        }
        out[p++] = 255.toByte()
        val size = maxOf(p, 300)
        val data = out.copyOf(size)
        return if (unicastTo != 0) Reply(data, unicastTo, false) else Reply(data, -1, true)
    }

    companion object {
        fun parseOptions(pkt: ByteArray, len: Int): Map<Int, ByteArray> {
            val m = HashMap<Int, ByteArray>()
            var i = 240
            while (i < len) {
                val code = pkt[i].toInt() and 255
                if (code == 0) { i++; continue }
                if (code == 255) break
                if (i + 1 >= len) break
                val l = pkt[i + 1].toInt() and 255
                if (i + 2 + l > len) break
                m[code] = pkt.copyOfRange(i + 2, i + 2 + l)
                i += 2 + l
            }
            return m
        }

        fun u32(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or
                ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)

        fun put32(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
        }
    }
}
