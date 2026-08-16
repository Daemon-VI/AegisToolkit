# Aegis — On-device Security Toolkit

A native Android app with a dashboard of network, Wi-Fi, web, and password/hashing tools.
No root required. Built for personal, **authorized** security testing and learning.

> ⚠️ Use these tools only on networks and systems you own or have explicit permission to test.

## Install
The app is already installed on your phone. To reinstall or put it on another device:
```bash
adb install -r Aegis-v1.3-debug.apk
```
Then open **Aegis** from your app drawer (shield icon).

## Tools

**Network & Recon**
- **Network Info** — your IP/subnet, gateway, DNS, interface.
- **Host Discovery** — sweeps your LAN for live devices.
- **Port Scanner** — TCP connect scan; blank port box = common ports, or `1-1024`, `22,80,443`.
- **Ping** — real ICMP round-trips.
- **DNS Lookup** — A/AAAA/CNAME/MX/NS/TXT/SOA/PTR against any resolver.
- **WHOIS** — port-43 lookup (start with `whois.iana.org`, follow its referral).
- **Subnet Calculator** — CIDR → network/broadcast/mask/host range.

**Wi-Fi** (grant location once when asked — Android requires it)
- **Connection Details** — SSID, BSSID, signal, channel, band, link speed.
- **Nearby Networks** — scan surrounding APs with a signal meter (needs location ON).

**Web**
- **HTTP Headers** — raw response headers, no redirect follow.
- **Tech Fingerprint** — guesses server/framework (WordPress, Next.js, Cloudflare, …).
- **Directory Brute-Force** — tries a wordlist of paths, reports non-404s.
- **Request Builder** — any method, custom headers/body, view the full response.
- **TLS / Cert Inspector** — handshakes with an HTTPS host and shows the full certificate chain
  (subject, issuer, expiry, key size, SANs), the negotiated protocol/cipher, and whether the system
  trusts it — even for untrusted or expired certs.
- **Security Headers** — grades a site's HSTS, CSP, X-Frame-Options, X-Content-Type-Options,
  Referrer-Policy and Permissions-Policy, flags Server/X-Powered-By leaks, gives a letter grade.

**Passwords & Hashing**
- **Hash Generator** — MD5/SHA/CRC of text, live.
- **Hash Identifier** — guesses a hash's type.
- **Dictionary Cracker** — crack a hash against a wordlist.
- **Encoder / Decoder** — Base64, Hex, URL, ROT13.
- **Password Tools** — entropy/strength meter + secure generator.
- **File Hash** — pick any file and get its MD5/SHA-1/SHA-256; paste a publisher's hash to confirm a
  download wasn't tampered with.

**Device & Privacy**
- **Security Checkup** — a read-only look at your phone's posture: screen lock, storage encryption,
  security-patch age, Android version, root indicators, developer options / USB debugging, VPN,
  user-installed CA certificates (a MITM risk), and active device-admin apps.
- **Permission Auditor** — which installed apps currently hold camera, microphone, location, SMS,
  contacts and other sensitive permissions — plus the high-power "special access" grants
  (accessibility, notification access, device administrator). Tap a row to list the apps.

**CLI via Termux** (unlocks the heavy tools — nmap, sqlmap, hydra…)
- **Termux Setup** — one-time: install Termux (F-Droid/GitHub, *not* Play Store), grant the
  RUN_COMMAND permission, and in Termux run:
  `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings`
  Then hit "Run test" to confirm the bridge works.
- **Run Command** — any shell command; background captures output in Aegis, foreground opens Termux.
- **Nmap** — target + scan preset → runs nmap in Termux, output back in Aegis.
- **Toolbox** — check which tools are installed, or install them (`pkg install`).

**Intruder Watch** (camera trap for failed unlocks — use only on a phone you own)
- **Arm Guardian** — when someone fails to unlock your phone, Aegis silently takes a front-camera
  photo. One-time setup: grant **camera**, activate the **device administrator** (this is how
  Android reports failed unlocks — Aegis requests only "watch login", it can't lock or wipe your
  phone), optionally allow **display over other apps** (lets capture run while the screen is
  locked), pick how many failed attempts trigger it, then **arm**. "Test capture now" proves it.
- **Capture Log** — a gallery of the selfies, each stamped with the time and how many failed
  attempts. Share or delete individually, or clear all. Photos never leave the phone unless you
  hit Share.

  > Note: a failed *fingerprint/face* alone may not trigger a capture until Android falls back to
  > the PIN; wrong PIN/pattern/password always does. Background camera use is restricted on newer
  > Android — the "display over other apps" permission is what lets capture work while locked.

## Rebuild from source
```bash
./gradlew :app:assembleDebug
```
See `ARCHITECTURE.md` for how it's structured and `ROADMAP.md` for what's next (including bridging
to Termux to run nmap/sqlmap/hydra).
