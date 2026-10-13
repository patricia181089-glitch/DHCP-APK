package de.bmclink

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    // ---- Farben ----
    private val cBg = Color.parseColor("#101317")
    private val cCard = Color.parseColor("#1B2027")
    private val cText = Color.parseColor("#E8EBEF")
    private val cDim = Color.parseColor("#9AA4B1")
    private val cAccent = Color.parseColor("#3DDC97")
    private val cWarn = Color.parseColor("#FFB547")
    private val cErr = Color.parseColor("#FF6B6B")

    private val ui = Handler(Looper.getMainLooper())

    // ---- Views ----
    private lateinit var statusText: TextView
    private lateinit var rootText: TextView
    private lateinit var ifaceEdit: EditText
    private lateinit var bmcEdit: EditText
    private lateinit var phoneEdit: EditText
    private lateinit var dhcpButton: Button
    private lateinit var scanButton: Button
    private lateinit var resultList: LinearLayout
    private lateinit var resultHint: TextView
    private lateinit var logText: TextView

    // ---- Zustand ----
    @Volatile private var rootOk = false
    private var watchdog: Process? = null
    private var dhcp: Process? = null
    private var appliedIp: Ipv4? = null
    private var appliedIface: String? = null
    private var scanner: Scanner? = null
    private val found = LinkedHashMap<String, Host>()

    private val refreshTick = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        setContentView(buildUi())
        log("App gestartet. Adapter einstecken, Kabel zum Management-Port (iDRAC/iLO/IPMI) stecken.")
        Thread {
            rootOk = Root.available()
            ui.post {
                rootText.text = if (rootOk) "✔ Root verfügbar" else "✖ Kein Root – Fall B nur mit Root möglich"
                rootText.setTextColor(if (rootOk) cAccent else cWarn)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refreshTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refreshTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner?.cancelled?.set(true)
        if (isFinishing && (watchdog != null || dhcp != null)) {
            val iface = appliedIface ?: iface()
            val ip = appliedIp
            dhcp?.destroy()
            watchdog?.destroy()
            Thread { RootNet.teardown(iface, ip) }.start()
        }
    }

    // =====================================================================
    // UI-Aufbau (bewusst ohne XML/AndroidX, damit die App klein und robust bleibt)
    // =====================================================================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "BMC Link"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
        })
        root.addView(dim("Per USB-LAN-Adapter vom Handy auf iDRAC, iLO, IPMI & Co."))

        // ---------- Status ----------
        card(root, "Adapter") { c ->
            statusText = text("…").also { c.addView(it) }
            rootText = text("Prüfe Root …", cDim).also { c.addView(it) }
            c.addView(label("Interface"))
            ifaceEdit = edit("eth0").also { c.addView(it) }
        }

        // ---------- Fall A ----------
        card(root, "Fall A · BMC steht auf DHCP (ohne Root)") { c ->
            c.addView(dim(
                "1. Ethernet-Tethering einschalten (Android 11+). Das Handy verteilt dann selbst IPs.\n" +
                "2. 1–2 Minuten warten, bis der BMC eine Adresse geholt hat.\n" +
                "3. „BMC suchen“ – gefundene Geräte erscheinen unten und lassen sich im Browser öffnen."
            ))
            c.addView(button("Ethernet-Tethering öffnen") { openTetherSettings() })
            scanButton = button("BMC suchen") { toggleScan() }.also { c.addView(it) }
        }

        // ---------- Fall B ----------
        card(root, "Fall B · BMC hat feste IP (Root)") { c ->
            c.addView(dim("Standard-IP wählen oder eintragen. Das Handy bekommt eine IP im selben Netz, " +
                    "danach ist der BMC für alle Apps (auch den Browser) erreichbar."))
            val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            presets.addView(chip("Dell iDRAC\n192.168.0.120") { setBmc("192.168.0.120") })
            presets.addView(chip("Lenovo XCC\n192.168.70.125") { setBmc("192.168.70.125") })
            presets.addView(chip("Nur DHCP\n10.99.0.1") { setOnlyDhcp() })
            c.addView(HorizontalScrollView(this).apply { addView(presets); isHorizontalScrollBarEnabled = false })
            c.addView(dim("Standard-IPs ohne Gewähr – je nach Generation/Einstellung verschieden."))

            c.addView(label("BMC-IP"))
            bmcEdit = edit("192.168.0.120").also {
                it.inputType = InputType.TYPE_CLASS_PHONE
                c.addView(it)
            }
            c.addView(label("Handy-IP / Präfix"))
            phoneEdit = edit("192.168.0.250/24").also {
                it.inputType = InputType.TYPE_CLASS_PHONE
                c.addView(it)
            }
            c.addView(button("Handy-IP setzen") { applyStaticIp() })
            c.addView(button("BMC-IP direkt prüfen") { probeBmc() })
            dhcpButton = button("Eigenen DHCP-Server starten") { toggleDhcp() }.also { c.addView(it) }
            c.addView(dim("DHCP-Server: Alternative zum Tethering, wenn der BMC auf DHCP steht. " +
                    "Vergibt Adressen .100–.199 im Netz der Handy-IP."))
            c.addView(button("Alles zurücksetzen", danger = true) { resetAll() })
        }

        // ---------- Ergebnisse ----------
        card(root, "Gefundene Geräte") { c ->
            resultHint = dim("Noch nichts gefunden.").also { c.addView(it) }
            resultList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(resultList)
        }

        // ---------- Log ----------
        card(root, "Protokoll") { c ->
            logText = TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11.5f
                setTextColor(cDim)
                setTextIsSelectable(true)
            }
            c.addView(logText)
        }

        return ScrollView(this).apply {
            setBackgroundColor(cBg)
            fitsSystemWindows = true
            addView(root)
        }
    }

    private fun card(parent: LinearLayout, title: String, body: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(cCard); cornerRadius = dp(14).toFloat()
            }
        }
        c.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
            setPadding(0, 0, 0, dp(6))
        })
        body(c)
        parent.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
    }

    private fun text(s: String, color: Int = cText) = TextView(this).apply {
        text = s; textSize = 14f; setTextColor(color); setPadding(0, dp(2), 0, dp(2))
    }

    private fun dim(s: String) = text(s, cDim).apply { textSize = 13f }

    private fun label(s: String) = text(s, cDim).apply { textSize = 12f; setPadding(0, dp(8), 0, 0) }

    private fun edit(s: String) = EditText(this).apply {
        setText(s); setTextColor(cText); setHintTextColor(cDim); textSize = 15f; isSingleLine = true
    }

    private fun button(s: String, danger: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setTextColor(if (danger) cErr else Color.parseColor("#0B1A12"))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (danger) setStroke(dp(1), cErr) else setColor(cAccent)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(8) }
    }

    private fun chip(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 12.5f
        gravity = Gravity.CENTER
        setTextColor(cText)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setStroke(dp(1), cAccent) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(8); topMargin = dp(6) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    // =====================================================================
    // Status
    // =====================================================================

    private fun iface() = ifaceEdit.text.toString().trim().ifEmpty { "eth0" }

    /**
     * Erkennt den Adapter über die USB-Geräteliste (braucht keine Rechte).
     * Ab Android 11 sieht eine App Netzwerk-Interfaces ohne IP sonst gar nicht.
     */
    private fun usbLanAdapter(): String? = try {
        val um = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
        um.deviceList.values.firstNotNullOfOrNull { d ->
            val known = when (d.vendorId) {
                0x0bda -> "Realtek"
                0x0b95 -> "ASIX"
                0x0424 -> "Microchip/SMSC"
                0x2001 -> "D-Link"
                0x13b1 -> "Linksys"
                0x17ef -> "Lenovo"
                0x04e8 -> "Samsung"
                0x2357 -> "TP-Link"
                0x0fe6 -> "Kontron/CoreChips"
                0x050d -> "Belkin"
                0x0846 -> "Netgear"
                0x1c04 -> "QinHeng"
                0x0b05 -> "ASUS"
                else -> null
            }
            // Netzwerk-Klasse: CDC (2), Vendor-spezifisch (255) bei Realtek/ASIX
            val isNet = (0 until d.interfaceCount).any {
                val c = d.getInterface(it).interfaceClass
                c == 2 || c == 10 || (c == 255 && known != null)
            }
            if (isNet && known != null) "USB-LAN-Adapter ($known)" else if (isNet) "USB-Netzwerkgerät" else null
        }
    } catch (_: Exception) {
        null
    }

    private fun refreshStatus() {
        val list = NetInfo.list(this)
        val sys = NetInfo.sysLanNames()
        // Interface-Feld automatisch füllen, falls der Standard nicht existiert
        val names = (list.map { it.name } + sys).distinct()
        if (names.isNotEmpty() && iface() !in names && appliedIface == null) ifaceEdit.setText(names.first())

        val cur = list.firstOrNull { it.name == iface() }
        val sb = StringBuilder()
        val color: Int
        val usb = usbLanAdapter()
        when {
            cur == null && iface() !in sys && usb == null -> {
                sb.append("Kein LAN-Adapter erkannt.\nAdapter per OTG einstecken (Handy muss OTG + den Chip unterstützen).")
                color = cErr
            }
            cur == null || cur.addrs.isEmpty() -> {
                sb.append("${usb ?: iface()} erkannt, aber noch ohne IP.\n" +
                        "• Server verteilt IPs (z. B. Inspur 126.0.0.x): Tethering AUS lassen, Kabel stecken, ein paar Sekunden warten\n" +
                        "• BMC wartet auf DHCP: Tethering AN\n" +
                        "• Feste BMC-IP: Fall B (Root)")
                color = cWarn
            }
            else -> {
                val mode = when {
                    appliedIp != null && cur.addrs.any { it.addr == appliedIp!!.addr } -> "feste IP (Root)"
                    cur.network != null -> "Client – IP vom DHCP im Netz"
                    else -> "Tethering / lokal"
                }
                sb.append("${cur.name}: ${cur.addrs.joinToString()}  ·  $mode")
                color = cAccent
            }
        }
        if (dhcp != null) sb.append("\nDHCP-Server läuft")
        statusText.text = sb.toString()
        statusText.setTextColor(color)
    }

    // =====================================================================
    // Fall A
    // =====================================================================

    private fun openTetherSettings() {
        val candidates = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherWifiSettingsActivity"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in candidates) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                toast("Unter „Hotspot & Tethering“ → „Ethernet-Tethering“ einschalten")
                return
            } catch (_: Exception) {
            }
        }
        toast("Einstellungen konnten nicht geöffnet werden")
    }

    private fun toggleScan() {
        scanner?.let {
            it.cancelled.set(true)
            log("Scan wird abgebrochen …")
            return
        }
        val info = NetInfo.list(this).firstOrNull { it.name == iface() }
        val own = info?.addrs?.firstOrNull()
        if (own == null) {
            log("✖ ${iface()} hat keine IPv4-Adresse. Tethering einschalten (Fall A) oder Handy-IP setzen (Fall B).")
            toast("Adapter hat keine IP")
            return
        }
        val targets = Scanner.targetsFor(own)
        val s = Scanner(info.network)
        scanner = s
        scanButton.text = "Suche läuft … (Tippen = Abbrechen)"
        log("Suche in ${intToIp(own.network)}/${maxOf(own.prefix, 24)} (${targets.size} Adressen) …")
        Thread {
            var last = 0L
            s.scan(targets,
                onProgress = { d, t ->
                    val now = System.currentTimeMillis()
                    if (now - last > 400 || d == t) {
                        last = now
                        ui.post { if (scanner === s) scanButton.text = "Suche … $d/$t (Tippen = Abbrechen)" }
                    }
                },
                onFound = { h -> ui.post { addHost(h) } })
            ui.post {
                scanner = null
                scanButton.text = "BMC suchen"
                val bmcs = found.values.count { it.looksLikeBmc }
                log("Suche fertig: ${found.size} Gerät(e), davon $bmcs BMC-ähnlich.")
            }
        }.start()
    }

    // =====================================================================
    // Fall B
    // =====================================================================

    private fun setBmc(ip: String) {
        bmcEdit.setText(ip)
        val bmc = ipToInt(ip) ?: return
        val net = bmc and (-1 shl 8)
        val phone = if ((bmc and 255) == 250) net or 251 else net or 250
        phoneEdit.setText("${intToIp(phone)}/24")
    }

    private fun setOnlyDhcp() {
        bmcEdit.setText("")
        phoneEdit.setText("10.99.0.1/24")
        log("„Nur DHCP“: Handy-IP setzen, dann „Eigenen DHCP-Server starten“. Der BMC bekommt eine 10.99.0.1xx.")
    }

    /** Nur Hinweis anzeigen (UI-Thread, blockiert nicht). */
    private fun needRoot(): Boolean {
        if (!rootOk) {
            log("✖ Ohne Root lässt Android keine feste IP auf dem LAN-Adapter zu. Nutze Fall A (Tethering) " +
                    "oder stelle den BMC am Server (BIOS/Setup) auf DHCP um.")
            toast("Root benötigt")
        }
        return rootOk
    }

    private fun applyStaticIp(then: (() -> Unit)? = null) {
        val own = Ipv4.parse(phoneEdit.text.toString())
        if (own == null) {
            log("✖ Handy-IP ungültig. Format: 192.168.0.250/24"); return
        }
        val bmc = bmcEdit.text.toString().trim().takeIf { it.isNotEmpty() }?.let { ipToInt(it) }
        if (bmc != null && !own.contains(bmc)) {
            log("⚠ BMC ${intToIp(bmc)} liegt nicht im Netz ${intToIp(own.network)}/${own.prefix} – trotzdem gesetzt.")
        }
        if (bmc != null && bmc == own.addr) {
            log("✖ Handy-IP darf nicht gleich der BMC-IP sein."); return
        }
        val iface = iface()
        Thread {
            if (!needRootBg()) return@Thread
            val old = appliedIp
            if (old != null && (old != own || appliedIface != iface)) {
                watchdog?.destroy(); watchdog = null
                RootNet.teardown(appliedIface ?: iface, old)
            }
            val r = RootNet.apply(iface, own)
            val ok = r.out.contains("inet ${intToIp(own.addr)}/")
            ui.post {
                log(if (ok) "✔ $iface hat jetzt $own" else "✖ IP setzen fehlgeschlagen")
                if (r.out.isNotBlank()) log(r.out)
            }
            if (ok) {
                appliedIp = own
                appliedIface = iface
                if (watchdog == null) watchdog = RootNet.startWatchdog(iface, own)
                ui.post {
                    refreshStatus()
                    if (bmc != null && then == null) log("Jetzt „BMC-IP direkt prüfen“ oder Browser: https://${intToIp(bmc)}")
                    then?.invoke()
                }
            }
        }.start()
    }

    /** Root-Prüfung aus einem Hintergrund-Thread. */
    private fun needRootBg(): Boolean {
        if (!rootOk) rootOk = Root.available(force = true)
        if (!rootOk) ui.post { needRoot() }
        return rootOk
    }

    private fun probeBmc() {
        val ip = bmcEdit.text.toString().trim()
        if (ipToInt(ip) == null) {
            log("✖ Bitte eine gültige BMC-IP eintragen."); return
        }
        val info = NetInfo.list(this).firstOrNull { it.name == iface() }
        log("Prüfe $ip …")
        Thread {
            val h = Scanner(info?.network).probe(ip)
            ui.post {
                if (h == null) {
                    log("✖ $ip antwortet nicht (kein IPMI auf UDP 623, kein 443/80/22). Kabel am Management-Port? Richtige IP?")
                } else {
                    addHost(h)
                    log("✔ $ip antwortet: ${describe(h)}")
                }
            }
        }.start()
    }

    private fun toggleDhcp() {
        dhcp?.let {
            it.destroy()
            dhcp = null
            dhcpButton.text = "Eigenen DHCP-Server starten"
            Thread { Root.run("pkill -f de.bmclink.DhcpMain 2>/dev/null") }.start()
            log("DHCP-Server gestoppt.")
            refreshStatus()
            return
        }
        val own = Ipv4.parse(phoneEdit.text.toString())
        if (own == null) {
            log("✖ Handy-IP ungültig."); return
        }
        val start = own.network + 100
        val end = minOf(own.network + 199, own.broadcast - 1)
        if (appliedIp != own) {
            log("Setze zuerst die Handy-IP $own …")
            applyStaticIp { startDhcp(own, start, end) }
        } else {
            startDhcp(own, start, end)
        }
    }

    private fun startDhcp(own: Ipv4, start: Int, end: Int) {
        val iface = appliedIface ?: iface()
        dhcpButton.text = "DHCP-Server stoppen"
        log("Starte DHCP-Server auf $iface (Pool ${intToIp(start)}–${intToIp(end)}) …")
        Thread {
            val p = try {
                RootNet.startDhcp(applicationInfo.sourceDir, iface, own, start, end)
            } catch (e: Exception) {
                ui.post { log("✖ Start fehlgeschlagen: ${e.message}"); dhcpButton.text = "Eigenen DHCP-Server starten" }
                return@Thread
            }
            dhcp = p
            ui.post { refreshStatus() }
            try {
                BufferedReader(InputStreamReader(p.inputStream)).forEachLine { line -> ui.post { onDhcpLine(line) } }
            } catch (_: Exception) {
            }
            ui.post {
                if (dhcp === p) {
                    dhcp = null
                    dhcpButton.text = "Eigenen DHCP-Server starten"
                    log("DHCP-Server-Prozess beendet.")
                    refreshStatus()
                }
            }
        }.start()
    }

    private fun onDhcpLine(line: String) {
        when {
            line.startsWith("LEASE ") -> {
                val p = line.split(" ")
                val mac = p.getOrNull(1) ?: "?"
                val ip = p.getOrNull(2) ?: return
                val host = p.getOrNull(3)?.takeIf { it != "-" }
                log("✔ Gerät $mac${host?.let { " ($it)" } ?: ""} hat $ip bekommen – prüfe …")
                toast("BMC hat $ip bekommen")
                bmcEdit.setText(ip)
                Thread {
                    val h = Scanner(null).probe(ip) ?: Host(ip, false, emptyList(), null)
                    ui.post { addHost(h) }
                }.start()
            }
            line.startsWith("ERROR ") -> log("✖ " + line.removePrefix("ERROR "))
            line.startsWith("INFO ") -> log(line.removePrefix("INFO "))
            line.isNotBlank() -> log(line)
        }
    }

    private fun resetAll() {
        scanner?.cancelled?.set(true)
        val iface = appliedIface ?: iface()
        val ip = appliedIp
        dhcp?.destroy(); dhcp = null
        watchdog?.destroy(); watchdog = null
        appliedIp = null; appliedIface = null
        dhcpButton.text = "Eigenen DHCP-Server starten"
        found.clear(); resultList.removeAllViews(); resultHint.visibility = View.VISIBLE
        if (!rootOk) {
            log("Liste geleert."); return
        }
        Thread {
            val r = RootNet.teardown(iface, ip)
            ui.post { log(if (r.code == 0) "✔ Zurückgesetzt (IP, Routen, DHCP entfernt)." else "Zurücksetzen: ${r.out}"); refreshStatus() }
        }.start()
    }

    // =====================================================================
    // Ergebnisse
    // =====================================================================

    private fun describe(h: Host): String {
        val parts = ArrayList<String>()
        h.vendor?.let { parts += it }
        if (h.ipmi) parts += "IPMI"
        if (h.ports.isNotEmpty()) parts += "Ports " + h.ports.joinToString(",")
        return parts.joinToString(" · ").ifEmpty { "antwortet" }
    }

    private fun addHost(h: Host) {
        found[h.ip] = h
        resultHint.visibility = View.GONE
        resultList.removeAllViews()
        val sorted = found.values.sortedWith(compareByDescending<Host> { it.looksLikeBmc }.thenBy { ipToInt(it.ip)?.toLong()?.and(0xffffffffL) })
        for (x in sorted) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(text((if (x.looksLikeBmc) "● " else "○ ") + x.ip, if (x.looksLikeBmc) cAccent else cText).apply {
                setTypeface(typeface, Typeface.BOLD)
            })
            txt.addView(dim(describe(x)))
            row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Öffnen"
                isAllCaps = false
                setTextColor(Color.parseColor("#0B1A12"))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(cAccent) }
                setOnClickListener { openUrl(x.url()) }
            }, LinearLayout.LayoutParams(dp(88), dp(40)))
            resultList.addView(row)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            toast("Zertifikatswarnung im Browser bestätigen (BMCs sind selbstsigniert)")
        } catch (_: ActivityNotFoundException) {
            toast("Kein Browser gefunden")
        }
    }

    // =====================================================================

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)

    private fun log(s: String) {
        val line = "${timeFmt.format(Date())}  $s"
        val cur = logText.text.toString()
        val next = if (cur.isEmpty()) line else "$line\n$cur"
        logText.text = if (next.length > 12000) next.substring(0, 12000) else next
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
