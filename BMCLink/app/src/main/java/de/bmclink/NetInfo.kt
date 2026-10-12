package de.bmclink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** IPv4-Adresse mit Präfix, z. B. 192.168.0.250/24. */
data class Ipv4(val addr: Int, val prefix: Int) {
    val mask: Int get() = if (prefix == 0) 0 else (-1 shl (32 - prefix))
    val network: Int get() = addr and mask
    val broadcast: Int get() = network or mask.inv()
    fun contains(ip: Int) = (ip and mask) == network
    override fun toString() = "${intToIp(addr)}/$prefix"

    companion object {
        fun parse(s: String): Ipv4? {
            val parts = s.trim().split("/")
            val ip = ipToInt(parts[0]) ?: return null
            val pfx = if (parts.size > 1) parts[1].toIntOrNull() ?: return null else 24
            if (pfx !in 8..30) return null
            return Ipv4(ip, pfx)
        }
    }
}

fun ipToInt(s: String): Int? {
    val p = s.trim().split(".")
    if (p.size != 4) return null
    var r = 0
    for (x in p) {
        val v = x.toIntOrNull() ?: return null
        if (v !in 0..255) return null
        r = (r shl 8) or v
    }
    return r
}

fun intToIp(i: Int) = "${(i ushr 24) and 255}.${(i ushr 16) and 255}.${(i ushr 8) and 255}.${i and 255}"

fun Inet4Address.toInt(): Int {
    val b = address
    return ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
            ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
}

data class IfaceInfo(
    val name: String,
    val up: Boolean,
    val addrs: List<Ipv4>,
    /** Android-Netzwerk, falls das Handy hier selbst per DHCP eine IP bekommen hat (Client-Modus). */
    val network: Network?,
)

object NetInfo {

    /** Namen, die typischerweise ein USB-LAN-Adapter bzw. Tethering-Interface bekommt. */
    private val LAN_PREFIXES = listOf("eth", "usb", "enx", "lan", "en")
    private val IGNORE_PREFIXES = listOf("lo", "wlan", "rmnet", "ccmni", "dummy", "p2p", "tun", "ip6", "sit",
        "ifb", "bond", "r_rmnet", "v4-", "clat", "ap", "swlan", "rev_rmnet", "ipsec", "epdg", "aware", "nan", "seth")

    fun isLanName(n: String): Boolean =
        IGNORE_PREFIXES.none { n.startsWith(it) } && LAN_PREFIXES.any { n.startsWith(it) }

    /** Andere Interfaces, über die Broadcasts im LAN ankommen könnten (WLAN, Hotspot ...). */
    fun isBroadcastLan(n: String): Boolean =
        n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap") || isLanName(n)

    fun list(ctx: Context): List<IfaceInfo> {
        val ethNetworks = HashMap<String, Network>()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
                val name = cm.getLinkProperties(n)?.interfaceName ?: continue
                ethNetworks[name] = n
            }
        } catch (_: Exception) {
        }

        val result = ArrayList<IfaceInfo>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            if (ifs != null) for (ni in ifs.toList()) {
                val name = ni.name ?: continue
                if (!isLanName(name) && !ethNetworks.containsKey(name)) continue
                val addrs = ni.interfaceAddresses
                    .filter { it.address is Inet4Address }
                    .map { Ipv4((it.address as Inet4Address).toInt(), it.networkPrefixLength.toInt()) }
                result += IfaceInfo(name, safeUp(ni), addrs, ethNetworks[name])
            }
        } catch (_: Exception) {
        }
        // Interfaces, die NetworkInterface (Android 11+ Einschränkungen) nicht liefert, aber CM kennt
        for ((name, net) in ethNetworks) {
            if (result.none { it.name == name }) result += IfaceInfo(name, true, emptyList(), net)
        }
        return result.sortedBy { it.name }
    }

    /** Liste der Interfaces über /sys/class/net, braucht keine Rechte (für die Anzeige "Adapter erkannt"). */
    fun sysLanNames(): List<String> = try {
        java.io.File("/sys/class/net").list()?.filter { isLanName(it) }?.sorted() ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun safeUp(ni: NetworkInterface) = try {
        ni.isUp
    } catch (_: Exception) {
        false
    }
}
