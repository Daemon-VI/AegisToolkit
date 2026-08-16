# Aegis — Roadmap

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
