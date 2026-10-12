package de.bmclink

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Wird NICHT in der App ausgeführt, sondern als root über
 *   CLASSPATH=<apk> app_process /system/bin de.bmclink.DhcpMain <iface> <serverIp> <prefix> <poolStart> <poolEnd>
 * Port 67 darf nur root öffnen. Ausgabe zeilenweise auf stdout, wird von der App gelesen:
 *   INFO <text> | LEASE <mac> <ip> <hostname> | ERROR <text>
 * Beendet sich, wenn stdin geschlossen wird (= App stoppt den Server).
 */
object DhcpMain {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (t: Throwable) {
            out("ERROR ${t.javaClass.simpleName}: ${t.message}")
            System.exit(1)
        }
    }

    private fun out(s: String) {
        println(s)
        System.out.flush()
    }

    private fun run(args: Array<String>) {
        if (args.size < 5) {
            out("ERROR Aufruf: DhcpMain <iface> <serverIp> <prefix> <poolStart> <poolEnd>")
            System.exit(2)
        }
        val iface = args[0]
        val serverIp = ipToInt(args[1]) ?: error("ungültige Server-IP")
        val prefix = args[2].toInt()
        val start = ipToInt(args[3]) ?: error("ungültiger Pool-Start")
        val end = ipToInt(args[4]) ?: error("ungültiges Pool-Ende")

        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP)
        Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
        Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_BROADCAST, 1)

        val bound = bindToDevice(fd, iface)
        if (bound) {
            out("INFO Socket an $iface gebunden")
        } else {
            // Ohne SO_BINDTODEVICE würden wir auch DHCP-Anfragen aus dem WLAN beantworten -> gefährlich.
            val others = otherLanInterfacesWithIp(iface)
            if (others.isNotEmpty()) {
                out("ERROR Konnte den Server nicht auf $iface beschränken und ${others.joinToString()} ist aktiv. " +
                        "Bitte WLAN/Hotspot ausschalten und erneut starten.")
                System.exit(3)
            }
            out("INFO Kein SO_BINDTODEVICE – läuft trotzdem, da nur $iface im LAN hängt")
        }

        Os.bind(fd, Inet4Address.getByName("0.0.0.0"), 67)
        out("INFO DHCP-Server läuft auf $iface, Server ${intToIp(serverIp)}/$prefix, Pool ${intToIp(start)} – ${intToIp(end)}")

        val core = DhcpCore(serverIp, prefix, start, end, log = { out("INFO $it") })
        core.onLease = { mac, ip, host -> out("LEASE $mac ${intToIp(ip)} ${host ?: "-"}") }

        // Beenden, wenn die App stdin schließt
        Thread {
            try {
                while (System.`in`.read() >= 0) { /* ignorieren */ }
            } catch (_: Exception) {
            }
            out("INFO DHCP-Server beendet")
            System.exit(0)
        }.apply { isDaemon = true }.start()

        val subnetBroadcast = InetAddress.getByName(intToIp((serverIp and maskOf(prefix)) or maskOf(prefix).inv()))
        val limitedBroadcast = InetAddress.getByName("255.255.255.255")
        val buf = ByteArray(1500)
        while (true) {
            val src = InetSocketAddress(0)
            val n = Os.recvfrom(fd, buf, 0, buf.size, 0, src)
            val reply = try {
                core.handle(buf, n)
            } catch (e: Exception) {
                out("INFO Ungültiges Paket ignoriert: ${e.message}")
                null
            } ?: continue
            try {
                if (reply.broadcast) {
                    try {
                        Os.sendto(fd, reply.data, 0, reply.data.size, 0, limitedBroadcast, 68)
                    } catch (e: Exception) {
                        // Fallback: Subnetz-Broadcast (läuft über unsere Routing-Regel zuverlässig auf das Interface)
                        Os.sendto(fd, reply.data, 0, reply.data.size, 0, subnetBroadcast, 68)
                    }
                } else {
                    Os.sendto(fd, reply.data, 0, reply.data.size, 0, InetAddress.getByName(intToIp(reply.destIp)), 68)
                }
            } catch (e: Exception) {
                out("INFO Senden fehlgeschlagen: ${e.message}")
            }
        }
    }

    private fun maskOf(prefix: Int) = if (prefix == 0) 0 else (-1 shl (32 - prefix))

    /** SO_BINDTODEVICE über die (versteckte) Methode Os.setsockoptIfreq. Als root via app_process erlaubt. */
    private fun bindToDevice(fd: FileDescriptor, iface: String): Boolean {
        val soBindToDevice = try {
            OsConstants::class.java.getField("SO_BINDTODEVICE").getInt(null)
        } catch (_: Exception) {
            25 // Linux-Wert, auf allen Android-Architekturen gleich
        }
        return try {
            val m = Os::class.java.getMethod(
                "setsockoptIfreq", FileDescriptor::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, String::class.java
            )
            m.invoke(null, fd, OsConstants.SOL_SOCKET, soBindToDevice, iface)
            true
        } catch (e: Throwable) {
            out("INFO SO_BINDTODEVICE nicht möglich: ${e.cause?.message ?: e.message}")
            false
        }
    }

    private fun otherLanInterfacesWithIp(iface: String): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.name != iface && NetInfo.isBroadcastLan(it.name) && it.isUp }
            .filter { ni -> ni.inetAddresses.toList().any { it is Inet4Address } }
            .map { it.name }
    } catch (_: Exception) {
        emptyList()
    }
}
