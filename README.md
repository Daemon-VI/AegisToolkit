<div align="center">

# Aegis

**Packet capture, TLS inspection, and app auditing on Android — without root.**

31 security tools in one native app. No root, no ads, no telemetry, no accounts.
Everything runs on the phone; nothing is uploaded.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen)
![Rootless](https://img.shields.io/badge/root-not%20required-success)

</div>


---

## Why this exists

Most Android network tooling asks you to pick one of three bad options: root your phone, trust a
closed-source app that ships an ad SDK, or SSH into a laptop. Aegis is the fourth option — an
open-source, single-APK toolkit that does the interesting work locally on a stock, unrooted device.

Three things here are genuinely hard to find elsewhere on an unrooted phone:

- **Live packet capture over `VpnService`** — Aegis stands up a TUN interface and parses real
  IPv4/TCP/UDP headers off your own device's traffic, showing per-flow protocol, endpoints, and
  byte counts. No root, no `tcpdump` binary. *(Capture-only today: packets are observed and then
  dropped, so there is no internet while a capture runs. The forwarding engine is the next
  milestone — see [ROADMAP.md](ROADMAP.md).)*
- **Full TLS certificate-chain inspection** — handshakes with any host and shows the complete chain
  (subject, issuer, SANs, key size, signature algorithm, expiry), the negotiated protocol and
  cipher, and whether the system trust store accepts it — including for expired and untrusted
  certificates, which is exactly when you need to look.
- **Offline APK / tracker analysis** — point it at any installed app and get its dangerous
  permissions, exported components, signer SHA-256, and third-party trackers matched against a
  bundled 35-signature database. No upload, no third-party scanning service.

## Install

Grab the APK from [**Releases**](../../releases/latest). Android 8.0 (API 26) or newer, arm64.

Or build it yourself:

```bash
git clone https://github.com/Daemon-VI/AegisToolkit.git
cd AegisToolkit
./gradlew :app:assembleRelease
```

## Tools

<details open>
<summary><b>Traffic</b></summary>

- **Traffic Monitor** — no-root live IP-packet capture over `VpnService`; per-flow protocol,
  endpoints, and size.
</details>

<details>
<summary><b>Network &amp; recon</b> (7)</summary>

- **Network Info** — IPv4/prefix, IPv6, gateway, DNS servers, active interface.
- **Host Discovery** — ping-sweeps your LAN for live hosts.
- **Port Scanner** — TCP connect scan; common ports by default, or `1-1024` / `22,80,443`.
- **Ping** — real ICMP round-trips.
- **DNS Lookup** — a hand-rolled UDP resolver: A/AAAA/CNAME/MX/NS/TXT/SOA/PTR against any server.
- **WHOIS** — port-43 lookups with referral following.
- **Subnet Calculator** — CIDR to network/broadcast/mask/host range.
</details>

<details>
<summary><b>Web</b> (6)</summary>

- **TLS / Cert Inspector** — full chain, SANs, key sizes, negotiated cipher, trust verdict.
- **Security Headers** — grades HSTS, CSP, X-Frame-Options, X-Content-Type-Options,
  Referrer-Policy, Permissions-Policy; flags `Server` / `X-Powered-By` leaks; gives a letter grade.
- **HTTP Headers** — raw response headers, redirects not followed.
- **Tech Fingerprint** — infers server and framework (WordPress, Next.js, Cloudflare, …).
- **Request Builder** — any method, custom headers and body, full response.
- **Directory Brute-Force** — wordlist path discovery against a host you are authorized to test.
</details>

<details>
<summary><b>Device &amp; privacy</b> (3)</summary>

- **Security Checkup** — read-only posture review: screen lock, storage encryption,
  security-patch age, Android version, root indicators, developer options / USB debugging, VPN
  state, **user-installed CA certificates** (a MITM red flag), and active device-admin apps.
- **App Analyzer** — per-app dangerous permissions, exported components, signer SHA-256 (with a
  debug-key warning), and third-party trackers from an offline signature DB.
- **Permission Auditor** — which installed apps hold camera, microphone, location, SMS, contacts,
  and the high-power special-access grants (accessibility, notification access, device admin).
</details>

<details>
<summary><b>Passwords &amp; hashing</b> (7)</summary>

- **Breach Check** — Have I Been Pwned Pwned-Passwords over **k-anonymity**: only the first five
  characters of the SHA-1 hash ever leave the device.
- **Hash Generator** — MD5, SHA-1/256/384/512, CRC32, live as you type.
- **Hash Identifier** — infers a hash's algorithm.
- **Dictionary Cracker** — tests a hash against a wordlist, for auditing the strength of
  credentials you own.
- **File Hash** — MD5/SHA-1/SHA-256 of any file; paste a publisher's hash to verify a download.
- **Password Tools** — entropy meter and a secure generator.
- **Encoder / Decoder** — Base64, Hex, URL, ROT13.
</details>

<details>
<summary><b>Vault &amp; 2FA</b> (2)</summary>

- **Authenticator** — RFC 6238 TOTP; seeds encrypted under a hardware-backed Keystore key.
- **Secure Vault** — AES-256-GCM notes, key held in the Android Keystore, never exported.
</details>

<details>
<summary><b>Wi-Fi</b> (3)</summary>

- **Connection Details** — SSID, BSSID, RSSI, channel, band, link speed.
- **Nearby Networks** — scans surrounding APs with a signal meter.
- **Hotspot Monitor** — watches devices connected to your phone's hotspot.
</details>

<details>
<summary><b>Intruder Watch</b> (2)</summary>

- **Arm Guardian** — after N failed unlock attempts, silently captures a front-camera photo.
  Needs camera permission and the device-admin "watch login" grant — Aegis requests *only* that,
  so it cannot lock or wipe the device.
- **Capture Log** — timestamped gallery of captures. Photos never leave the phone unless you share
  them.
</details>

<details>
<summary><b>Termux bridge</b> (3) — the heavy tools</summary>

- **Nmap**, **Run Command**, **Toolbox** — shells out to [Termux](https://termux.dev) so you can
  drive `nmap`, `sqlmap`, `hydra` and friends with output piped back into Aegis.
  One-time setup: install Termux from F-Droid (*not* the Play Store), grant `RUN_COMMAND`, then run

  ```bash
  mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings
  ```
</details>

## Privacy

Aegis has no analytics, no crash reporting, no ad SDK, and no account. The only outbound traffic it
ever makes is the request *you* asked for — a scan, a lookup, a handshake — plus one anonymized
5-character hash prefix if you use Breach Check. Captures, vault entries, and TOTP seeds never
leave the device.

Permissions are requested lazily, only when you open a tool that needs one.

## Authorized use only

Aegis is built for testing systems you own or have written permission to test, and for
understanding your own device's security posture. Port scanning, directory brute-forcing, and
credential auditing against third-party infrastructure without authorization is illegal in most
jurisdictions. You are responsible for how you use it.

## Contributing

Issues and PRs are welcome — especially around the VPN forwarding engine, the tracker signature
database, and device compatibility reports (OEM skins vary wildly in what they permit). See
[ARCHITECTURE.md](ARCHITECTURE.md) for how the app is laid out and [ROADMAP.md](ROADMAP.md) for
what is planned.

## License

[GPL-3.0](LICENSE). If you ship a derivative, ship the source too.
