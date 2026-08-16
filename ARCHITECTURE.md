# Aegis — Architecture

## Layout
```
app/src/main/java/com/rishi/aegis/
  MainActivity.kt      # Activity, state-based nav (back stack), dashboard grid, ToolRouter
  Model.kt             # Category + Tool enums, Screen sealed interface
  core/
    Net.kt             # NetEngine: local info, ping sweep, port scan, ping, whois, http, dirBrute, fingerprint
    Dns.kt             # DnsEngine: hand-built UDP DNS query + response parser (with name compression)
    Crypto.kt          # CryptoEngine: hashing, hash-id, dictionary crack, encoders, password strength/gen
    Termux.kt          # TermuxBridge: RUN_COMMAND intent to Termux, background result capture
    Guardian.kt        # Intruder Watch: settings (armed/threshold), device-admin helpers, capture store + trigger
    AegisDeviceAdminReceiver.kt  # DeviceAdminReceiver.onPasswordFailed -> Guardian.trigger
    IntruderService.kt # camera foreground service: one silent shot then stopSelf
    IntruderCamera.kt  # Camera2 headless still capture (no preview) -> JPEG file
    DeviceSec.kt       # v1.3: device posture checkup + app permission/special-access auditor
    WebSec.kt          # v1.3: TLS/cert inspection (capture chain + validate) + security-header grading
  ui/
    Theme.kt           # AegisTheme (dark green/cyan terminal palette)
    Common.kt          # ToolPage, AegisField, RunButton, Console, KeyVal, Chip, Progress, etc.
  tools/
    NetworkScreens.kt  # 7 network tool composables (object NetworkScreens)
    WifiScreens.kt     # 2 wifi composables + runtime-permission gate (object WifiScreens)
    WebScreens.kt      # 4 web composables (object WebScreens)
    CryptoScreens.kt   # 5 crypto composables (object CryptoScreens)
    TermuxScreens.kt   # Setup, RunCommand, Nmap, Toolbox (object TermuxScreens)
    GuardianScreens.kt # Arm Guardian (setup) + Capture Log gallery (object GuardianScreens)
    DeviceScreens.kt   # v1.3: Security Checkup + Permission Auditor (object DeviceScreens)
    # v1.3 also adds WebScreens.TlsInspector / .HeaderAudit and CryptoScreens.FileHash
```

## Key design decisions
- **State-based navigation, no Navigation-Compose.** A `SnapshotStateList<Screen>` back stack in
  `AppRoot()`; `BackHandler` pops it. Avoided the (uncached) navigation-compose dependency.
- **No icon library.** Uses text glyphs (e.g. "‹" back). Avoided the uncached
  material-icons-extended artifact. Everything installs/builds offline.
- **Engines are plain objects with `suspend` funcs on `Dispatchers.IO`.** Screens call them from
  `rememberCoroutineScope().launch {}`; progress via `(done,total)->Unit` callbacks that write
  Compose state (snapshot state is safe to write from background threads).
- **Concurrency** in scans via `kotlinx.coroutines.sync.Semaphore` (64 for ping sweep, 128 for
  port scan, 24 for dir brute).
- **DNS is implemented from scratch** (see `Dns.kt`) so it can return MX/TXT/NS/SOA/etc. that
  `InetAddress` can't. Builds the 12-byte header + question, parses answers including 0xC0
  compression pointers. Labels are trimmed to tolerate soft-keyboard auto-spaces.

## The AGP 9 "built-in Kotlin" gotcha (important for future edits)
AGP **9.x ships its own Kotlin** and registers the `kotlin` Gradle extension. Consequences:
- Do **NOT** apply `org.jetbrains.kotlin.android` — it collides ("extension 'kotlin' already
  registered") and, being 2.2.10, also fails an old `BaseExtension` cast against AGP 9.
- The opt-out flag `android.builtInKotlin=false` is deprecated and doesn't rescue the classic path.
- **Working setup:** apply only `com.android.application` + `org.jetbrains.kotlin.plugin.compose`
  (version **2.2.10**, matching AGP's bundled Kotlin), and set `buildFeatures { compose = true }`.
  The compose compiler version MUST equal AGP's bundled Kotlin (found via the AGP `.module` file).
- compileSdk had to be **37** because AndroidX core-ktx 1.19.0 requires it.

## Termux bridge (core/Termux.kt)
Runs CLI tools by sending Termux's `com.termux.RUN_COMMAND` intent to
`com.termux/com.termux.app.RunCommandService` via `startForegroundService`. Command runs as
`bash -c "<line>"`.
- **Background mode** (capture output): a **mutable** `PendingIntent.getBroadcast` is passed as
  `RUN_COMMAND_PENDING_INTENT`; Termux fills a `"result"` bundle (keys `stdout`/`stderr`/`exitCode`/
  `err`/`errmsg`) and fires it. A dynamically-registered (RECEIVER_NOT_EXPORTED) receiver reads it.
  Wrapped in `suspendCancellableCoroutine` + `withTimeoutOrNull` so a missing/misconfigured Termux
  surfaces as a clear timeout message instead of hanging. FLAG_MUTABLE is required (API 31+) so
  Termux can write the result into the PendingIntent.
- **Foreground mode**: `RUN_COMMAND_BACKGROUND=false` opens a visible Termux session — used for
  interactive tools (sqlmap/hydra) and package installs.
- **Preconditions** the app checks: package `com.termux` present; `com.termux.permission.RUN_COMMAND`
  granted. It can't check `allow-external-apps` (Termux's private file) — the Setup screen instructs.
- Manifest needs `<uses-permission com.termux.permission.RUN_COMMAND>` and a `<queries><package
  com.termux></queries>` for API 30+ package visibility.

## Intruder Watch (device-admin camera trap — v1.2)
Catches whoever fails to unlock the phone, no root needed.

**Detection — Device Admin API.** `AegisDeviceAdminReceiver` extends `DeviceAdminReceiver`; the
manifest declares it with `BIND_DEVICE_ADMIN` + `<meta-data android.app.device_admin>` pointing at
`res/xml/device_admin.xml`, whose only policy is `<watch-login/>`. That policy is what makes Android
call `onPasswordFailed(context, intent, user)` on every failed keyguard credential attempt. We
override the **3-arg (UserHandle)** overload — the 2-arg one is deprecated and only reached via the
base class's default delegation. The callback reads `DevicePolicyManager.currentFailedPasswordAttempts`
and calls `Guardian.trigger()`. We request no lock/wipe/password policies.

**Capture — Camera2 headless + camera foreground service.** `Guardian.trigger` (gated by the armed
flag + capture-after-N threshold; `force=true` bypasses both for the test button) starts
`IntruderService` via `startForegroundService`. The service calls `startForeground(..., 
FOREGROUND_SERVICE_TYPE_CAMERA)` (API 29+) with an IMPORTANCE_MIN notification, then
`IntruderCamera.capture()` opens the front camera, drives a short repeating request against a
throwaway `SurfaceTexture(false)` to let 3A converge (~700 ms), fires one `TEMPLATE_STILL_CAPTURE`
into a JPEG `ImageReader`, writes the bytes to `filesDir/intruders/cap_<millis>_<attempts>.jpg`, and
tears everything down. An `AtomicBoolean` guards against overlapping shots on rapid repeated
failures; a hard 6 s timeout guarantees the service always stops. `JPEG_ORIENTATION` is set to the
sensor orientation (assumes upright/portrait — the usual lockscreen pose); the Capture Log honours
the resulting EXIF orientation when decoding.

**Storage / display / sharing.** App-private only. `Guardian.captures()` lists + parses the
filenames (epoch-millis + attempt count) newest-first. `GuardianScreens.Log` decodes each JPEG
downsampled on `Dispatchers.IO` (`BitmapFactory` + `inSampleSize`), rotates by EXIF, shows it with
timestamp + attempt count. Share uses a `FileProvider` (authority `${applicationId}.fileprovider`,
paths in `res/xml/file_paths.xml`) so nothing leaves the device except via an explicit Share.

**The background-camera gotcha (Android 14/15).** Apps can't use the camera from the background, and
starting a camera-type FGS from the background is restricted. We lean on the **SYSTEM_ALERT_WINDOW**
("display over other apps") exemption — requested on the Arm screen — to start the service while the
screen is locked. Some OEM builds may still only allow capture while Aegis is foreground; the test
path (app foreground) always works. The real lockscreen path must be confirmed by the owner doing one
intentional wrong-PIN entry — never automate failed unlocks over ADB (lockout / possible
wipe-after-N-fails policy). Device-admin activation was verified with `dpm set-active-admin`.

**Biometric-failure detection — attempted and dropped (do not re-attempt on this device).** Android
gives apps NO "failed fingerprint/face" callback — `onPasswordFailed` is credential-only. A WAKE mode
was built to cover it: a persistent `specialUse` foreground watcher (`WatchService`) listening for
SCREEN_ON/OFF/USER_PRESENT, capturing on every wake-while-locked into a quarantine dir, then
committing on SCREEN_OFF or discarding on USER_PRESENT (`WatchCoordinator`), plus a 1×1
TYPE_APPLICATION_OVERLAY window to grant camera-FGS eligibility. On-device result (Realme RMX3312,
Android 15/ColorOS): the state machine + auto-discard worked, but the camera start still threw
`SecurityException: Starting FGS with type camera ... app must be in the eligible state/exemptions`
whenever the app wasn't recently foreground — even with the overlay window live and SYSTEM_ALERT_WINDOW
granted. This OEM hard-blocks background camera regardless of the documented exemptions, so WAKE mode
could not work reliably here. It was removed (commit reverted to PIN-only). If ever revisited, it would
need a different device or a persistent camera-typed FGS (constant privacy-indicator cost).

## v1.3 — Device & Privacy + Web security tools
- **DeviceSec.checkup()** — read-only posture via public APIs: `KeyguardManager.isDeviceSecure`,
  `DevicePolicyManager.storageEncryptionStatus`, `Build.VERSION.SECURITY_PATCH` (age vs device clock),
  root indicators (su/magisk file probes + `Build.TAGS` test-keys + known root packages),
  `Settings.Global` DEVELOPMENT_SETTINGS_ENABLED / ADB_ENABLED, VPN transport check,
  user-installed CA certs (`KeyStore("AndroidCAStore")` aliases starting `user:` — a MITM signal),
  and `DevicePolicyManager.activeAdmins`. **Gotcha:** `packageManager.canRequestPackageInstalls()`
  THROWS on this ColorOS build — it (and every framework call here) must be wrapped in try/catch, and
  the screens wrap the whole engine call in `runCatching` so one failing check never crashes the app.
- **DeviceSec.permissionAudit()** — `getInstalledPackages(GET_PERMISSIONS)` then match each app's
  `requestedPermissions` + `requestedPermissionsFlags & REQUESTED_PERMISSION_GRANTED` against sensitive
  groups. Needs **QUERY_ALL_PACKAGES** (declared with `tools:ignore` — fine for a sideloaded personal
  app, would block a Play listing). Special access read from `Settings.Secure`
  (`enabled_accessibility_services`, `enabled_notification_listeners`) + active device admins.
- **WebSec.inspectTls()** — two phases: (1) a non-throwing capturing `X509TrustManager` grabs the
  chain + negotiated protocol/cipher even for untrusted/expired certs, with SNI set via
  `SNIHostName`; (2) a default-trust `SSLSocket` handshake + `HostnameVerifier.verify` reports whether
  the system trusts it. Cert fields (subject/issuer CN, validity, `sigAlgName`, key alg + bit length
  from RSA/EC public keys, SANs, CA flag) parsed from `X509Certificate`. `certWarnings()` flags
  expiry, weak sig (MD5/SHA1), small RSA keys — all against the device clock.
- **WebSec.auditHeaders()** — grades security headers off `NetEngine.httpRequest`, letter A–F.
- **File Hash** — SAF `OpenDocument` → stream through MD5/SHA-1/SHA-256 on Dispatchers.IO; name/size
  from `OpenableColumns`. Optional expected-hash compare.
- **Soft-keyboard whitespace:** the TLS host + header URL inputs `filterNot { it.isWhitespace() }`
  before use — this keyboard inserts a space after "." (same fix the DNS tool needed).

## Permissions (AndroidManifest)
INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_FINE/COARSE_LOCATION,
NEARBY_WIFI_DEVICES (neverForLocation). `usesCleartextTraffic=true` so the web tools can hit http://.
Location/nearby are requested at runtime by the Wi-Fi screens' `WifiGate`.
Intruder Watch adds: CAMERA, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, WAKE_LOCK,
SYSTEM_ALERT_WINDOW, `<uses-feature camera.front required=false>`; plus the device-admin `<receiver>`,
the `camera`-typed `<service>`, and the `FileProvider`. CAMERA is grantable only via the in-app
button on ColorOS (`adb pm grant` is blocked, same as RUN_COMMAND).
v1.3 adds: QUERY_ALL_PACKAGES (for the Permission Auditor).
