# Aegis — Project State

_Last updated: 2026-08-16 (v1.4)_

## What this is
**Aegis** is a native Android security toolkit app, built for the owner's personal phone
(Realme RMX3312, Android 15 / API 35, arm64). Single-activity Jetpack Compose app, **no root
required**. Package `com.rishi.aegis`.

## Status: v1.4.1 — installed & verified on device (31 tools, 9 categories)
_v1.4.1 adds **Hotspot Monitor** (WIFI) — off-state verified on device; live device-discovery test
pending (needs owner to enable hotspot + connect a device; ColorOS blocks adb softap-start)._

### v1.4 — COMPLETE, installed & verified on device (30 tools, 9 categories)
v1.4 adds four features, all built + device-verified via ADB screenshots on 2026-08-16:
- **Traffic Monitor (flagship)** — no-root packet capture over `VpnService`. TUN established on
  ColorOS/Android 15 (fd valid), live-parsed 162 real IPv4/UDP/TCP flows (DNS→8.8.8.8:53, TCP→Meta
  :443), clean teardown restores internet. **Capture-only for now** (packets observed then dropped,
  no forwarding → no internet while running); forwarding engine is the next step. ✅
- **Breach Check (HIBP k-anonymity)** — "password" → seen 52,372,427 times; only prefix `5BAA6` left
  the phone. ✅
- **App Analyzer** — Instagram: 19 dangerous perms / 69 open exports / signer `5F:3E:50:F4…` / 0
  trackers (first-party, correct); a casual game: 9 trackers (Firebase, AdMob, Meta, Unity, ironSource,
  AppLovin, Vungle, InMobi, Pangle). ✅
- **Authenticator + Secure Vault** — Keystore AES-256-GCM ("Hardware Keystore active"); RFC 6238 TOTP
  022 333 matched an independent reference exactly. ✅

### v1.3 (still present) — verified earlier:
- **Device & Privacy (v1.3)** — VERIFIED. **Security Checkup** reads real posture (screen lock set,
  storage encryption active, patch 2026-06-01, Android 15, no root, correctly flags Developer
  options + USB debugging ON). **Permission Auditor** lists real apps by permission (Camera held by
  27 apps, Mic by 23) and special access (accessibility/notification/device-admin), with tap-to-expand. ✅
- **Web TLS/Headers (v1.3)** — VERIFIED. **TLS/Cert Inspector** on github.com returned trusted ✓ /
  TLSv1.3 / TLS_AES_128_GCM_SHA256 and the full 3-cert chain (SANs, issuers, EC key sizes, sig algs,
  validity). **Security Headers** grades HSTS/CSP/X-Frame/etc. ✅
- **Intruder Watch (v1.2)** — FULLY VERIFIED end-to-end. Enabled device admin via
  `dpm set-active-admin`, granted camera in-app, fired "Test capture now": a silent front-camera
  JPEG (1920×1080, mean luminance 140.7 — a real, well-exposed frame, not black) was written to
  `filesDir/intruders/` and rendered in the Capture Log gallery with correct EXIF rotation,
  timestamp, and "after N failed attempts". ✅
- **Network Info** — reads real active-network data (IPv4/prefix, IPv6, gateway, DNS, interface). ✅
- **DNS Lookup** — custom raw UDP DNS resolver; MX query for google.com returned
  `10 smtp.google.com` (name-compression parsing works). ✅
- **Hash Generator** — MD5/SHA-1/SHA-256/SHA-384/SHA-512/CRC32 all correct, live as you type. ✅
- **Termux bridge (v1.1)** — FULLY VERIFIED end-to-end. Installed Termux 0.118.3 (arm64) + nmap 7.991
  on the phone and ran `nmap -T4 -F -Pn 192.168.1.1` from Aegis's Nmap screen — real nmap output
  captured back in-app. Connection test returned `aegis-ok / aarch64 / exit 0`. ✅
- Dashboard navigation, scroll, back handling, dark theme. ✅

## Toolset (30 tools)
- **Network & Recon:** Network Info, Host Discovery (ping sweep), Port Scanner (TCP connect),
  Ping (system ICMP binary), DNS Lookup (raw UDP, A/AAAA/CNAME/MX/NS/TXT/SOA/PTR), WHOIS, Subnet Calculator.
- **Traffic (v1.4):** Traffic Monitor — no-root live IP-packet capture over VpnService (per-flow
  proto/endpoints/size). Capture-only prototype; forwarding engine pending.
- **Passwords & Hashing (v1.4 addition):** Breach Check — HIBP Pwned-Passwords via k-anonymity.
- **Device & Privacy (v1.4 addition):** App Analyzer — dangerous perms, exported components, signer
  SHA-256 (+ debug-key flag), and third-party trackers (35-signature offline DB).
- **Vault & 2FA (v1.4):** Authenticator (RFC 6238 TOTP, seeds encrypted in Keystore), Secure Vault
  (AES-256-GCM notes under a hardware Keystore key).
- **Wi-Fi:** Connection Details (SSID/BSSID/RSSI/channel/band), Nearby Networks scan (signal meter),
  Hotspot Monitor (v1.4.1 — detects own SoftAP, lists connected devices via subnet sweep + MAC/vendor,
  shows hotspot data in/out from /proc/net/dev; no per-client content without root).
- **Web:** HTTP Headers, Tech Fingerprint, Directory Brute-Force, Request Builder.
- **Passwords & Hashing:** Hash Generator, Hash Identifier, Dictionary Cracker, Encoder/Decoder
  (Base64/Hex/URL/ROT13), Password Tools (strength meter + generator), File Hash (SAF-pick a file →
  MD5/SHA-1/SHA-256 + compare to an expected value).
- **Web (v1.3 additions):** TLS/Cert Inspector (chain, expiry, key, cipher, SANs, trust+hostname
  validation), Security Headers (HSTS/CSP/X-Frame/X-Content-Type/Referrer/Permissions grading +
  info-leak flags + letter grade).
- **Device & Privacy (v1.3):** Security Checkup (lock, encryption, patch age, root indicators, dev
  options, ADB, VPN, user CA certs, device admins), Permission Auditor (apps by sensitive permission
  + accessibility/notification/device-admin special access).
- **CLI via Termux (v1.1):** Termux Setup (status/permission/test), Run Command, Nmap, Toolbox
  (check/install pentest packages).
- **Intruder Watch (v1.2):** Arm Guardian (device-admin activation, camera permission, overlay
  permission, capture-after-N threshold, arm switch, test capture), Capture Log (gallery of
  failed-unlock selfies with share/delete/clear).

## Termux setup (one-time, user-driven — can't be automated)
The bridge lets Aegis run real CLI tools (nmap, sqlmap, hydra…) via Termux. To enable:
1. Install Termux from **F-Droid or GitHub** (NOT Google Play — that build is dead). Open it once so
   it bootstraps its base system.
2. In Termux: `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings`
3. In Aegis → Termux Setup → grant the RUN_COMMAND permission, then "Run test".
The Setup screen walks through all of this and tests the connection.

## Intruder Watch setup (one-time, user-driven)
Open **Arm Guardian** and complete the numbered steps:
1. **Grant camera** (runtime permission — the photo).
2. **Activate device administrator** — opens the system screen; this is what makes Android deliver
   `onPasswordFailed`. Aegis requests only the `watch-login` policy (no lock/wipe).
3. **Allow display over other apps** (recommended) — grants the background-start exemption so the
   camera service can fire while the screen is locked. Without it, capture may only work while
   Aegis is open (device-dependent).
4. Choose **capture-after-N** (1 = every failed attempt).
5. **Arm** the switch. Use **Test capture now** to prove the pipeline, then check **Capture Log**.

To fully verify the real lockscreen path safely, the phone owner does ONE intentional wrong-PIN
entry — do NOT automate failed unlocks over ADB (risk of lockout / a "wipe after N fails" policy).
Device-admin activation itself was verified via `dpm set-active-admin`.

Captures live in app-private `filesDir/intruders/` as `cap_<epochMillis>_<attempts>.jpg`; nothing
leaves the device except through the explicit Share button (FileProvider authority
`${applicationId}.fileprovider`).

## Build & install
```bash
cd C:/Users/Rishi/AegisToolkit
./gradlew :app:assembleDebug
adb -s ac1b21b1 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s ac1b21b1 shell am start -n com.rishi.aegis/.MainActivity
```
Shareable APK staged at repo root: `Aegis-v1.4-debug.apk` (debug-signed, installs on any Android 8+).

## Environment (this machine)
- Android SDK: `C:\Android\Sdk` (platforms android-36/37, build-tools 36/37, NDK 28.2)
- JDK 21, Gradle 9.7.0 (`C:\Android\gradle-9.7.0`), Android Studio installed
- Device: `adb` id `ac1b21b1` = Realme RMX3312, Android 15.

## Version stack (all were pre-cached — chosen deliberately for offline reliability)
- AGP **9.3.1** (uses **built-in Kotlin 2.2.10** — see ARCHITECTURE for the gotcha)
- Kotlin / Compose Compiler plugin **2.2.10**
- Compose BOM **2026.06.01**, Material3 1.4.0
- activity-compose 1.13.0, lifecycle 2.11.0, core-ktx 1.19.0, coroutines 1.11.0
- compileSdk **37**, minSdk 26, targetSdk 35

## Notes / limitations (non-root Android sandbox)
- Wi-Fi scan results are throttled by Android 9+ and need location ON + permission.
- Host discovery uses `InetAddress.isReachable` — firewalled hosts stay invisible.
- No raw sockets → no true traceroute, no packet capture / MITM (would need root).
- Some networks block external UDP:53; DNS tool now shows a clear message and you can point it
  at the local resolver.
- **Intruder Watch:** `onPasswordFailed` fires on failed PIN/pattern/password and on biometric
  lock-outs that fall back to the credential — a stranger's *silent* fingerprint miss alone may not
  trigger it until Android forces the PIN. Background camera use is restricted on Android 14/15;
  the "Display over other apps" permission is the exemption we lean on, but some OEM builds may
  still only allow capture while the app is foreground. ColorOS blocks `adb pm grant` for CAMERA
  (grant it via the in-app button) just like it blocks RUN_COMMAND.
