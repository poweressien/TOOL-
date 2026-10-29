# Privacy Toolkit (Android)

Bottom tabs and what is inside:

**Run** - localhost HTML runner (paste/import a single HTML file, real http://localhost origin, console, desktop mode).

**Files** - Compress (smart/ZIP/GZIP/XZ, photos + video) · PDF (images<->PDF, merge, split) · Encrypt (AES-256-GCM + shredder) · Strip EXIF (remove GPS/camera metadata) · OCR (offline text from images) · Cleaner (biggest, duplicate and old files).

**Network** - Signal (live bar + %) · Speed (live meter + speed test w/ upload, jitter, history) · Wi-Fi (analyzer, channel graph, best channel) · Toolbox (ping, traceroute, DNS, whois, port check) · Data (per-app Wi-Fi/mobile usage) · LAN (device + open-port scanner).

**Security** - VPN (WireGuard) · Shield (DNS tracker blocker) · Audit (app permission/tracker scan) · Passwords (generator, strength, breach check) · Vault (encrypted offline password vault with fingerprint unlock).

**Tools** - QR (scan, generate, Wi-Fi QR) · Sensors (compass, level, barometer, light, magnetic, motion) · API (HTTP tester) · Dev (JSON, Base64, hashes, URL, color, UUID, timestamps) · Fonts (47 fancy Unicode styles, copy/share) · Info · Location (mock GPS for testing) · Preview.

**Assist** -
- Site Guard: draggable floating circle gauge (1-100%, red/yellow/green) that scores the site you're on. Auto-detects browser URLs through an address-bar-only accessibility service, or scan via Share / paste. Checks: HTTPS + certificate, redirects, domain age (RDAP), look-alike/brand spoofing, scam words, shorteners, risky downloads.
- Smart Scan: phone (format, country, Nigerian carrier prefix, scam hints) and email (syntax, disposable/typo, MX/SPF/DMARC, Gravatar, GitHub, domain age) plus one-tap web searches.
- Scam Check: paste or share an SMS/WhatsApp/email to detect OTP/BVN grabs, advance-fee, fake prizes, risky links.
- Monitor: foreground service with heads-up pop-ups for background apps, data drainers, weak/slow network, missed calls, calendar events, low battery/storage.
- Bluetooth: live BLE + classic scan with signal/distance, paired and connected devices, maker, services, item-tracker (AirTag/Tile/SmartTag) alerts, and a "read device info" button (model, firmware, battery).
- Smart Scan "Deep scan online": web footprint, scam reports in top results, Gravatar profile, domain registrar/IP/host.
- Home: apps auto-sorted into folders (A-Z, most used, unused) with an optional launcher mode.

## Build
Push to GitHub. Actions tab -> latest run -> Artifacts -> PrivacyToolkit-debug.
Or locally: `gradle assembleDebug` (JDK 17, Android SDK 34).

## Permissions you will be asked for
Location (Wi-Fi scan list), Usage access (Data tab), All files access (Cleaner), VPN (VPN/Shield), Camera via system scanner (QR), Display over other apps (Site Guard bubble), Accessibility (optional, browser address bar only), Notifications, Call log + Calendar (Monitor, optional). Everything is processed on-device.
