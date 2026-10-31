# Privacy Toolkit (Android) - v3.0 (merged)

v3.0 merges the two parallel branches (v1.2 and v2) into one app. Nothing was removed from either; where both had the same
tool the stronger implementation was kept and the other branch's extras were folded into it. 11 tabs (scrollable bar):
Run, Files, Network, Security, Tools, Assist, BioTrack, Price, Radio, Lens, Geo. No backend: everything runs on the phone or
calls public data sources directly.

## What v3.0 added on top of v1.2
**Network tab: new sub-tabs**
* **Wi-Fi Pro**: connection details (BSSID, gateway, DNS, DHCP, lease, link speed, standard), live signal graph, channel congestion
  and best-channel advice, interference analysis, evil-twin / open / WEP / WPS checks, every AP with distance estimate, saved-password reveal (QR or root).
* **Intel**: real data about any domain, URL or IP: owner, location, DNS, WHOIS/RDAP, SPF/DMARC/DKIM, TLS chain, security-header grade,
  subdomains from certificate logs, web-archive age, redirect chain, policy files. Stale answers from an earlier lookup can no longer overwrite a new one.
* **LAN**: new *Full* depth (all 65535 ports, probed in chunks and stoppable), custom port lists (`22,80,8000-8100`), **Stop** button,
  Copy IP / Copy SSH, separate Open web UI and Open HTTPS buttons, extra service names for ports without a risk row.

**Tools tab**
* **Sensor+**: all sensors live, metal detector, vibration meter, light meter, barometric altimeter + GPS speedometer, multi-touch tester,
  display tester, tone generator/sweep, haptics test, sound meter.
* **Dev**: new **CIDR** calculator and **Hash all** (MD5, SHA-1, SHA-256, SHA-384, SHA-512 together).

**Site Guard bubble**: menu grew from 16 to 27 actions. Added copy/share/open the site, scam reports, WHOIS, web archive, VirusTotal, full on-device intel
summary, number/email check, Scam Check, Wi-Fi Pro and Lens shortcuts.

**Radio**: cell-tower viewer (serving + neighbours, technology, signal quality, operator) and Geiger-counter beeps on the Wi-Fi signal meter.

**Files > Smart**: PDF reading now has three tiers: built-in parser, then PDFBox (embedded fonts, metadata), then on-device OCR.

**Performance and robustness**
* HTTP helpers cache successful answers (20 s / 90 s), request gzip, cap response size and always close connections.
* LAN port probing is chunked, so a 65535-port scan never creates tens of thousands of coroutines at once; scans can be cancelled cleanly.
* Manifest now declares background location (needed for Geo alerts on Android 10+) and vibrate (haptics test).
* ML Kit libraries moved to the newer versions used by v2.

## Honest limits
- Android does not let normal apps read saved Wi-Fi passwords. The app shows the connected network's details, supports QR/manual/root entries and keeps them in an encrypted, biometric-gated vault.
- Species-level plant ID needs an online service (optional Pl@ntNet key in settings).
- SDR spectrum needs a reachable rtl_tcp server.
- The microphone is not factory calibrated: use the calibration slider for dB readings close to a reference meter.
- Heart rate and sleep are wellness estimates, not medical devices.
- This merge was syntax-checked and its pure logic unit-tested off-device (150+ checks, see `tools/logic-tests`). The full Android build runs on GitHub Actions; check the first run's summary for any environment-specific compile messages.

## Build
Push to GitHub. Actions tab -> latest run -> Artifacts -> PrivacyToolkit-debug.
Or locally: `gradle assembleDebug` (JDK 17, Android SDK 34).

## Original tabs

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
Location (Wi-Fi scan list), Usage access (Data tab), All files access (Cleaner), VPN (VPN/Shield), Camera (QR, Lens, Price scan, heart rate), Microphone (noise meter), Activity recognition + Body sensors (BioTrack), Background location (Geo alerts), Display over other apps (Site Guard bubble), Accessibility (optional, browser address bar only), Notifications, Call log + Calendar (Monitor, optional). Everything is processed on-device.
