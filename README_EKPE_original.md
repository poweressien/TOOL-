# EKPE

Android toolkit app. Tabs: Analyzer, Intel, Split, Fonts, Cloud, Clock, Tools, **Radar**, **Guard**, **Labs**.

## Get the APK (GitHub)
1. Create a GitHub repo and push the contents of this folder (the `.github` folder must be at the repo root).
2. Open the repo > Actions > "Build EKPE APK" (runs on every push, or press Run workflow).
3. When it turns green, open the run and download the artifact **EKPE-apk**. It contains `EKPE.apk`.

## Build locally
Android Studio (JDK 17): open this folder, sync, Run. Or `gradle assembleDebug` (Gradle 8.11.1).
APK path: `app/build/outputs/apk/debug/EKPE-debug.apk`

## Radar tab
Cell towers (tech, carrier, cell ID, signal %, distance), Wi-Fi and Bluetooth devices (brand, signal %, distance, closeness),
live signal %, download/upload speed, electromagnetic field level, and pop-up alerts that keep working when the app is closed.
All local, no backend. Allow location, nearby devices, phone and notification permissions when asked, and turn on Bluetooth, Wi-Fi and Location.
Phones cannot detect ionizing radiation; the field meter reads electromagnetic fields only.

Package: com.inwekobong.ekpe

## Guard tab (7 tools)
- **LAN Guard**: finds devices on your Wi-Fi by pinging (Android hides the router table), shows name/brand/IP, flags new ones, optional background alerts. Brand matching is partial and phones with random MACs show as "Private MAC".
- **Bug Sweep**: 30 s sweep combining magnetic field, front-camera bright-spot (IR LED) detection and Radar Wi-Fi/Bluetooth. Gives a suspicion score, not a guarantee.
- **Device Finder**: hot/cold + beeps from Bluetooth signal strength.
- **Network Toolkit**: speed test (needs internet), ping, traceroute, DNS, port check (own network only).
- **App Privacy Audit**: dangerous permissions, background-capable apps, known ad/tracker SDKs.
- **Private Vault**: fingerprint/screen-lock gate, AES-256-GCM with a hardware-backed key. Uninstalling erases it: use **Backup** (password-encrypted file) and **Restore**.
- **Sound Lab**: dB meter (approximate), 32-band live spectrum, noise alarm that saves WAV clips.

## Labs tab (8 tools)
- **Coverage Mapper**: logs signal per SIM carrier while you walk (GPS) or tap a room map; heatmap, best spot, and which carrier wins.
- **Tower Locator**: download an OpenCelliD country file once (free key); Radar then shows true tower distance and bearing.
- **Offline AI**: MediaPipe LLM Inference. Import or download a Gemma-style .task/.bin model (kept outside the APK). Explain Radar, draft, translate, voice input. Adds roughly 50 MB to the APK.
- **Automation Rules**: triggers (new BLE within N m, signal low, field jump, new Wi-Fi, battery, offline) with actions (alert, torch, vibrate, alarm, SMS, WhatsApp tap-to-send).
- **Script Lab**: Python console with `radar()` and `notify()`.
- **Phone-to-Phone Share**: Wi-Fi Direct chat and files, no internet.
- **Media Forensics**: EXIF metadata plus Error Level Analysis for JPEGs. Hints, not proof.
- **Money Tracker**: SMS alerts to a local ledger, category/day charts, CSV monthly report, editable rules.
