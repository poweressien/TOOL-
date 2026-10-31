# EKPE (Privacy Toolkit + EKPE suite) - v4.0, one app

This is **PrivacyToolkit v3.0 and EKPE v1.2 merged into a single Android app**. Nothing was removed from either one.
The app name is **EKPE** and the launcher icon is your photo (see `branding/`).

| | |
|---|---|
| App name | EKPE |
| applicationId | `com.toolkit.privacy` |
| minSdk / targetSdk | 26 / 34 |
| Output file | `EKPE.apk` (debug build, signed with the fixed `app/ekpe.jks` key so each build installs over the last) |

## How the two apps are combined
* **Main screen** = the Privacy Toolkit (Compose) with its 11 tabs: Run, Files, Network, Security, Tools, Assist, BioTrack, Price, Radio, Lens, Geo.
* **New 12th tab "EKPE"** = the whole EKPE suite. Tap a card to open EKPE on that section:
  Analyzer, Intel, Split, Fonts, Cloud, Clock, Tools, Radar, Guard (7 tools), Labs (8 tools).
  The last tab of the EKPE bar, **"◀ Toolkit"**, brings you back to the main screen.
* Same APK, same icon, same process. The EKPE Python engine (Intel, Script Lab) is bundled with Chaquopy.
* Where both apps have a similar tool (Vault, Fonts, LAN, Radar/Radio, Audit, Sound/Noise, Network toolkit...) **both versions are kept**.
  Their data does not clash (different Keystore aliases, file names, prefs and notification channels).

## What changed technically
* `app/src/main/java/com/toolkit/privacy/*` - Privacy Toolkit code, unchanged except: new `EkpeHub.kt`, the extra tab in `MainActivity.kt`,
  and visible text that said "Privacy Toolkit" now says "EKPE".
* `app/src/main/java/com/inwekobong/ekpe/*` - EKPE code, unchanged except `MainActivity.kt` (added the "◀ Toolkit" tab).
* `app/src/main/python`, `app/src/main/assets/radar` - EKPE Python and radar assets, unchanged.
* `AndroidManifest.xml` - union of both manifests (all permissions, all services, the `<queries>` block). EKPE's activity is no longer a launcher
  entry; the main screen is the only launcher entry. EKPE's accessibility config was renamed to `ekpe_accessibility_config.xml` to avoid a name clash.
* `app/build.gradle.kts` - both dependency lists merged, Chaquopy plugin added, fixed signing config, APK named `EKPE`.
  Toolchain: AGP 8.5.2, Kotlin 2.0.20, Gradle 8.9, Chaquopy 17.2.0, Python 3.12.
* ABIs: arm64-v8a, armeabi-v7a, x86_64.

## Build (GitHub Actions)
1. Push the **contents of this folder** to a GitHub repo (the `.github` folder must be at the repo root).
2. Actions tab -> "Build EKPE APK" (runs on every push, or press *Run workflow*).
3. When green, open the run -> Artifacts -> **EKPE-apk** -> `EKPE.apk`. The **build-log** artifact and the run summary list any compile errors.

Locally: JDK 17, Android SDK 34, Python 3.12 on PATH, then `gradle assembleDebug`.

## Permissions to allow after install
Location, Nearby devices, Phone, Contacts, SMS, Camera, Microphone, Notifications, Photos/Videos, overlay ("Display over other apps"),
Usage access, Modify system settings. Turn on Bluetooth, Wi-Fi and Location for Radar. Details for each tool are in the two original READMEs:
`README_PrivacyToolkit_original.md` and `README_EKPE_original.md`.
