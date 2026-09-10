package com.rishi.aegis

enum class Category(val label: String, val blurb: String) {
    NETWORK("Network & Recon", "Discover hosts, scan ports, resolve names"),
    TRAFFIC("Traffic", "Watch every packet leave your phone — no root"),
    WIFI("Wi-Fi", "Inspect your connection and nearby networks"),
    WEB("Web", "Probe HTTP servers and web apps"),
    CRYPTO("Passwords & Hashing", "Hash, crack, encode, generate"),
    DEVICE("Device & Privacy", "Audit your phone's security posture and apps"),
    SECURE("Vault & 2FA", "Hardware-encrypted secrets and TOTP codes"),
    CLI("CLI via Termux", "Run nmap, sqlmap, hydra & more through Termux"),
    GUARDIAN("Intruder Watch", "Catch whoever tries to unlock your phone"),
}

enum class Tool(val title: String, val subtitle: String, val category: Category) {
    NET_INFO("Network Info", "Your IP, gateway, DNS, subnet", Category.NETWORK),
    HOST_DISCOVERY("Host Discovery", "Find live devices on your LAN", Category.NETWORK),
    PORT_SCAN("Port Scanner", "TCP connect scan of a host", Category.NETWORK),
    PING("Ping", "Latency to a host (ICMP)", Category.NETWORK),
    DNS("DNS Lookup", "A / AAAA / MX / TXT / NS / CNAME / SOA", Category.NETWORK),
    WHOIS("WHOIS", "Domain / IP registration data", Category.NETWORK),
    SUBNET("Subnet Calculator", "CIDR ranges, masks, host counts", Category.NETWORK),
    TRAFFIC_MON("Traffic Monitor", "Live capture of every packet, no root", Category.TRAFFIC),
    WIFI_INFO("Connection Details", "SSID, BSSID, signal, channel", Category.WIFI),
    WIFI_SCAN("Nearby Networks", "Scan APs with a signal meter", Category.WIFI),
    HOTSPOT_MON("Hotspot Monitor", "Who's connected to your hotspot", Category.WIFI),
    HTTP_HEADERS("HTTP Headers", "Inspect response headers", Category.WEB),
    FINGERPRINT("Tech Fingerprint", "Identify server / framework", Category.WEB),
    DIR_BRUTE("Directory Brute-Force", "Find hidden paths", Category.WEB),
    REQ_BUILDER("Request Builder", "Craft any HTTP request", Category.WEB),
    HASH_GEN("Hash Generator", "MD5 / SHA / CRC of text", Category.CRYPTO),
    HASH_ID("Hash Identifier", "Guess a hash's type", Category.CRYPTO),
    HASH_CRACK("Dictionary Cracker", "Crack a hash with a wordlist", Category.CRYPTO),
    ENCODER("Encoder / Decoder", "Base64, Hex, URL, ROT13", Category.CRYPTO),
    PW_TOOLS("Password Tools", "Strength meter + generator", Category.CRYPTO),
    FILE_HASH("File Hash", "Checksum a file to verify it", Category.CRYPTO),
    BREACH_CHECK("Breach Check", "Has this password leaked? (k-anonymity)", Category.CRYPTO),
    TLS_INSPECT("TLS / Cert Inspector", "Certificate chain, expiry, ciphers", Category.WEB),
    HEADER_AUDIT("Security Headers", "Grade HSTS, CSP, X-Frame & more", Category.WEB),
    SEC_CHECKUP("Security Checkup", "Lock, encryption, root, patch level", Category.DEVICE),
    PERM_AUDIT("Permission Auditor", "Which apps hold camera / mic / location", Category.DEVICE),
    APK_ANALYZE("App Analyzer", "Perms, exports, signer & trackers of any app", Category.DEVICE),
    TOTP_AUTH("Authenticator", "TOTP 2FA codes, seeds in Keystore", Category.SECURE),
    SECURE_VAULT("Secure Vault", "Encrypted notes in hardware Keystore", Category.SECURE),
    CLI_SETUP("Termux Setup", "Install, permission & connection test", Category.CLI),
    CLI_RUN("Run Command", "Any shell command, output captured", Category.CLI),
    CLI_NMAP("Nmap", "Port/service scanning via nmap", Category.CLI),
    CLI_TOOLBOX("Toolbox", "Check & install pentest packages", Category.CLI),
    GUARD_SETUP("Arm Guardian", "Snap a selfie on failed unlock", Category.GUARDIAN),
    GUARD_LOG("Capture Log", "Photos of failed unlock attempts", Category.GUARDIAN),
}

sealed interface Screen {
    data object Home : Screen
    data class ToolScreen(val tool: Tool) : Screen
}
