package de.bmclink

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class IpmiException(msg: String) : Exception(msg)

/** Eine LAN-Konfiguration, wie sie der BMC per IPMI meldet. */
data class LanInfo(
    val channel: Int,
    val source: String,
    val ip: String,
    val mask: String,
    val gateway: String,
    val mac: String,
    val ipVersion: String?,
) {
    override fun toString() =
        "Kanal $channel: $ip / $mask, GW $gateway ($source), MAC $mac" + (ipVersion?.let { " · $it" } ?: "")
}

/**
 * Minimaler IPMI-2.0-Client (RMCP+, Cipher-Suite 3 = HMAC-SHA1/AES-128, Fallback 17 = HMAC-SHA256/AES-128).
 * Kann über IPv4 und IPv6 (auch link-local fe80::…%eth0) sprechen.
 * [bind] erlaubt es, den Socket an ein Android-Network zu binden.
 */
class Ipmi(
    host: String,
    private val user: String = "admin",
    password: String = "admin",
    private val timeoutMs: Int = 1500,
    private val retries: Int = 3,
    private val bind: (DatagramSocket) -> Unit = {},
) : AutoCloseable {

    private val addr: InetSocketAddress = InetSocketAddress(resolve(host), 623)
    private val sock = DatagramSocket().also { bind(it); it.soTimeout = 300 }
    private val kuid = password.toByteArray().copyOf(20)
    private val uname = user.toByteArray()
    private val rnd = SecureRandom()

    private var hashAlg = "HmacSHA1"
    private var icvLen = 12
    private var sid = 0          // Session-ID des BMC
    private var csid = 0         // unsere Session-ID
    private var seq = 0
    private var rqSeq = 0
    private var k1 = ByteArray(0)
    private var aesKey = ByteArray(0)
    var active = false
        private set
    var suite = 0
        private set

    // ------------------------------------------------------------------ Transport

    private fun <T> xfer(pkt: ByteArray, check: (ByteArray) -> T?): T {
        val buf = ByteArray(4096)
        repeat(retries) {
            sock.send(DatagramPacket(pkt, pkt.size, addr))
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val r = check(buf.copyOf(p.length))
                if (r != null) return r
            }
        }
        throw IpmiException("${addr.address.hostAddress} antwortet nicht (Zeitüberschreitung)")
    }

    private fun mac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance(hashAlg)
        m.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, hashAlg))
        for (p in parts) m.update(p)
        return m.doFinal()
    }

    private fun lan15(netfn: Int, cmd: Int, data: ByteArray): Pair<Int, ByteArray> {
        val hdr = byteArrayOf(0x20, (netfn shl 2).toByte())
        val body = byteArrayOf(0x81.toByte(), 0, cmd.toByte()) + data
        val msg = hdr + chk(hdr) + body + chk(body)
        val pkt = RMCP + byteArrayOf(0) + ByteArray(8) + byteArrayOf(msg.size.toByte()) + msg
        return xfer(pkt) { d ->
            if (d.size >= 21 && d.startsWith(RMCP) && d[4].toInt() == 0 && u(d[19]) == cmd)
                u(d[20]) to d.copyOfRange(21, d.size - 1) else null
        }
    }

    private fun v2(ptype: Int, payload: ByteArray): ByteArray {
        val pkt = RMCP + byteArrayOf(0x06, ptype.toByte()) + ByteArray(8) + le16(payload.size) + payload
        return xfer(pkt) { d ->
            if (d.size >= 16 && d.startsWith(RMCP) && d[4].toInt() == 6 && (u(d[5]) and 0x3F) == ptype + 1) {
                val ln = u(d[14]) or (u(d[15]) shl 8)
                d.copyOfRange(16, minOf(d.size, 16 + ln))
            } else null
        }
    }

    // ------------------------------------------------------------------ Sitzung

    fun open(): Ipmi {
        try {
            return openInner()
        } catch (e: Exception) {
            sock.close()
            throw e
        }
    }

    private fun openInner(): Ipmi {
        try {
            lan15(0x06, 0x38, byteArrayOf(0x8E.toByte(), PRIV_ADMIN.toByte()))   // Get Channel Auth Caps
        } catch (e: IpmiException) {
            throw IpmiException("${addr.address.hostAddress} antwortet nicht auf IPMI (UDP 623).")
        }
        var err: IpmiException? = null
        for (s in intArrayOf(3, 17)) {
            try {
                openSuite(s)
                return this
            } catch (e: IpmiException) {
                err = e
                if (!e.message.orEmpty().contains("Cipher")) throw e
            }
        }
        throw err!!
    }

    private fun openSuite(s: Int) {
        val auth: Int
        val integ: Int
        if (s == 3) {
            auth = 1; integ = 1; hashAlg = "HmacSHA1"; icvLen = 12
        } else {
            auth = 3; integ = 4; hashAlg = "HmacSHA256"; icvLen = 16
        }
        csid = rnd.nextInt() or 1
        val tag: Byte = 0x42
        val req = byteArrayOf(tag, PRIV_ADMIN.toByte(), 0, 0) + le32(csid) +
                byteArrayOf(0, 0, 0, 8, auth.toByte(), 0, 0, 0) +
                byteArrayOf(1, 0, 0, 8, integ.toByte(), 0, 0, 0) +
                byteArrayOf(2, 0, 0, 8, 1, 0, 0, 0)
        val r = v2(0x10, req)
        if (r.size < 12) throw IpmiException("Open Session: Antwort zu kurz")
        if (r[1].toInt() != 0) throw IpmiException("Open Session abgelehnt: ${rakpText(u(r[1]))}")
        val sidB = r.copyOfRange(8, 12)
        sid = le32(sidB)
        val csidB = le32(csid)

        val rm = ByteArray(16).also { rnd.nextBytes(it) }
        val role = 0x10 or PRIV_ADMIN
        val tail = byteArrayOf(role.toByte(), uname.size.toByte()) + uname
        val rakp1 = byteArrayOf(tag, 0, 0, 0) + sidB + rm + byteArrayOf(role.toByte(), 0, 0, uname.size.toByte()) + uname
        val r2 = try {
            v2(0x12, rakp1)
        } catch (e: IpmiException) {
            throw IpmiException("Keine Antwort bei der Anmeldung – Benutzername falsch?")
        }
        if (r2.size < 2) throw IpmiException("RAKP2: Antwort zu kurz")
        if (r2[1].toInt() != 0) throw IpmiException("Anmeldung fehlgeschlagen: ${rakpText(u(r2[1]))}")
        val rc = r2.copyOfRange(8, 24)
        val guid = r2.copyOfRange(24, 40)
        val code = r2.copyOfRange(40, r2.size)
        val exp = mac(kuid, csidB, sidB, rm, rc, guid, tail)
        if (code.size < exp.size || !MessageDigest.isEqual(exp, code.copyOf(exp.size)))
            throw IpmiException("Anmeldung fehlgeschlagen: Passwort falsch")
        val sik = mac(kuid, rm, rc, tail)
        val n = exp.size
        k1 = mac(sik, ByteArray(n) { 1 })
        aesKey = mac(sik, ByteArray(n) { 2 }).copyOf(16)

        val rakp3 = byteArrayOf(tag, 0, 0, 0) + sidB + mac(kuid, rc, csidB, tail)
        val r4 = v2(0x14, rakp3)
        if (r4.size < 2) throw IpmiException("RAKP4: Antwort zu kurz")
        if (r4[1].toInt() != 0) throw IpmiException("Anmeldung fehlgeschlagen: ${rakpText(u(r4[1]))}")
        val icv = mac(sik, rm, sidB, guid).copyOf(icvLen)
        if (r4.size < 8 + icvLen || !MessageDigest.isEqual(icv, r4.copyOfRange(8, 8 + icvLen)))
            throw IpmiException("Sitzungsschlüssel stimmt nicht (RAKP4)")
        seq = 0
        suite = s
        active = true
    }

    /** Sendet einen IPMI-Befehl in der Sitzung. Rückgabe: Completion Code + Daten. */
    fun raw(netfn: Int, cmd: Int, data: ByteArray = ByteArray(0)): Pair<Int, ByteArray> {
        if (!active) throw IpmiException("keine Sitzung")
        rqSeq = (rqSeq + 1) and 0x3F
        seq = if (seq == -1) 1 else seq + 1
        val mySeq = rqSeq
        val hdr = byteArrayOf(0x20, (netfn shl 2).toByte())
        val body = byteArrayOf(0x81.toByte(), (mySeq shl 2).toByte(), cmd.toByte()) + data
        val msg = hdr + chk(hdr) + body + chk(body)
        val padN = (16 - (msg.size + 1) % 16) % 16
        val plain = msg + ByteArray(padN) { (it + 1).toByte() } + byteArrayOf(padN.toByte())
        val iv = ByteArray(16).also { rnd.nextBytes(it) }
        val payload = iv + aes(Cipher.ENCRYPT_MODE, iv, plain)
        var sess = byteArrayOf(0x06, 0xC0.toByte()) + le32(sid) + le32(seq) + le16(payload.size) + payload
        val pad = (4 - (sess.size + 2) % 4) % 4
        sess = sess + ByteArray(pad) { 0xFF.toByte() } + byteArrayOf(pad.toByte(), 0x07)
        val pkt = RMCP + sess + mac(k1, sess).copyOf(icvLen)
        val rNetfn = netfn or 1

        return xfer(pkt) { d ->
            if (d.size < 32 || !d.startsWith(RMCP) || d[4].toInt() != 6 || (u(d[5]) and 0x3F) != 0) return@xfer null
            if (le32(d.copyOfRange(6, 10)) != csid) return@xfer null
            val ln = u(d[14]) or (u(d[15]) shl 8)
            var p = d.copyOfRange(16, minOf(d.size, 16 + ln))
            if (u(d[5]) and 0x40 != 0) {
                val end = d.size - icvLen
                if (end < 4) return@xfer null
                val calc = mac(k1, d.copyOfRange(4, end)).copyOf(icvLen)
                if (!MessageDigest.isEqual(calc, d.copyOfRange(end, d.size))) return@xfer null
            }
            if (u(d[5]) and 0x80 != 0) {
                if (p.size < 32 || p.size % 16 != 0) return@xfer null
                val dec = try {
                    aes(Cipher.DECRYPT_MODE, p.copyOf(16), p.copyOfRange(16, p.size))
                } catch (_: Exception) {
                    return@xfer null
                }
                val pl = u(dec[dec.size - 1])
                if (pl + 1 > dec.size) return@xfer null
                p = dec.copyOf(dec.size - pl - 1)
            }
            if (p.size < 8 || (u(p[1]) shr 2) != rNetfn || (u(p[4]) shr 2) != mySeq || u(p[5]) != cmd) return@xfer null
            u(p[6]) to p.copyOfRange(7, p.size - 1)
        }
    }

    fun cmd(netfn: Int, cmd: Int, data: ByteArray, what: String): ByteArray {
        val (cc, d) = raw(netfn, cmd, data)
        if (cc != 0) throw IpmiException("$what: Fehlercode 0x%02x (%s)".format(cc, CC_TEXT[cc] ?: "unbekannt"))
        return d
    }

    private fun aes(mode: Int, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(mode, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    override fun close() {
        if (active) {
            try {
                raw(0x06, 0x3C, le32(sid))
            } catch (_: Exception) {
            }
            active = false
        }
        sock.close()
    }

    // ------------------------------------------------------------------ LAN-Konfiguration

    /** Alle Kanäle mit Medium 802.3 (LAN). */
    fun lanChannels(): List<Int> {
        val list = ArrayList<Int>()
        for (ch in 1..11) {
            try {
                val (cc, d) = raw(0x06, 0x42, byteArrayOf(ch.toByte()))
                if (cc == 0 && d.size >= 2 && (u(d[1]) and 0x7F) == 0x04) list += ch
            } catch (_: IpmiException) {
            }
        }
        return list.ifEmpty { listOf(1) }
    }

    private fun getParam(ch: Int, p: Int): ByteArray? = try {
        val d = cmd(0x0C, 0x02, byteArrayOf(ch.toByte(), p.toByte(), 0, 0), "LAN-Parameter $p lesen")
        d.copyOfRange(1, d.size)
    } catch (_: IpmiException) {
        null
    }

    private fun setParam(ch: Int, p: Int, data: ByteArray, what: String) {
        cmd(0x0C, 0x01, byteArrayOf(ch.toByte(), p.toByte()) + data, what)
    }

    fun lanInfo(ch: Int): LanInfo {
        val src = getParam(ch, 4)
        val v6 = getParam(ch, 51)
        return LanInfo(
            channel = ch,
            source = when (src?.getOrNull(0)?.let { u(it) and 0x0F }) {
                1 -> "statisch"; 2 -> "DHCP"; 3 -> "BIOS"; 4 -> "andere"; 0 -> "unbekannt"; else -> "?"
            },
            ip = ip4(getParam(ch, 3)),
            mask = ip4(getParam(ch, 6)),
            gateway = ip4(getParam(ch, 12)),
            mac = getParam(ch, 5)?.takeIf { it.size >= 6 }?.copyOf(6)?.joinToString(":") { "%02x".format(it) } ?: "?",
            ipVersion = v6?.getOrNull(0)?.let { mapOf(0 to "nur IPv4", 1 to "nur IPv6", 2 to "IPv4 + IPv6")[u(it)] },
        )
    }

    fun setStatic(ch: Int, ip: String, mask: String, gw: String) {
        val ipB = ip4Bytes(ip) ?: throw IpmiException("IP ungültig: $ip")
        val maskB = ip4Bytes(mask) ?: throw IpmiException("Maske ungültig: $mask")
        val gwB = ip4Bytes(gw) ?: throw IpmiException("Gateway ungültig: $gw")
        try {
            setParam(ch, 0, byteArrayOf(1), "Set in Progress")
        } catch (_: IpmiException) {
        }
        try {
            setParam(ch, 4, byteArrayOf(1), "Adressquelle auf statisch")
            setParam(ch, 3, ipB, "IP-Adresse")
            setParam(ch, 6, maskB, "Subnetzmaske")
            setParam(ch, 12, gwB, "Gateway")
        } finally {
            try {
                setParam(ch, 0, byteArrayOf(0), "Set complete")
            } catch (_: IpmiException) {
            }
        }
    }

    fun setDhcp(ch: Int) = setParam(ch, 4, byteArrayOf(2), "Adressquelle auf DHCP")

    companion object {
        val RMCP = byteArrayOf(0x06, 0x00, 0xFF.toByte(), 0x07)
        val ASF_PING = byteArrayOf(0x06, 0x00, 0xFF.toByte(), 0x06, 0x00, 0x00, 0x11, 0xBE.toByte(), 0x80.toByte(), 0x2A, 0x00, 0x00)
        const val PRIV_ADMIN = 4

        private val CC_TEXT = mapOf(
            0x80 to "Parameter nicht unterstützt", 0x81 to "Schreiben gerade gesperrt",
            0x82 to "Parameter schreibgeschützt", 0xC1 to "Befehl nicht unterstützt",
            0xC9 to "Wert außerhalb des Bereichs", 0xCC to "ungültige Daten",
            0xD4 to "fehlende Rechte (Admin nötig)", 0xD5 to "Befehl gerade nicht möglich",
        )

        fun rakpText(s: Int) = when (s) {
            0x01 -> "keine freien Ressourcen (zu viele Sitzungen offen?)"
            0x02 -> "ungültige Session-ID"
            0x09 -> "angeforderte Rechte nicht erlaubt"
            0x0D -> "Benutzername unbekannt"
            0x0F -> "Prüfsumme ungültig – Passwort falsch?"
            0x11, 0x12 -> "Cipher-Suite wird nicht unterstützt"
            0x13 -> "Benutzer/Rolle nicht erlaubt"
            else -> "Status 0x%02x".format(s)
        }

        /**
         * Löst IPv4, IPv6 und link-local mit Zone auf (fe80::1%eth0). Die Zone wird über
         * /sys/class/net/<if>/ifindex in eine Nummer übersetzt, das klappt auch ohne NetworkInterface-Rechte.
         */
        fun resolve(host: String): InetAddress {
            val h = host.trim().removePrefix("[").removeSuffix("]")
            val pct = h.indexOf('%')
            if (pct > 0) {
                val zone = h.substring(pct + 1)
                val base = InetAddress.getByName(h.substring(0, pct))
                val idx = zone.toIntOrNull() ?: ifIndex(zone)
                if (idx != null && base is Inet6Address) return Inet6Address.getByAddress(null, base.address, idx)
                return InetAddress.getByName(h)
            }
            return InetAddress.getByName(h)
        }

        fun ifIndex(name: String): Int? = try {
            File("/sys/class/net/$name/ifindex").readText().trim().toInt()
        } catch (_: Exception) {
            try {
                java.net.NetworkInterface.getByName(name)?.index
            } catch (_: Exception) {
                null
            }
        }

        /** EUI-64-Link-Local -> MAC (fe80::a2b3:ccff:fe11:2233 -> a0:b3:cc:11:22:33), sonst null. */
        fun macFromLinkLocal(a: InetAddress): String? {
            if (a !is Inet6Address) return null
            val b = a.address
            if (u(b[11]) != 0xFF || u(b[12]) != 0xFE) return null
            val m = byteArrayOf((u(b[8]) xor 2).toByte(), b[9], b[10], b[13], b[14], b[15])
            return m.joinToString(":") { "%02x".format(it) }
        }

        /** Darstellung fürs Eingabefeld: fe80::…%eth0 bzw. IPv4. */
        fun display(a: InetAddress, ifName: String?): String {
            if (a is Inet4Address) return a.hostAddress ?: ""
            val base = (a.hostAddress ?: "").substringBefore('%')
            return if (a.isLinkLocalAddress && ifName != null) "$base%$ifName" else base
        }

        /**
         * Sucht BMCs: IPMI-Presence-Ping an ff02::1 auf dem Interface und an 255.255.255.255.
         * Liefert Paare (Adresse, MAC falls aus link-local ableitbar).
         */
        fun discover(ifName: String, timeoutMs: Int = 3000, bind: (DatagramSocket) -> Unit = {}): List<Pair<String, String?>> {
            val found = LinkedHashMap<String, String?>()
            val socks = ArrayList<DatagramSocket>()
            val idx = ifIndex(ifName)
            if (idx != null) {
                try {
                    val s = DatagramSocket(InetSocketAddress(InetAddress.getByName("::"), 0))
                    bind(s)
                    val dst = Inet6Address.getByAddress(null, InetAddress.getByName("ff02::1").address, idx)
                    repeat(2) { s.send(DatagramPacket(ASF_PING, ASF_PING.size, dst, 623)) }
                    socks += s
                } catch (_: Exception) {
                }
            }
            try {
                val s = DatagramSocket()
                bind(s)
                s.broadcast = true
                repeat(2) { s.send(DatagramPacket(ASF_PING, ASF_PING.size, InetAddress.getByName("255.255.255.255"), 623)) }
                socks += s
            } catch (_: Exception) {
            }
            // Antworten auf allen Sockets parallel einsammeln
            val threads = socks.map { s ->
                Thread {
                    val buf = ByteArray(512)
                    val end = System.currentTimeMillis() + timeoutMs
                    try {
                        s.soTimeout = 250
                        while (System.currentTimeMillis() < end) {
                            val p = DatagramPacket(buf, buf.size)
                            try {
                                s.receive(p)
                            } catch (_: SocketTimeoutException) {
                                continue
                            }
                            if (p.length >= 9 && buf[0].toInt() == 6 && u(buf[8]) == 0x40) {
                                val key = display(p.address, ifName)
                                synchronized(found) { if (key !in found) found[key] = macFromLinkLocal(p.address) }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        s.close()
                    }
                }.also { it.start() }
            }
            threads.forEach { it.join(timeoutMs + 1000L) }
            return synchronized(found) { found.toList() }
        }

        // ---- Bytes ----
        private fun u(b: Byte) = b.toInt() and 0xFF
        private fun chk(d: ByteArray) = byteArrayOf((-(d.sumOf { u(it) })).toByte())
        private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        private fun le32(b: ByteArray) = u(b[0]) or (u(b[1]) shl 8) or (u(b[2]) shl 16) or (u(b[3]) shl 24)
        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
        private fun ip4(b: ByteArray?) = if (b == null || b.size < 4) "?" else b.copyOf(4).joinToString(".") { u(it).toString() }
        fun ip4Bytes(s: String): ByteArray? {
            val p = s.trim().split(".")
            if (p.size != 4) return null
            val out = ByteArray(4)
            for (i in 0..3) out[i] = (p[i].toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toByte()
            return out
        }
    }
}
