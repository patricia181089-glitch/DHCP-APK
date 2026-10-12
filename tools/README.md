# switchboard_ip.py – Switchboard-/BMC-IP per IPMI ändern

Für den Fall: Switchboard-BMC (z. B. Inspur NF5488M5) bekommt per DHCP keine Adresse, die feste IP ist unbekannt,
du willst aber auf die Weboberfläche (z. B. fürs CPLD-Update).

**Voraussetzung:** Python 3.7+ (Windows: python.org, beim Installieren „Add to PATH“). Sonst nichts – kein ipmitool nötig.

## Ablauf
1. Laptop per Kabel direkt an den Management-Port des Switchboards.
2. `python switchboard_ip.py` starten (Windows: Doppelklick). Der geführte Modus sucht den BMC,
   zeigt die aktuelle Konfiguration und fragt nach der neuen IP.
3. Laptop-IP ins selbe Netz setzen (das Skript schlägt eine vor), `https://<neue IP>` öffnen, CPLD aktualisieren.

## Einzelbefehle
```
python switchboard_ip.py suchen
python switchboard_ip.py zeigen  fe80::a2b3:ccff:fe11:2233%eth0 -p admin
python switchboard_ip.py setzen  fe80::a2b3:ccff:fe11:2233%12 -p admin --ip 192.168.1.50 --maske 255.255.255.0
python switchboard_ip.py setzen  192.168.1.50 -p admin --dhcp      # zurück auf DHCP
```
- Unter Windows steht hinter dem `%` die Interface-Nummer (wird bei `suchen` passend ausgegeben).
- Standard-Login Inspur: `admin` / `admin`.
- `-k 8` wählt einen bestimmten LAN-Kanal (Standard: alle LAN-Kanäle werden erkannt, gesetzt wird der erste).

## Wenn die Suche nichts findet
- Link-LED am Port an? BMC nach Strom an 2–3 Minuten booten lassen.
- Windows-Firewall: Python für „Öffentliche Netzwerke“ zulassen (sonst kommen die Antworten nicht an).
- IPv6 am Laptop-Adapter nicht deaktivieren.
- Ist IPv6 am BMC ausgeschaltet, hilft nur noch Mitschneiden (Wireshark, Filter `arp or udp port 67 or 68`):
  der BMC verrät beim Booten seine IPv4 per ARP.
