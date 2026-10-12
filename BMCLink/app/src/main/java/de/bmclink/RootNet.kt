package de.bmclink

/**
 * Setzt per root eine feste IP auf den USB-LAN-Adapter und sorgt per Policy-Routing dafür,
 * dass ALLE Apps (auch der Browser) das BMC-Netz über diesen Adapter erreichen.
 * Android (IpClient/EthernetService) entfernt fremde Adressen gern wieder,
 * deshalb hält ein kleiner Wächter-Loop alles am Leben, bis "Zurücksetzen" gedrückt wird.
 */
object RootNet {

    private const val TABLE = 1213
    private const val PREF = 99
    private const val FLAG = "/data/local/tmp/bmclink.run"

    private fun net(own: Ipv4) = "${intToIp(own.network)}/${own.prefix}"

    private fun applyCmds(iface: String, own: Ipv4): String = """
        ip link set $iface up 2>/dev/null
        ip -4 addr show dev $iface | grep -q "inet ${intToIp(own.addr)}/" || ip addr add $own broadcast + dev $iface
        ip route replace ${net(own)} dev $iface src ${intToIp(own.addr)} table $TABLE
        ip route replace 255.255.255.255/32 dev $iface table $TABLE
        ip rule show | grep -q "to ${net(own)} lookup $TABLE" || ip rule add to ${net(own)} lookup $TABLE pref $PREF
        ip rule show | grep -q "to 255.255.255.255 lookup $TABLE" || ip rule add to 255.255.255.255 lookup $TABLE pref $PREF
    """.trimIndent()

    /** Einmalig anwenden + Ergebnis zurückgeben (zum Anzeigen von Fehlern). */
    fun apply(iface: String, own: Ipv4): Root.Result {
        val script = """
            ${applyCmds(iface, own)}
            iptables -C INPUT -i $iface -j ACCEPT 2>/dev/null || iptables -I INPUT -i $iface -j ACCEPT
            iptables -C OUTPUT -o $iface -j ACCEPT 2>/dev/null || iptables -I OUTPUT -o $iface -j ACCEPT
            echo "---"
            ip -4 addr show dev $iface
            ip route get ${intToIp(own.broadcast - 1)} 2>&1
        """.trimIndent()
        return Root.run(script)
    }

    /** Wächter im Hintergrund starten (läuft als root-Shell, endet wenn das Flag-File gelöscht wird). */
    fun startWatchdog(iface: String, own: Ipv4): Process {
        val loop = applyCmds(iface, own).lines().joinToString("; ")
        val script = """
            touch $FLAG
            while [ -f $FLAG ]; do $loop; sleep 3; done
            exit
        """.trimIndent()
        return Root.startLongRunning(script)
    }

    /**
     * IPv6 link-local auf dem Adapter sicherstellen, damit ein BMC mit unbekannter IPv4 über fe80::… erreichbar ist.
     * Die Regel greift nur für Pakete, die ausdrücklich über dieses Interface gehen (oif), stört also sonst nichts.
     * Danach Ping an alle Knoten (ff02::1) und Nachbartabelle auslesen.
     * Rückgabe: Liste (link-local-Adresse, MAC).
     */
    fun v6Neighbors(iface: String): Pair<List<Pair<String, String>>, String> {
        val script = """
            echo 0 > /proc/sys/net/ipv6/conf/$iface/disable_ipv6 2>/dev/null
            ip link set $iface up 2>/dev/null
            ip -6 route replace fe80::/64 dev $iface table $TABLE 2>/dev/null
            ip -6 route replace ff00::/8 dev $iface table $TABLE 2>/dev/null
            ip -6 rule show | grep -q "oif $iface lookup $TABLE" || ip -6 rule add oif $iface lookup $TABLE pref $PREF
            ip6tables -C INPUT -i $iface -j ACCEPT 2>/dev/null || ip6tables -I INPUT -i $iface -j ACCEPT
            ip6tables -C OUTPUT -o $iface -j ACCEPT 2>/dev/null || ip6tables -I OUTPUT -o $iface -j ACCEPT
            sleep 2
            (ping -6 -c 3 -w 3 -I $iface ff02::1 || ping6 -c 3 -w 3 -I $iface ff02::1) >/dev/null 2>&1
            echo "---OWN"
            ip -6 addr show dev $iface
            echo "---NEIGH"
            ip -6 neigh show dev $iface
        """.trimIndent()
        val r = Root.run(script, timeoutSec = 25)
        val own = Regex("inet6 (fe80::[0-9a-f:]+)").findAll(r.out.substringAfter("---OWN").substringBefore("---NEIGH"))
            .map { it.groupValues[1] }.toSet()
        val list = ArrayList<Pair<String, String>>()
        for (line in r.out.substringAfter("---NEIGH").lines()) {
            val m = Regex("^(fe80::[0-9a-f:]+)\\s.*lladdr ([0-9a-f:]{17})").find(line.trim()) ?: continue
            if (m.groupValues[1] !in own && "FAILED" !in line) list += m.groupValues[1] to m.groupValues[2]
        }
        return list to r.out
    }

    fun teardown(iface: String, own: Ipv4?): Root.Result {
        val sb = StringBuilder("rm -f $FLAG\n")
        sb.append("pkill -f de.bmclink.DhcpMain 2>/dev/null\n")
        sb.append("sleep 1\n")
        sb.append("while ip rule del lookup $TABLE 2>/dev/null; do :; done\n")
        sb.append("ip route flush table $TABLE 2>/dev/null\n")
        sb.append("while ip -6 rule del lookup $TABLE 2>/dev/null; do :; done\n")
        sb.append("ip -6 route flush table $TABLE 2>/dev/null\n")
        sb.append("ip6tables -D INPUT -i $iface -j ACCEPT 2>/dev/null\n")
        sb.append("ip6tables -D OUTPUT -o $iface -j ACCEPT 2>/dev/null\n")
        if (own != null) sb.append("ip addr del $own dev $iface 2>/dev/null\n")
        sb.append("iptables -D INPUT -i $iface -j ACCEPT 2>/dev/null\n")
        sb.append("iptables -D OUTPUT -o $iface -j ACCEPT 2>/dev/null\n")
        sb.append("echo ok\n")
        return Root.run(sb.toString())
    }

    /** Startet den eigenen DHCP-Server als root (app_process lädt unsere eigene APK). */
    fun startDhcp(apkPath: String, iface: String, own: Ipv4, poolStart: Int, poolEnd: Int): Process {
        val script = """
            pkill -f de.bmclink.DhcpMain 2>/dev/null
            iptables -C INPUT -i $iface -p udp --dport 67 -j ACCEPT 2>/dev/null || iptables -I INPUT -i $iface -p udp --dport 67 -j ACCEPT
            export CLASSPATH='$apkPath'
            exec app_process /system/bin de.bmclink.DhcpMain $iface ${intToIp(own.addr)} ${own.prefix} ${intToIp(poolStart)} ${intToIp(poolEnd)} 2>&1
        """.trimIndent()
        return Root.startLongRunning(script)
    }
}
