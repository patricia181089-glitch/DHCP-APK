package de.bmclink

import android.net.Network
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class Host(
    val ip: String,
    val ipmi: Boolean,
    val ports: List<Int>,
    val vendor: String?,
) {
    val looksLikeBmc: Boolean get() = ipmi || vendor != null
    fun url(): String = if (443 in ports || ports.isEmpty()) "https://$ip" else "http://$ip"
}

/**
 * Sucht BMCs in einem Subnetz:
 *  - IPMI/RMCP "Presence Ping" auf UDP 623 (antwortet praktisch jeder BMC)
 *  - TCP 443/80/22/5900/623
 *  - Webseite + Redfish auf Herstellerkennung prüfen (iDRAC, iLO, Supermicro, XClarity ...)
 */
class Scanner(private val network: Network?) {

    val cancelled = AtomicBoolean(false)

    fun scan(targets: List<Int>, onProgress: (done: Int, total: Int) -> Unit, onFound: (Host) -> Unit) {
        val pool = Executors.newFixedThreadPool(48)
        val done = AtomicInteger(0)
        val futures = ArrayList<Future<*>>()
        for (t in targets) {
            futures += pool.submit {
                if (!cancelled.get()) {
                    val h = probe(intToIp(t))
                    if (h != null) onFound(h)
                }
                onProgress(done.incrementAndGet(), targets.size)
            }
        }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.MINUTES)
    }

    /** Prüft genau eine IP. Liefert null, wenn dort nichts antwortet. */
    fun probe(ip: String): Host? {
        val ipmi = asfPing(ip)
        val ports = listOf(443, 80, 22, 5900).filter { tcpOpen(ip, it, 700) }
        if (!ipmi && ports.isEmpty()) return null
        val vendor = if (443 in ports || 80 in ports) identify(ip, ports) else null
        return Host(ip, ipmi, ports, vendor ?: if (ipmi) "BMC (IPMI)" else null)
    }

    // ---------- IPMI / RMCP ----------

    /** ASF Presence Ping (RMCP, UDP 623). Antwort = Presence Pong (0x40). */
    fun asfPing(ip: String): Boolean {
        val ping = byteArrayOf(
            0x06, 0x00, 0xFF.toByte(), 0x06,         // RMCP: Version, Reserved, Seq, Class ASF
            0x00, 0x00, 0x11, 0xBE.toByte(),         // IANA 4542 (ASF)
            0x80.toByte(), 0x2A, 0x00, 0x00          // Presence Ping, Tag, Reserved, Länge 0
        )
        var s: DatagramSocket? = null
        return try {
            s = DatagramSocket()
            bindUdp(s)
            s.soTimeout = 500
            val addr = InetAddress.getByName(ip)
            val buf = ByteArray(64)
            repeat(2) {
                s.send(DatagramPacket(ping, ping.size, addr, 623))
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    if (pkt.length >= 9 && buf[0] == 0x06.toByte() && buf[8] == 0x40.toByte()) return true
                } catch (_: java.net.SocketTimeoutException) {
                }
            }
            false
        } catch (_: Exception) {
            false
        } finally {
            s?.close()
        }
    }

    // ---------- TCP ----------

    fun tcpOpen(ip: String, port: Int, timeout: Int): Boolean {
        var s: Socket? = null
        return try {
            s = network?.socketFactory?.createSocket() ?: Socket()
            s.connect(InetSocketAddress(ip, port), timeout)
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                s?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun bindUdp(s: DatagramSocket) {
        if (network != null && Build.VERSION.SDK_INT >= 22) {
            try {
                network.bindSocket(s)
            } catch (_: Exception) {
            }
        }
    }

    // ---------- Herstellererkennung ----------

    fun identify(ip: String, ports: List<Int>): String? {
        val base = if (443 in ports) "https://$ip" else "http://$ip"
        val redfish = fetch("$base/redfish/v1/")
        val page = fetch("$base/")
        val all = ((redfish ?: "") + "\n" + (page ?: "")).lowercase()
        return when {
            "idrac" in all || "\"dell\"" in all || "dell inc" in all -> "Dell iDRAC"
            "integrated lights-out" in all || "ilo " in all || "\"hpe\"" in all || "hewlett" in all -> "HPE iLO"
            "xclarity" in all || "lenovo" in all || "imm2" in all -> "Lenovo XClarity/IMM"
            "supermicro" in all || "aten" in all -> "Supermicro IPMI"
            "fujitsu" in all || "irmc" in all -> "Fujitsu iRMC"
            "cisco" in all && ("cimc" in all || "imc" in all) -> "Cisco CIMC"
            "asrockrack" in all || "asrock rack" in all -> "ASRock Rack BMC"
            "megarac" in all || "american megatrends" in all || "\"ami\"" in all -> "AMI MegaRAC BMC"
            "openbmc" in all -> "OpenBMC"
            "gigabyte" in all -> "Gigabyte BMC"
            "redfish" in all || "\"@odata.id\"" in all -> "BMC (Redfish)"
            "ipmi" in all || "bmc" in all -> "BMC"
            else -> null
        }
    }

    private fun fetch(url: String): String? {
        var c: HttpURLConnection? = null
        return try {
            val u = URL(url)
            c = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
            if (c is HttpsURLConnection) {
                // Nur für die Erkennung: BMCs haben fast immer selbstsignierte Zertifikate.
                c.sslSocketFactory = trustAll.socketFactory
                c.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            c.connectTimeout = 2500
            c.readTimeout = 3000
            c.instanceFollowRedirects = true
            val code = c.responseCode
            val header = c.headerFields.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.use { s ->
                val buf = ByteArray(65536)
                var n = 0
                while (n < buf.size) {
                    val r = s.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.UTF_8)
            } ?: ""
            header + "\n" + body
        } catch (_: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    companion object {
        private val trustAll: SSLContext by lazy {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
        }

        /** Alle Host-Adressen eines Netzes (bei > /24 nur das /24 um die eigene IP). */
        fun targetsFor(own: Ipv4): List<Int> {
            val net = if (own.prefix < 24) Ipv4(own.addr, 24) else own
            val list = ArrayList<Int>()
            var i = net.network + 1
            while (i < net.broadcast) {
                if (i != own.addr) list += i
                i++
            }
            return list
        }
    }
}
