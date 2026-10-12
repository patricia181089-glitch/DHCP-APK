# BMC Link

Android-App, mit der du per USB-LAN-Adapter (Micro-USB/USB-C OTG) vom Handy auf einen Server-BMC zugreifst (Dell iDRAC, HPE iLO, Supermicro IPMI, Lenovo XClarity …).

## Funktionen

**Fall A: BMC steht auf DHCP (ohne Root)**
- Öffnet direkt die Tethering-Einstellungen. Ab Android 11 dort **Ethernet-Tethering** einschalten. Das Handy verteilt dann selbst IP-Adressen.
- **BMC suchen** scannt das Netz am Adapter:
  - IPMI-Ping (RMCP/ASF, UDP 623)
  - Ports 443/80/22/5900
  - Herstellererkennung über Webseite und Redfish
- Gefundene Geräte lassen sich mit einem Tipp im Browser öffnen.

**Fall B: BMC hat eine feste IP (Root nötig)**
- Voreinstellungen für Dell iDRAC (192.168.0.120) und Lenovo XCC (192.168.70.125), oder eigene IP eintragen.
- Setzt eine feste IP auf den Adapter und leitet das BMC-Netz per Policy-Routing über den Adapter. Damit erreicht auch der Browser den BMC.
- Ein Wächter setzt die IP alle 3 s neu, falls Android sie wieder entfernt.
- **Eigener DHCP-Server** (Root): Alternative zum Tethering, z. B. bei Android < 11. Er läuft als root-Prozess, ist fest an den Adapter gebunden und antwortet nie ins WLAN. Sobald der BMC eine Adresse bekommt, wird sie angezeigt und geprüft.
- **Alles zurücksetzen** entfernt IP, Routen, Firewall-Regeln und den DHCP-Server.

**Switchboard-IP ändern (IPMI, z. B. Inspur M5 GPU-Server)**
Für BMCs mit unbekannter IP, wenn DHCP nicht geht – z. B. das Switchboard-BMC, um dort das CPLD zu aktualisieren.
1. Kabel direkt in den Management-Port des Switchboards.
2. **Switchboard suchen**: findet den BMC über IPv6 link-local (`fe80::…%eth0`) und IPMI-Ping. Dafür muss die IPv4 nicht passen. Mit Root zusätzlich per Ping an alle Knoten + Nachbartabelle (zuverlässiger).
3. **Konfiguration lesen**: meldet sich per IPMI an (Standard Inspur: admin/admin) und zeigt IP, Maske, Gateway, Quelle (statisch/DHCP) und MAC aller LAN-Kanäle.
4. **IP am Switchboard setzen**: feste IPv4/Maske/Gateway (mit Rückfrage), danach wird zur Kontrolle neu gelesen.
5. **Handy ins neue Netz + Weboberfläche öffnen**: setzt (Root) eine passende Handy-IP und öffnet `https://<neue IP>` fürs CPLD-Update. Ohne Root: Ethernet-Tethering an, dann schlägt „Konfiguration lesen“ eine IP im Tethering-Netz vor.

Das Gleiche gibt es als Laptop-Skript ohne Zusatzsoftware: `tools/switchboard_ip.py` (siehe `tools/README.md`).

## APK bauen

### Variante 1: GitHub (ohne Installation)
1. Neues (gerne privates) Repository auf github.com anlegen.
2. Den Inhalt dieses Ordners hochladen, inklusive des versteckten Ordners `.github`.
   - Mit git: `git init && git add . && git commit -m init && git push`.
   - Im Browser: „Add file → Upload files“. Achtung, der Ordner `.github` muss mit hoch.
3. Reiter **Actions** öffnen. Der Lauf „APK bauen“ startet automatisch und dauert ca. 3–5 Minuten.
4. Den fertigen Lauf öffnen, unten unter **Artifacts** „BMCLink-apk“ herunterladen, entpacken und `app-release.apk` aufs Handy kopieren und installieren.

### Variante 2: Android Studio
Ordner öffnen, Gradle-Sync abwarten, dann *Build → Build APK(s)*.

## Verkabelung und Tipps
- Das Kabel muss in den **dedizierten Management-Port** (iDRAC/iLO/IPMI), nicht in einen normalen Netzwerkport.
- Wenn die App „Kein LAN-Adapter erkannt“ meldet, unterstützt das Handy OTG oder den Chip des Adapters nicht. ASIX AX88772 und Realtek RTL8152/8153 laufen am häufigsten.
- BMC-Webseiten haben selbstsignierte Zertifikate. Die Warnung im Browser bestätigen.
- Alte Java-KVM-Konsolen (`.jnlp`) laufen auf dem Handy nicht, die HTML5-Konsolen schon.

## Technik
- Kotlin, keine Fremdbibliotheken, minSdk 21 (Android 5+).
- Der DHCP-Server (`DhcpMain`) wird per `su` mit `app_process` aus der eigenen APK gestartet, weil nur root Port 67 öffnen darf.
- Policy-Routing nutzt Routing-Tabelle 1213 mit Regel-Priorität 99 (IPv6: nur `oif`-Regel für den Adapter).
- IPMI 2.0 (RMCP+) ist selbst implementiert (`Ipmi.kt`): Cipher-Suite 3 (HMAC-SHA1/AES-128), Fallback 17 (SHA256).
