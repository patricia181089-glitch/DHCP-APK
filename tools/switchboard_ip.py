#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Switchboard-IP – IP eines BMC (z. B. Switchboard-BMC im Inspur NF5488M5) per IPMI ändern,
auch wenn DHCP nicht geht und die aktuelle IPv4 unbekannt ist.

Vorgehen
  1. Laptop per Kabel direkt an den Management-Port des Switchboards.
  2. "suchen": findet den BMC über IPv6 link-local (fe80::…, braucht KEINE passende IPv4)
     und per IPMI-Ping (IPv4-Broadcast).
  3. "zeigen": liest per IPMI die aktuelle IPv4-Konfiguration aller LAN-Kanäle.
  4. "setzen": stellt eine feste IPv4/Maske/Gateway ein.
  5. Laptop-IP ins selbe Netz, Weboberfläche öffnen, CPLD-Update.

Ohne Argumente startet ein geführter Modus (Doppelklick unter Windows reicht).
Keine Fremdbibliotheken nötig (Python 3.7+). Ist "cryptography" installiert, wird es für AES genutzt.

Beispiele
  python switchboard_ip.py suchen
  python switchboard_ip.py zeigen fe80::a2b3:ccff:fe11:2233%eth0 -u admin -p admin
  python switchboard_ip.py setzen fe80::a2b3:ccff:fe11:2233%12 --ip 192.168.1.50 --maske 255.255.255.0 --gw 0.0.0.0
"""

import argparse
import getpass
import hashlib
import hmac
import ipaddress
import os
import platform
import re
import select
import socket
import struct
import subprocess
import sys
import time

# =============================================================================
# AES-128 (nur falls "cryptography" fehlt; kompakt, reicht für ein paar Pakete)
# =============================================================================

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

    def aes_cbc_encrypt(key, iv, data):
        e = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
        return e.update(data) + e.finalize()

    def aes_cbc_decrypt(key, iv, data):
        d = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
        return d.update(data) + d.finalize()

except Exception:  # pragma: no cover - Fallback
    _SBOX = [0] * 256
    _INV = [0] * 256

    def _init_sbox():
        p = q = 1
        while True:
            p = p ^ ((p << 1) & 0xFF) ^ (0x1B if p & 0x80 else 0)
            q ^= q << 1
            q ^= q << 2
            q ^= q << 4
            q &= 0xFF
            if q & 0x80:
                q ^= 0x09
            x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4))
            x = (x ^ 0x63) & 0xFF
            _SBOX[p] = x
            _INV[x] = p
            if p == 1:
                break
        _SBOX[0] = 0x63
        _INV[0x63] = 0

    _init_sbox()

    def _xt(a):
        return ((a << 1) ^ 0x1B) & 0xFF if a & 0x80 else a << 1

    def _mul(a, b):
        r = 0
        while b:
            if b & 1:
                r ^= a
            a = _xt(a)
            b >>= 1
        return r

    def _expand(key):
        w = list(key)
        rcon = 1
        while len(w) < 176:
            t = w[-4:]
            if len(w) % 16 == 0:
                t = [_SBOX[t[1]] ^ rcon, _SBOX[t[2]], _SBOX[t[3]], _SBOX[t[0]]]
                rcon = _xt(rcon)
            w += [w[len(w) - 16 + i] ^ t[i] for i in range(4)]
        return [w[16 * r:16 * r + 16] for r in range(11)]

    def _enc_block(rk, b):
        s = [b[i] ^ rk[0][i] for i in range(16)]
        for r in range(1, 11):
            s = [_SBOX[x] for x in s]
            s = [s[(i + 4 * (i % 4)) % 16] for i in range(16)]
            if r != 10:
                n = []
                for c in range(4):
                    a = s[4 * c:4 * c + 4]
                    n += [_xt(a[0]) ^ _xt(a[1]) ^ a[1] ^ a[2] ^ a[3],
                          a[0] ^ _xt(a[1]) ^ _xt(a[2]) ^ a[2] ^ a[3],
                          a[0] ^ a[1] ^ _xt(a[2]) ^ _xt(a[3]) ^ a[3],
                          _xt(a[0]) ^ a[0] ^ a[1] ^ a[2] ^ _xt(a[3])]
                s = n
            s = [s[i] ^ rk[r][i] for i in range(16)]
        return s

    def _dec_block(rk, b):
        s = [b[i] ^ rk[10][i] for i in range(16)]
        for r in range(9, -1, -1):
            s = [s[(i - 4 * (i % 4)) % 16] for i in range(16)]
            s = [_INV[x] for x in s]
            s = [s[i] ^ rk[r][i] for i in range(16)]
            if r != 0:
                n = []
                for c in range(4):
                    a = s[4 * c:4 * c + 4]
                    n += [_mul(a[0], 14) ^ _mul(a[1], 11) ^ _mul(a[2], 13) ^ _mul(a[3], 9),
                          _mul(a[0], 9) ^ _mul(a[1], 14) ^ _mul(a[2], 11) ^ _mul(a[3], 13),
                          _mul(a[0], 13) ^ _mul(a[1], 9) ^ _mul(a[2], 14) ^ _mul(a[3], 11),
                          _mul(a[0], 11) ^ _mul(a[1], 13) ^ _mul(a[2], 9) ^ _mul(a[3], 14)]
                s = n
        return s

    def aes_cbc_encrypt(key, iv, data):
        rk = _expand(key)
        prev, out = list(iv), bytearray()
        for i in range(0, len(data), 16):
            prev = _enc_block(rk, [data[i + j] ^ prev[j] for j in range(16)])
            out += bytes(prev)
        return bytes(out)

    def aes_cbc_decrypt(key, iv, data):
        rk = _expand(key)
        prev, out = list(iv), bytearray()
        for i in range(0, len(data), 16):
            blk = list(data[i:i + 16])
            d = _dec_block(rk, blk)
            out += bytes(d[j] ^ prev[j] for j in range(16))
            prev = blk
        return bytes(out)


# =============================================================================
# IPMI 2.0 / RMCP+ Client (Cipher-Suite 3 = SHA1/AES, Fallback 17 = SHA256/AES)
# =============================================================================

RMCP_IPMI = b"\x06\x00\xff\x07"
ASF_PING = b"\x06\x00\xff\x06" + b"\x00\x00\x11\xbe" + b"\x80\x2a\x00\x00"

PRIV_ADMIN = 4

RAKP_STATUS = {
    0x01: "keine freien Ressourcen (zu viele Sitzungen offen?)",
    0x02: "ungültige Session-ID",
    0x09: "angeforderte Rolle/Rechte nicht erlaubt",
    0x0D: "Benutzername unbekannt",
    0x0F: "Prüfsumme ungültig – Passwort falsch?",
    0x11: "Cipher-Suite wird nicht unterstützt",
    0x12: "Cipher-Suite wird nicht unterstützt",
    0x13: "Benutzer/Rolle nicht erlaubt",
}

CC_TEXT = {
    0x80: "Parameter nicht unterstützt",
    0x81: "Schreiben gerade gesperrt (Set in Progress)",
    0x82: "Parameter ist schreibgeschützt",
    0xC1: "Befehl nicht unterstützt",
    0xC9: "Parameter außerhalb des Bereichs",
    0xCC: "ungültige Daten",
    0xD4: "fehlende Rechte (Admin-Benutzer nötig)",
    0xD5: "Befehl gerade nicht möglich",
}


class IpmiError(Exception):
    pass


def _chk(data):
    return (-sum(data)) & 0xFF


def resolve(host):
    """Liefert (family, sockaddr). Akzeptiert IPv4, IPv6, fe80::…%iface."""
    infos = socket.getaddrinfo(host, 623, socket.AF_UNSPEC, socket.SOCK_DGRAM)
    return infos[0][0], infos[0][4]


class Ipmi:
    def __init__(self, host, user="admin", password="admin", timeout=1.5, retries=3, verbose=False):
        self.host = host
        self.user = user.encode()
        self.kuid = password.encode()[:20].ljust(20, b"\x00")
        self.timeout = timeout
        self.retries = retries
        self.verbose = verbose
        self.family, self.addr = resolve(host)
        self.sock = socket.socket(self.family, socket.SOCK_DGRAM)
        self.sock.settimeout(timeout)
        self.sid = 0          # Session-ID des BMC
        self.csid = 0         # unsere Session-ID
        self.seq = 0
        self.rqseq = 0
        self.k1 = self.aes = None
        self.hash = hashlib.sha1
        self.icv_len = 12
        self.active = False

    # --------------------------------------------------------------- Transport
    def _xfer(self, pkt, check):
        last = None
        for attempt in range(self.retries):
            self.sock.sendto(pkt, self.addr)
            end = time.time() + self.timeout
            while time.time() < end:
                try:
                    data, _ = self.sock.recvfrom(4096)
                except socket.timeout:
                    break
                r = check(data)
                if r is not None:
                    return r
            last = "Zeitüberschreitung"
        raise IpmiError("%s antwortet nicht (%s)" % (self.host, last))

    def _hmac(self, key, data):
        return hmac.new(key, data, self.hash).digest()

    # --------------------------------------------------------------- Vor der Sitzung
    def _lan15(self, netfn, cmd, data):
        """IPMI-1.5-Nachricht ohne Sitzung (nur Get Channel Auth Caps)."""
        hdr = bytes([0x20, netfn << 2])
        body = bytes([0x81, 0x00, cmd]) + data
        msg = hdr + bytes([_chk(hdr)]) + body + bytes([_chk(body)])
        pkt = RMCP_IPMI + b"\x00" + b"\x00" * 8 + bytes([len(msg)]) + msg

        def check(d):
            if len(d) >= 21 and d[:4] == RMCP_IPMI and d[4] == 0 and d[19] == cmd:
                return d[20], d[21:-1]
            return None
        return self._xfer(pkt, check)

    def _v2(self, ptype, payload):
        """Unauthentifizierte IPMI-2.0-Nachricht (Open Session / RAKP)."""
        pkt = RMCP_IPMI + bytes([0x06, ptype]) + b"\x00" * 8 + struct.pack("<H", len(payload)) + payload
        want = ptype + 1

        def check(d):
            if len(d) >= 16 and d[:4] == RMCP_IPMI and d[4] == 0x06 and (d[5] & 0x3F) == want:
                ln = struct.unpack("<H", d[14:16])[0]
                return d[16:16 + ln]
            return None
        return self._xfer(pkt, check)

    def open(self, suites=(3, 17)):
        try:
            self._lan15(0x06, 0x38, bytes([0x8E, PRIV_ADMIN]))   # Get Channel Auth Caps (IPMI 2.0)
        except IpmiError:
            raise IpmiError("%s antwortet nicht auf IPMI (UDP 623). Ist IPMI-over-LAN aktiv, stimmt die Adresse?" % self.host)
        err = None
        for s in suites:
            try:
                self._open(s)
                return self
            except IpmiError as e:
                err = e
                if "Cipher" not in str(e):
                    raise
        raise err

    def _open(self, suite):
        if suite == 3:
            auth, integ, self.hash, self.icv_len = 1, 1, hashlib.sha1, 12
        else:
            auth, integ, self.hash, self.icv_len = 3, 4, hashlib.sha256, 16
        self.csid = struct.unpack("<I", os.urandom(4))[0] | 1
        tag = 0x42
        req = bytes([tag, PRIV_ADMIN, 0, 0]) + struct.pack("<I", self.csid)
        req += bytes([0, 0, 0, 8, auth, 0, 0, 0])
        req += bytes([1, 0, 0, 8, integ, 0, 0, 0])
        req += bytes([2, 0, 0, 8, 1, 0, 0, 0])        # AES-CBC-128
        r = self._v2(0x10, req)
        if r[1] != 0:
            raise IpmiError("Open Session abgelehnt: %s" % RAKP_STATUS.get(r[1], "Status 0x%02x" % r[1]))
        self.sid = struct.unpack("<I", r[8:12])[0]
        sid_b, csid_b = r[8:12], struct.pack("<I", self.csid)

        # RAKP 1/2
        rm = os.urandom(16)
        role = 0x10 | PRIV_ADMIN          # Name-only-Lookup + Admin
        rakp1 = bytes([tag, 0, 0, 0]) + sid_b + rm + bytes([role, 0, 0, len(self.user)]) + self.user
        try:
            r2 = self._v2(0x12, rakp1)
        except IpmiError:
            raise IpmiError("Keine Antwort bei der Anmeldung – Benutzername falsch?")
        if r2[1] != 0:
            raise IpmiError("Anmeldung fehlgeschlagen: %s" % RAKP_STATUS.get(r2[1], "Status 0x%02x" % r2[1]))
        rc, guid, code = r2[8:24], r2[24:40], r2[40:]
        tail = bytes([role, len(self.user)]) + self.user
        exp = self._hmac(self.kuid, csid_b + sid_b + rm + rc + guid + tail)
        if not hmac.compare_digest(exp, code[:len(exp)]):
            raise IpmiError("Anmeldung fehlgeschlagen: Passwort falsch")
        sik = self._hmac(self.kuid, rm + rc + tail)
        n = self.hash().digest_size
        self.k1 = self._hmac(sik, b"\x01" * n)
        self.aes = self._hmac(sik, b"\x02" * n)[:16]

        # RAKP 3/4
        rakp3 = bytes([tag, 0, 0, 0]) + sid_b + self._hmac(self.kuid, rc + csid_b + tail)
        r4 = self._v2(0x14, rakp3)
        if r4[1] != 0:
            raise IpmiError("Anmeldung fehlgeschlagen: %s" % RAKP_STATUS.get(r4[1], "Status 0x%02x" % r4[1]))
        icv = self._hmac(sik, rm + sid_b + guid)[:self.icv_len]
        if not hmac.compare_digest(icv, r4[8:8 + self.icv_len]):
            raise IpmiError("Sitzungsschlüssel stimmt nicht (RAKP4)")
        self.seq = 0
        self.active = True
        if self.verbose:
            print("  Sitzung offen (Cipher-Suite %d)" % suite)

    # --------------------------------------------------------------- Befehle
    def raw(self, netfn, cmd, data=b""):
        if not self.active:
            raise IpmiError("keine Sitzung")
        self.rqseq = (self.rqseq + 1) & 0x3F
        self.seq = (self.seq + 1) & 0xFFFFFFFF or 1
        hdr = bytes([0x20, netfn << 2])
        body = bytes([0x81, self.rqseq << 2, cmd]) + bytes(data)
        msg = hdr + bytes([_chk(hdr)]) + body + bytes([_chk(body)])
        padn = (16 - (len(msg) + 1) % 16) % 16
        plain = msg + bytes(range(1, padn + 1)) + bytes([padn])
        iv = os.urandom(16)
        payload = iv + aes_cbc_encrypt(self.aes, iv, plain)
        sess = bytes([0x06, 0xC0]) + struct.pack("<II", self.sid, self.seq) + struct.pack("<H", len(payload)) + payload
        pad = (4 - (len(sess) + 2) % 4) % 4
        sess += b"\xff" * pad + bytes([pad, 0x07])
        pkt = RMCP_IPMI + sess + self._hmac(self.k1, sess)[:self.icv_len]
        seq, rnetfn = self.rqseq, netfn | 1

        def check(d):
            if len(d) < 16 + 16 or d[:4] != RMCP_IPMI or d[4] != 0x06 or (d[5] & 0x3F) != 0:
                return None
            if struct.unpack("<I", d[6:10])[0] != self.csid:
                return None
            ln = struct.unpack("<H", d[14:16])[0]
            p = d[16:16 + ln]
            if d[5] & 0x40:
                end = len(d) - self.icv_len
                if not hmac.compare_digest(self._hmac(self.k1, d[4:end])[:self.icv_len], d[end:]):
                    return None
            if d[5] & 0x80:
                p = aes_cbc_decrypt(self.aes, p[:16], p[16:])
                p = p[:len(p) - p[-1] - 1]
            if len(p) < 7 or (p[1] >> 2) != rnetfn or (p[4] >> 2) != seq or p[5] != cmd:
                return None
            return p[6], p[7:-1]
        return self._xfer(pkt, check)

    def cmd(self, netfn, cmd, data=b"", what="Befehl"):
        cc, d = self.raw(netfn, cmd, data)
        if cc != 0:
            raise IpmiError("%s: Fehlercode 0x%02x (%s)" % (what, cc, CC_TEXT.get(cc, "unbekannt")))
        return d

    def close(self):
        if self.active:
            try:
                self.retries = 1
                self.raw(0x06, 0x3C, struct.pack("<I", self.sid))
            except Exception:
                pass
            self.active = False
        self.sock.close()

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()

    # --------------------------------------------------------------- LAN-Konfiguration
    def lan_channels(self):
        chans = []
        for ch in range(1, 12):
            try:
                cc, d = self.raw(0x06, 0x42, bytes([ch]))   # Get Channel Info
            except IpmiError:
                continue
            if cc == 0 and len(d) >= 2 and (d[1] & 0x7F) == 0x04:   # 802.3 LAN
                chans.append(ch)
        return chans or [1]

    def get_param(self, ch, p):
        d = self.cmd(0x0C, 0x02, bytes([ch, p, 0, 0]), "LAN-Parameter %d lesen" % p)
        return d[1:]

    def set_param(self, ch, p, data, what):
        self.cmd(0x0C, 0x01, bytes([ch, p]) + bytes(data), what)

    def lan_info(self, ch):
        info = {"kanal": ch}

        def g(p):
            try:
                return self.get_param(ch, p)
            except IpmiError:
                return None
        src = g(4)
        ip, mask, gw, mac = g(3), g(6), g(12), g(5)
        v6 = g(51)
        info["quelle"] = {0: "unbekannt", 1: "statisch", 2: "DHCP", 3: "BIOS", 4: "andere"}.get(src[0] & 0x0F, "?") if src else "?"
        info["ip"] = str(ipaddress.IPv4Address(bytes(ip[:4]))) if ip else "?"
        info["maske"] = str(ipaddress.IPv4Address(bytes(mask[:4]))) if mask else "?"
        info["gateway"] = str(ipaddress.IPv4Address(bytes(gw[:4]))) if gw else "?"
        info["mac"] = ":".join("%02x" % b for b in mac[:6]) if mac else "?"
        if v6:
            info["ip_version"] = {0: "nur IPv4", 1: "nur IPv6", 2: "IPv4 + IPv6"}.get(v6[0], "?")
        return info

    def set_static(self, ch, ip, mask, gw):
        ip_b = ipaddress.IPv4Address(ip).packed
        mask_b = ipaddress.IPv4Address(mask).packed
        gw_b = ipaddress.IPv4Address(gw).packed
        try:
            self.set_param(ch, 0, [1], "Set in Progress")
        except IpmiError:
            pass
        try:
            self.set_param(ch, 4, [1], "Adressquelle auf statisch")
            self.set_param(ch, 3, ip_b, "IP-Adresse")
            self.set_param(ch, 6, mask_b, "Subnetzmaske")
            self.set_param(ch, 12, gw_b, "Gateway")
        finally:
            try:
                self.set_param(ch, 0, [0], "Set complete")
            except IpmiError:
                pass

    def set_dhcp(self, ch):
        self.set_param(ch, 4, [2], "Adressquelle auf DHCP")


# =============================================================================
# Suche
# =============================================================================

def _mac_from_ll(addr):
    """EUI-64-Link-Local (fe80::xxff:fexx:xxxx) -> MAC, sonst None."""
    try:
        a = ipaddress.IPv6Address(addr.split("%")[0]).packed[8:]
    except ValueError:
        return None
    if a[3] != 0xFF or a[4] != 0xFE:
        return None
    mac = bytes([a[0] ^ 0x02, a[1], a[2], a[5], a[6], a[7]])
    return ":".join("%02x" % b for b in mac)


def _scopes():
    out = []
    try:
        for idx, name in socket.if_nameindex():
            if name == "lo" or name.startswith("loopback"):
                continue
            out.append((idx, name))
    except Exception:
        pass
    return out


def _fmt_ll(addr, scope_id, names):
    a = addr.split("%")[0]
    if platform.system() == "Windows":
        return "%s%%%d" % (a, scope_id)
    return "%s%%%s" % (a, names.get(scope_id, scope_id))


def discover(timeout=3.0, quiet=False):
    """IPMI-Presence-Ping an ff02::1 auf jeder Schnittstelle + IPv4-Broadcast. Liefert Liste von Funden."""
    found = {}
    names = {i: n for i, n in _scopes()}
    socks = []

    try:
        s6 = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
        s6.bind(("::", 0))
        for idx, name in names.items():
            try:
                s6.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_MULTICAST_IF, idx)
                for _ in range(2):
                    s6.sendto(ASF_PING, ("ff02::1", 623, 0, idx))
            except OSError:
                pass
        socks.append(s6)
    except OSError as e:
        if not quiet:
            print("  IPv6 nicht verfügbar: %s" % e)

    try:
        s4 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s4.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s4.bind(("0.0.0.0", 0))
        for _ in range(2):
            try:
                s4.sendto(ASF_PING, ("255.255.255.255", 623))
            except OSError:
                pass
        socks.append(s4)
    except OSError:
        pass

    end = time.time() + timeout
    while socks and time.time() < end:
        r, _, _ = select.select(socks, [], [], max(0.05, end - time.time()))
        for s in r:
            try:
                data, src = s.recvfrom(512)
            except OSError:
                continue
            if len(data) < 9 or data[0] != 0x06 or data[8] != 0x40:
                continue
            if s.family == socket.AF_INET6:
                key = _fmt_ll(src[0], src[3], names) if src[0].lower().startswith("fe80") else src[0]
                iface = names.get(src[3], "")
            else:
                key, iface = src[0], ""
            if key not in found:
                found[key] = {"adresse": key, "iface": iface, "mac": _mac_from_ll(src[0]) if ":" in src[0] else None}
    for s in socks:
        s.close()

    # Linux/macOS: zusätzlich ICMPv6-Ping an alle Knoten (findet BMCs, deren IPMI kein IPv6 kann)
    if platform.system() != "Windows":
        for idx, name in names.items():
            try:
                out = subprocess.run(["ping", "-6", "-c", "2", "-w", "2", "ff02::1%" + name],
                                     capture_output=True, text=True, timeout=5).stdout
            except Exception:
                continue
            for m in re.finditer(r"from (fe80::[0-9a-f:]+)", out):
                key = "%s%%%s" % (m.group(1), name)
                if key not in found and not _is_local(m.group(1)):
                    found[key] = {"adresse": key, "iface": name, "mac": _mac_from_ll(m.group(1)), "nur_ping": True}
    return list(found.values())


def _is_local(addr):
    try:
        for fam, _, _, _, sa in socket.getaddrinfo(socket.gethostname(), None):
            if sa[0].split("%")[0] == addr:
                return True
        with open("/proc/net/if_inet6") as f:
            hexa = ipaddress.IPv6Address(addr).exploded.replace(":", "")
            return any(line.startswith(hexa) for line in f)
    except Exception:
        return False


# =============================================================================
# Ausgabe / CLI
# =============================================================================

def print_info(info):
    print("  Kanal %d:  %s  Maske %s  GW %s  (%s)  MAC %s%s" % (
        info["kanal"], info["ip"], info["maske"], info["gateway"], info["quelle"], info["mac"],
        ("  · " + info["ip_version"]) if "ip_version" in info else ""))


def cmd_suchen(args):
    print("Suche BMCs (IPv6 link-local + IPv4-Broadcast) …")
    res = discover(args.timeout)
    if not res:
        print("\nNichts gefunden. Prüfen:\n"
              "  - Kabel im Management-Port des Switchboards, Link-LED an?\n"
              "  - Netzwerkkarte des Laptops aktiv (IPv6 nicht deaktiviert)?\n"
              "  - Windows-Firewall: Python für 'Öffentliche Netzwerke' erlauben\n"
              "  - BMC fertig gebootet? (nach Strom an 2–3 Minuten warten)")
        return 1
    print()
    for r in res:
        extra = "  MAC %s" % r["mac"] if r.get("mac") else ""
        hint = "  (antwortet nur auf Ping, nicht auf IPMI-Ping)" if r.get("nur_ping") else ""
        print("  %s%s%s" % (r["adresse"], extra, hint))
    print("\nWeiter mit:  zeigen <adresse>")
    return 0


def _connect(args):
    pw = args.passwort if args.passwort is not None else getpass.getpass("Passwort für %s: " % args.benutzer)
    return Ipmi(args.adresse, args.benutzer, pw, verbose=True).open()


def cmd_zeigen(args):
    with _connect(args) as b:
        chans = [args.kanal] if args.kanal else b.lan_channels()
        for ch in chans:
            print_info(b.lan_info(ch))
    return 0


def cmd_setzen(args):
    with _connect(args) as b:
        ch = args.kanal or b.lan_channels()[0]
        print("Vorher:")
        print_info(b.lan_info(ch))
        if args.dhcp:
            b.set_dhcp(ch)
        else:
            b.set_static(ch, args.ip, args.maske, args.gw)
        time.sleep(2)
        print("Nachher:")
        info = b.lan_info(ch)
        print_info(info)
        if not args.dhcp and info["ip"] != args.ip:
            print("⚠ Die gelesene IP weicht ab – evtl. übernimmt der BMC sie erst nach einigen Sekunden. "
                  "Mit 'zeigen' nochmal prüfen.")
    if not args.dhcp:
        print("\nFertig. Jetzt den Laptop ins selbe Netz bringen (z. B. %s) und https://%s öffnen."
              % (_suggest_own(args.ip, args.maske), args.ip))
    return 0


def _suggest_own(ip, mask):
    net = ipaddress.IPv4Network("%s/%s" % (ip, mask), strict=False)
    hosts = [h for h in (net.broadcast_address - 1, net.network_address + 1) if str(h) != ip]
    return "%s / %s" % (hosts[0], mask)


def wizard():
    print("=" * 64)
    print(" Switchboard-IP ändern (IPMI) – geführter Modus")
    print("=" * 64)
    print("Laptop per Kabel direkt an den Management-Port des Switchboards stecken.\n")
    input("Enter = Suche starten … ")
    res = discover(4.0)
    addr = None
    if res:
        print("\nGefunden:")
        for i, r in enumerate(res, 1):
            print("  [%d] %s%s" % (i, r["adresse"], "  MAC " + r["mac"] if r.get("mac") else ""))
        sel = input("\nNummer wählen oder Adresse eintippen [1]: ").strip() or "1"
        addr = res[int(sel) - 1]["adresse"] if sel.isdigit() and 0 < int(sel) <= len(res) else sel
    else:
        print("\nNichts gefunden (Kabel/Link prüfen, Firewall erlauben).")
        addr = input("Adresse manuell eingeben (leer = Ende): ").strip()
        if not addr:
            return 1
    user = input("Benutzer [admin]: ").strip() or "admin"
    pw = getpass.getpass("Passwort [admin]: ") or "admin"
    try:
        with Ipmi(addr, user, pw, verbose=True).open() as b:
            chans = b.lan_channels()
            infos = [b.lan_info(c) for c in chans]
            print("\nAktuelle Konfiguration:")
            for i in infos:
                print_info(i)
            ch = chans[0]
            if len(chans) > 1:
                c = input("Welchen Kanal ändern? [%d]: " % ch).strip()
                ch = int(c) if c else ch
            ip = input("\nNeue IP (leer = nichts ändern, 'dhcp' = auf DHCP): ").strip()
            if not ip:
                return 0
            if ip.lower() == "dhcp":
                b.set_dhcp(ch)
            else:
                ipaddress.IPv4Address(ip)
                mask = input("Maske [255.255.255.0]: ").strip() or "255.255.255.0"
                gw = input("Gateway [0.0.0.0]: ").strip() or "0.0.0.0"
                b.set_static(ch, ip, mask, gw)
                time.sleep(2)
                print("\nNeu:")
                print_info(b.lan_info(ch))
                print("\nLaptop-IP z. B. %s setzen, dann https://%s öffnen (CPLD-Update)." % (_suggest_own(ip, mask), ip))
    except (IpmiError, OSError, ValueError) as e:
        print("\n✖ %s" % e)
        return 1
    return 0


def main(argv=None):
    if argv is None:
        argv = sys.argv[1:]
    if not argv:
        rc = wizard()
        if platform.system() == "Windows":
            input("\nEnter zum Schließen …")
        return rc

    p = argparse.ArgumentParser(description="BMC/Switchboard-IP per IPMI finden und ändern (ohne DHCP).")
    sub = p.add_subparsers(dest="befehl", required=True)

    s = sub.add_parser("suchen", help="BMCs am Kabel finden (IPv6 link-local / Broadcast)")
    s.add_argument("--timeout", type=float, default=3.0)
    s.set_defaults(fn=cmd_suchen)

    def common(sp):
        sp.add_argument("adresse", help="z. B. fe80::…%%eth0, fe80::…%%12 (Windows) oder IPv4")
        sp.add_argument("-u", "--benutzer", default="admin")
        sp.add_argument("-p", "--passwort", default=None, help="ohne Angabe wird gefragt")
        sp.add_argument("-k", "--kanal", type=int, default=0, help="LAN-Kanal (Standard: automatisch)")

    z = sub.add_parser("zeigen", help="aktuelle IPv4-Konfiguration lesen")
    common(z)
    z.set_defaults(fn=cmd_zeigen)

    t = sub.add_parser("setzen", help="feste IPv4 setzen (oder --dhcp)")
    common(t)
    t.add_argument("--ip")
    t.add_argument("--maske", default="255.255.255.0")
    t.add_argument("--gw", default="0.0.0.0")
    t.add_argument("--dhcp", action="store_true", help="statt fester IP wieder auf DHCP stellen")
    t.set_defaults(fn=cmd_setzen)

    a = p.parse_args(argv)
    if getattr(a, "befehl", "") == "setzen" and not a.dhcp:
        if not a.ip:
            p.error("--ip fehlt (oder --dhcp)")
        ipaddress.IPv4Address(a.ip)
        ipaddress.IPv4Address(a.maske)
        ipaddress.IPv4Address(a.gw)
    try:
        return a.fn(a)
    except (IpmiError, OSError) as e:
        print("✖ %s" % e)
        return 1


if __name__ == "__main__":
    sys.exit(main())
