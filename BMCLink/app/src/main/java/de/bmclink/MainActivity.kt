package de.bmclink

import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var swAddrEdit: EditText
    private lateinit var swUserEdit: EditText
    private lateinit var swPassEdit: EditText
    private lateinit var swChanEdit: EditText
    private lateinit var swIpEdit: EditText
    private lateinit var swMaskEdit: EditText
    private lateinit var swGwEdit: EditText
    private lateinit var swList: LinearLayout
    private lateinit var swSearchButton: Button
    @Volatile private var swBusy = false

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

        // ---------- Switchboard / IPMI ----------
        card(root, "Switchboard-IP ändern (IPMI, z. B. Inspur M5)") { c ->
            c.addView(dim("Für BMCs mit unbekannter IP, wenn DHCP nicht geht (z. B. Switchboard-BMC fürs CPLD-Update). " +
                    "Kabel direkt in den Management-Port des Switchboards. Die Suche findet den BMC über IPv6 link-local " +
                    "(fe80::…) – dafür muss die IPv4 nicht passen. Mit Root klappt das am zuverlässigsten."))
            swSearchButton = button("1. Switchboard suchen") { swSearch() }.also { c.addView(it) }
            swList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(swList)

            c.addView(label("BMC-Adresse (fe80::…%eth0 oder IPv4)"))
            swAddrEdit = edit("").also { it.hint = "wird bei der Suche ausgefüllt"; c.addView(it) }
            val cred = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            swUserEdit = edit("admin")
            swPassEdit = edit("admin").also {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            cred.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; addView(label("Benutzer")); addView(swUserEdit)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
            cred.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; addView(label("Passwort")); addView(swPassEdit)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(cred)
            c.addView(button("2. Konfiguration lesen") { swRead() })

            c.addView(label("LAN-Kanal (leer = automatisch)"))
            swChanEdit = edit("").also { it.inputType = InputType.TYPE_CLASS_NUMBER; c.addView(it) }
            c.addView(label("Neue IP"))
            swIpEdit = edit("").also { it.inputType = InputType.TYPE_CLASS_PHONE; it.hint = "z. B. 192.168.1.50"; c.addView(it) }
            c.addView(label("Subnetzmaske"))
            swMaskEdit = edit("255.255.255.0").also { it.inputType = InputType.TYPE_CLASS_PHONE; c.addView(it) }
            c.addView(label("Gateway"))
            swGwEdit = edit("0.0.0.0").also { it.inputType = InputType.TYPE_CLASS_PHONE; c.addView(it) }
            c.addView(button("3. IP am Switchboard setzen") { swSet(dhcp = false) })
            c.addView(button("4. Handy ins neue Netz + Weboberfläche öffnen") { swConnect() })
            c.addView(button("Switchboard zurück auf DHCP", danger = true) { swSet(dhcp = true) })
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

    private fun refreshStatus() {
        val list = NetInfo.list(this)
        val sys = NetInfo.sysLanNames()
        // Interface-Feld automatisch füllen, falls der Standard nicht existiert
        val names = (list.map { it.name } + sys).distinct()
        if (names.isNotEmpty() && iface() !in names && appliedIface == null) ifaceEdit.setText(names.first())

        val cur = list.firstOrNull { it.name == iface() }
        val sb = StringBuilder()
        val color: Int
        when {
            cur == null && iface() !in sys -> {
                sb.append("Kein LAN-Adapter erkannt.\nAdapter per OTG einstecken (Handy muss OTG + den Chip unterstützen).")
                color = cErr
            }
            cur == null || cur.addrs.isEmpty() -> {
                sb.append("${iface()} erkannt, aber ohne IP.\n→ Fall A: Tethering an · Fall B: Handy-IP setzen")
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
    // Switchboard / IPMI
    // =====================================================================

    /** Bindet einen UDP-Socket an das Android-Netz des Adapters (falls es eins gibt). */
    private fun binder(): (java.net.DatagramSocket) -> Unit {
        val net = NetInfo.list(this).firstOrNull { it.name == iface() }?.network
        return { s ->
            if (net != null && android.os.Build.VERSION.SDK_INT >= 22) try {
                net.bindSocket(s)
            } catch (_: Exception) {
            }
        }
    }

    private fun swBegin(): Boolean {
        if (swBusy) {
            toast("Läuft noch …"); return false
        }
        swBusy = true
        return true
    }

    private fun swSearch() {
        if (!swBegin()) return
        val iface = iface()
        swSearchButton.text = "Suche läuft …"
        swList.removeAllViews()
        log("Switchboard-Suche auf $iface (IPv6 link-local + IPMI-Ping) …")
        Thread {
            val hits = LinkedHashMap<String, String>()   // Adresse -> Beschreibung
            try {
                if (rootOk || Root.available(force = true).also { rootOk = it }) {
                    val (neigh, _) = RootNet.v6Neighbors(iface)
                    for ((a, mac) in neigh) hits["$a%$iface"] = "MAC $mac · antwortet auf Ping"
                }
                for ((a, mac) in Ipmi.discover(iface, 3000, binder())) {
                    hits[a] = listOfNotNull(mac?.let { "MAC $it" }, "IPMI ✔").joinToString(" · ")
                }
            } catch (e: Exception) {
                ui.post { log("✖ Suche: ${e.message}") }
            }
            ui.post {
                swBusy = false
                swSearchButton.text = "1. Switchboard suchen"
                if (hits.isEmpty()) {
                    log("✖ Nichts gefunden. Kabel im Management-Port? Link-LED an? BMC fertig gebootet (2–3 min)? " +
                            "Ohne Root: Ethernet-Tethering einschalten, damit der Adapter aktiv ist.")
                    return@post
                }
                log("✔ ${hits.size} Kandidat(en) gefunden. Antworten mit „IPMI ✔“ sind sicher BMCs.")
                val best = hits.entries.firstOrNull { "IPMI" in it.value } ?: hits.entries.first()
                swAddrEdit.setText(best.key)
                for ((a, d) in hits) swList.addView(swRow(a, d))
            }
        }.start()
    }

    private fun swRow(addr: String, desc: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(text(addr, cAccent).apply { textSize = 12.5f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD) })
        txt.addView(dim(desc))
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Wählen"
            isAllCaps = false
            setTextColor(Color.parseColor("#0B1A12"))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(cAccent) }
            setOnClickListener { swAddrEdit.setText(addr) }
        }, LinearLayout.LayoutParams(dp(88), dp(40)))
        return row
    }

    private fun swRead() {
        val addr = swAddrEdit.text.toString().trim()
        if (addr.isEmpty()) {
            log("✖ Erst suchen oder eine BMC-Adresse eintragen."); return
        }
        if (!swBegin()) return
        val user = swUserEdit.text.toString().trim()
        val pass = swPassEdit.text.toString()
        val bind = binder()
        log("Verbinde per IPMI mit $addr …")
        Thread {
            try {
                Ipmi(addr, user, pass, bind = bind).open().use { b ->
                    val infos = b.lanChannels().map { b.lanInfo(it) }
                    ui.post {
                        log("✔ Angemeldet (Cipher-Suite ${b.suite}).")
                        infos.forEach { log("   $it") }
                        if (swChanEdit.text.isBlank()) swChanEdit.setText(infos.first().channel.toString())
                        if (swIpEdit.text.isBlank()) swIpEdit.setText(suggestIp(infos.first()))
                        val cur = infos.first()
                        if (cur.mask != "?" && cur.mask != "0.0.0.0" && swMaskEdit.text.toString() == "255.255.255.0") swMaskEdit.setText(cur.mask)
                    }
                }
            } catch (e: Exception) {
                ui.post { log("✖ ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                swBusy = false
            }
        }.start()
    }

    /** Vorschlag für die neue IP: ins Netz des Adapters (z. B. Tethering), sonst aktuelle IP, sonst 192.168.1.50. */
    private fun suggestIp(cur: LanInfo): String {
        val own = NetInfo.list(this).firstOrNull { it.name == iface() }?.addrs?.firstOrNull()
        if (own != null) {
            val cand = own.network + 200
            if (own.contains(cand) && cand != own.addr) {
                swMaskEdit.setText(intToIp(own.mask))
                return intToIp(cand)
            }
        }
        if (cur.ip != "?" && cur.ip != "0.0.0.0") return cur.ip
        return "192.168.1.50"
    }

    private fun swSet(dhcp: Boolean) {
        val chText = swChanEdit.text.toString().trim()
        val ip = swIpEdit.text.toString().trim()
        val mask = swMaskEdit.text.toString().trim()
        val gw = swGwEdit.text.toString().trim().ifEmpty { "0.0.0.0" }
        if (!dhcp) {
            if (ipToInt(ip) == null) { log("✖ Neue IP ungültig."); return }
            if (ipToInt(mask) == null) { log("✖ Maske ungültig."); return }
            if (ipToInt(gw) == null) { log("✖ Gateway ungültig."); return }
        }
        val what = if (dhcp) "auf DHCP stellen" else "auf $ip / $mask (GW $gw) setzen"
        AlertDialog.Builder(this)
            .setTitle("Switchboard-IP ändern?")
            .setMessage("Kanal ${chText.ifEmpty { "automatisch" }} $what.\n\n" +
                    "Die Verbindung über fe80::… bleibt dabei bestehen.")
            .setPositiveButton("Ja, setzen") { _, _ -> swDoSet(chText.toIntOrNull(), dhcp, ip, mask, gw) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun swDoSet(chIn: Int?, dhcp: Boolean, ip: String, mask: String, gw: String) {
        val addr = swAddrEdit.text.toString().trim()
        if (addr.isEmpty()) {
            log("✖ Erst suchen oder eine BMC-Adresse eintragen."); return
        }
        if (!swBegin()) return
        val user = swUserEdit.text.toString().trim()
        val pass = swPassEdit.text.toString()
        val bind = binder()
        log(if (dhcp) "Stelle Switchboard auf DHCP …" else "Setze Switchboard-IP $ip / $mask, GW $gw …")
        Thread {
            try {
                Ipmi(addr, user, pass, bind = bind).open().use { b ->
                    val ch = chIn ?: b.lanChannels().first()
                    val before = b.lanInfo(ch)
                    if (dhcp) b.setDhcp(ch) else b.setStatic(ch, ip, mask, gw)
                    Thread.sleep(2000)
                    val after = b.lanInfo(ch)
                    ui.post {
                        log("   vorher:  $before")
                        log("   nachher: $after")
                        if (!dhcp && after.ip == ip) {
                            log("✔ Switchboard hat jetzt $ip. Weiter mit Schritt 4.")
                            toast("Switchboard-IP gesetzt")
                        } else if (!dhcp) {
                            log("⚠ Gelesene IP weicht ab – evtl. übernimmt der BMC sie verzögert. Gleich nochmal „Konfiguration lesen“.")
                        } else {
                            log("✔ Auf DHCP umgestellt.")
                        }
                    }
                }
            } catch (e: Exception) {
                ui.post { log("✖ ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                swBusy = false
            }
        }.start()
    }

    /** Handy in das Netz der neuen Switchboard-IP bringen und die Weboberfläche öffnen. */
    private fun swConnect() {
        val ip = swIpEdit.text.toString().trim()
        val bmc = ipToInt(ip)
        val mask = ipToInt(swMaskEdit.text.toString().trim())
        if (bmc == null || mask == null) {
            log("✖ Neue IP/Maske im Switchboard-Bereich eintragen."); return
        }
        val prefix = Integer.bitCount(mask).coerceIn(8, 30)
        val own = NetInfo.list(this).firstOrNull { it.name == iface() }?.addrs?.firstOrNull()
        if (own != null && own.contains(bmc)) {
            log("Handy ist schon im Netz (${own}). Öffne https://$ip …")
            openUrl("https://$ip"); return
        }
        if (!rootOk) {
            log("✖ Ohne Root kann das Handy keine feste IP setzen. Tipp: Ethernet-Tethering einschalten, " +
                    "dann „Konfiguration lesen“ – die vorgeschlagene IP liegt dann im Tethering-Netz.")
            return
        }
        val net = Ipv4(bmc, prefix)
        val phone = if (net.broadcast - 1 != bmc) net.broadcast - 1 else net.network + 1
        bmcEdit.setText(ip)
        phoneEdit.setText("${intToIp(phone)}/$prefix")
        log("Setze Handy-IP ${intToIp(phone)}/$prefix und öffne danach https://$ip …")
        applyStaticIp { openUrl("https://$ip") }
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
