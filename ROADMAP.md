# Aegis — Roadmap

## v1.4.1 — Hotspot Monitor (2026-08-16)
New WIFI tool `HOTSPOT_MON` — watch the phone's own Wi-Fi hotspot, no root.
- `core/Hotspot.kt` (+ inline OUI table), `tools/HotspotScreens.kt`.
- Detects the SoftAP by enumerating NetworkInterface for the site-local IPv4 that isn't the cellular
  upstream (SoftAP iface = ap0/wlan1/swlan0, gateway ~192.168.x.1). Reads total data in/out from
  `/proc/net/dev` (confirmed world-readable on this ColorOS build). Discovers connected devices by
  sweeping the AP subnet (reuses `NetEngine.pingSweep`), enriches with MAC+vendor from `/proc/net/arp`
  (best-effort — may be empty on Android 15) and flags randomized/private MACs (locally-administered
  bit). Honest limit stated in-UI: per-device *traffic content* needs root (tethered traffic is routed
  below the app sandbox; even our VpnService only sees this phone).
- OFF-STATE VERIFIED on device: detectAp()→null with hotspot off, shows the warning + Refresh + Open
  hotspot settings. **Live device-discovery test still pending** — needs the owner to enable the
  hotspot + connect a device (ColorOS blocks `cmd wifi start-softap` from adb, uid 2000).

## v1.4 — IN PROGRESS (started 2026-08-16)
Four features approved: **Traffic Monitor** (no-root packet capture), **HIBP breach check**,
**offline APK/tracker analyzer**, **Keystore vault + TOTP**. Approach: prototype the flagship first.

### Flagship: Traffic Monitor (VpnService packet capture) — PROTOTYPE VERIFIED ✅
De-risk succeeded on the real phone (Realme RMX3312, Android 15 / ColorOS). Proven end-to-end:
- `VpnService.prepare()` consent dialog appears and works on ColorOS (shell `appops`/`pm grant`
  are blocked here as usual, so consent must be a real user tap — it is).
- `establish()` returns a valid TUN fd; the read loop parses real IPv4/IPv6 + TCP/UDP flows.
- Live capture verified: 162 packets, 81.6 KB, correct proto chips + src→dst:port + lengths in the
  UI (DNS to 8.8.8.8:53, TCP/UDP to Meta on :443). Stop tears the tunnel down cleanly and restores
  internet (`connectivity` shows NOT_VPN again).
- Files: `core/PacketParser.kt`, `core/TrafficCapture.kt`, `core/CaptureVpnService.kt`,
  `tools/TrafficScreens.kt`; manifest service + FGS specialUse; Tool `TRAFFIC_MON` / Category `TRAFFIC`.

**PROTOTYPE LIMITATION (by design):** capture-only. Packets are observed then dropped — no
forwarding — so the device has no internet while the monitor runs. Next step to make it a real
always-on monitor:
- Userspace forwarding: a TCP proxy (per-flow socket, `protect()` it, splice via the TUN) + a UDP
  relay (esp. DNS on :53). This is the hard part; PCAPdroid/tun2socks are the reference designs.
- Then layer on: per-app attribution (`ConnectivityManager.getConnectionOwnerUid`), domain logging,
  a block-list firewall, and `.pcap` export.

### Remaining v1.4 (cheap-and-robust wins, no VPN dependency)
- **HIBP breach check** — DONE & VERIFIED ✅ (2026-08-16). k-anonymity: SHA-1 the password, send
  only the 5-char prefix (`Add-Padding: true`), match the suffix locally. On-device test: "password"
  → "Found in breaches, seen 52,372,427 times", panel shows only prefix `5BAA6` left the phone.
  Files: `core/Breach.kt`, `tools/BreachScreens.kt`; Tool `BREACH_CHECK` under Category CRYPTO.
- **Offline APK/tracker analyzer** — DONE & VERIFIED ✅ (2026-08-16). Parses manifest: dangerous
  perms (via protectionLevel), exported components (unguarded flagged), signer SHA-256 (+ debug-key
  detection), and trackers via a bundled 35-signature DB matched against declared component classes.
  On-device: Instagram → 19 dangerous perms / 69 open exports / 0 trackers (first-party, correct);
  "2 Player Games" → 9 trackers (Firebase, AdMob, Meta, Unity, ironSource, AppLovin, Vungle, InMobi,
  Pangle). Files: `core/ApkAnalyzer.kt`, `core/Trackers.kt`, `tools/ApkScreens.kt`; Tool `APK_ANALYZE`
  under DEVICE. Note: list load can take ~2s on 200+ apps; brief "0 apps" until LaunchedEffect fills.
- **Keystore vault + TOTP** — DONE & VERIFIED ✅ (2026-08-16). AES-256-GCM under an AndroidKeystore
  key (hardware-backed; "Hardware Keystore active" badge confirmed on device). TOTP is RFC 6238
  (HMAC-SHA1, 6 digits, 30s). On-device: secret JBSWY3DPEHPK3PXP → code 022 333, which matches an
  independent reference TOTP for the same epoch exactly — and the seed round-tripped through Keystore
  encryption. Files: `core/Totp.kt`, `core/Vault.kt`, `tools/VaultScreens.kt`; Tools `TOTP_AUTH` +
  `SECURE_VAULT` under new Category SECURE ("Vault & 2FA").

## v1.4 status: all 4 approved features SHIPPED & device-verified (2026-08-16)
Remaining flagship work (deferred, was the plan's "then forwarding" phase): the Traffic Monitor is
still capture-only. To make it a real always-on monitor/firewall, build the userspace forwarding
engine (TCP proxy + UDP/DNS relay via `protect()`), then per-app attribution, domain log, block-list
firewall, and PCAP export. See the flagship section above.

## v1.3 — Device & Privacy + Web security — DONE
Shipped & verified on device: **Security Checkup** (posture), **Permission Auditor** (apps by
sensitive permission + special access), **TLS/Cert Inspector**, **Security Headers** grader, and
**File Hash**. Natural follow-ups:
- Security Checkup: tap a warning to jump to the relevant Settings screen; "show passwords" &
  screen-timeout checks; export the report.
- Permission Auditor: filter/search, sort by app, deep-link to an app's settings, show last-used.
- TLS: pin/compare a cert over time; OCSP/CRL revocation; export PEM; cipher-strength grading.
- Add from the old v1.2 list below: banner grabbing, mDNS/SSDP discovery, JWT decode, Base32.

## v1.2 — Intruder Watch — DONE
Shipped: device-admin `watch-login` → `onPasswordFailed` → camera foreground service → silent
Camera2 front-camera capture → Capture Log gallery (share/delete/clear via FileProvider). Verified
end-to-end on device. Possible follow-ups:
- Timestamp/geo/attempt overlay burned into the saved image; optional last-known-location tag.
- Email/upload a capture the moment it's taken (needs a chosen backend + INTERNET already held).
- Capture on other triggers: SIM change, airplane-mode toggle, repeated wrong app-lock.
- Auto-delete captures older than N days; a max-count cap on the folder.
- Full-screen viewer with pinch-zoom; mirror-correct the front-camera image.
- ~~Handle the biometric-only case~~ — ATTEMPTED (WAKE mode: persistent watcher + wake-capture +
  auto-discard + overlay-eligibility trick) and REMOVED. Android 15/ColorOS on this phone hard-blocks
  background camera-FGS starts (`SecurityException`, "must be in eligible state") even with an active
  overlay window, so it couldn't work reliably here. See ARCHITECTURE.md. Would need a different
  device or a persistent camera-typed FGS. Do not re-attempt on this phone.

## v1.1 — polish (quick wins)
- Save/share tool output to a file (share sheet) and a history log.
- Import a wordlist from a file for Directory Brute-Force and the Dictionary Cracker.
- Cancel button for long-running scans (cooperative cancel on the coroutine).
- Persist last-used inputs (DataStore).
- Copy individual result rows, not just the whole console.

## v1.2 — more non-root tools
- **mDNS / Bonjour discovery** (`_services._dns-sd._udp.local`) — great for finding LAN devices
  that don't answer pings (printers, Chromecast, IoT).
- **UPnP/SSDP discovery** (multicast 239.255.255.250:1900).
- **Banner grabbing** on open ports (read the first response line after connect).
- **TLS/certificate inspector** (issuer, SAN, expiry, chain) via HttpsURLConnection.
- **Traceroute** approximation using `ProcessBuilder` if a `traceroute`/`ping -t <ttl>` path exists
  on the device; otherwise TCP-TTL best effort.
- **HTTP security-header auditor** (HSTS, CSP, X-Frame-Options grading).
- **Base32 / JWT decode / entropy calculator** in the encoder.

## v2 — bridge to real CLI tools (the "all tools" ask) — DONE in v1.1
Shipped: `TermuxBridge` + CLI section (Termux Setup, Run Command, Nmap, Toolbox). Aegis sends
`RUN_COMMAND` to Termux and captures background output. Still to expand:
- More curated forms: sqlmap, hydra, nikto, whatweb, gobuster (build args from a form like Nmap does).
- Stream long output live (poll a result file) instead of one final bundle; handle the ~100KB
  transaction truncation by writing to `RESULT_DIRECTORY` + reading via SAF.
- A saved "recent commands" list and re-run.
- One-tap "install all" in Toolbox.

## Root-only (if the phone is ever rooted)
- Packet capture (tcpdump), ARP scan, real ICMP raw sockets, Wi-Fi monitor mode/deauth
  (hardware-dependent), MITM/ARP-spoof lab tools.

## Known limitations to keep documenting
- Android throttles Wi-Fi scans; results can be stale/empty without location ON.
- `isReachable` host discovery misses firewalled hosts; consider adding a TCP-ping sweep on common
  ports as a complement.
- Cleartext HTTP is enabled app-wide for the web tools — fine for a pentest tool, not for a
  store-published app.
