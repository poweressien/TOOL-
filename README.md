# EKPE (Privacy Toolkit + EKPE suite) - v5.0, one app

This is **PrivacyToolkit v3.0 and EKPE v1.2 merged into a single Android app**. Nothing was removed from either one.
The app name is **EKPE** and the launcher icon is your photo (see `branding/`).

| | |
|---|---|
| App name | EKPE |
| applicationId | `com.toolkit.privacy` |
| minSdk / targetSdk | 26 / 34 |
| Output file | `EKPE.apk` (debug build, signed with the fixed `app/ekpe.jks` key so each build installs over the last) |

## How the app looks and works (v5 launcher)
* **Home screen = an app launcher.** Every tool (83 of them, from both apps) is its own tile with a unique icon and a name tag, grouped in sections
  (Files, Network, Security, Assist, Tools, Build & See, BioTrack, Price Radar, Radio, GeoSense, EKPE, Guard, Labs). A search box at the top filters them.
  No emojis are used for navigation or icons any more.
* **Tap a tile** to open that tool. The Privacy Toolkit tools open inside the same screen (back arrow returns to the home). The EKPE-suite tools open the
  EKPE screen straight on that tool; its last tab **"Toolkit"** (or the back button) returns to the home.
* **Hamburger menu (top-left):** Home, Settings, Theme & colors, About, Developer.
* **Master switch (top-right and in the menu):** switch OFF and everything stops: all foreground/background services (Site Guard, Monitor, Geo, Sleep,
  Env alerts, Mock location, DNS Shield, VPN, Radar, Automation, LAN Guard, Media watcher, floating windows), plus the boot receiver, daily alarms and the
  accessibility link watcher. Tiles are dimmed and tools show a "Paused" screen. Switch ON and what was running is resumed.
  Floating windows are not reopened, and Sleep tracking only resumes if it was paused for under 2 hours.
* **Themes:** Powder Black (default), Butter White, System (follows your phone, plus wallpaper accent on Android 12+) and Custom (pick dark/light base and
  accent, background, card and text colours by hex). The theme applies to the Toolkit screens, the EKPE screens, the system bars and the Radar page.
  EKPE's built-in Intel web page (Flask) keeps its own dark styling.

## How the two apps are combined
* Same APK, same icon, same process. Both code bases are kept in their own packages (`com.toolkit.privacy` and `com.inwekobong.ekpe`).
  The EKPE Python engine (Intel, Script Lab) is bundled with Chaquopy.
* Where both apps have a similar tool (Vault, Fonts, LAN, Radar/Radio, Audit, Sound/Noise, Network toolkit...) **both versions are kept**.
  Their data does not clash (different Keystore aliases, file names, prefs and notification channels).

## What changed technically
* `app/src/main/java/com/toolkit/privacy/*` - Privacy Toolkit code, unchanged except: new `AppShell.kt` (home, drawer, settings, theme, about, developer),
  `ToolRegistry.kt` (the 83 tiles), `ThemeStore.kt`, `MasterSwitch.kt`, `EkpeApp.kt` (Application class); the old tab bar is gone from `MainActivity.kt`;
  small gates so receivers/services obey the master switch; and visible text that said "Privacy Toolkit" now says "EKPE".
* `app/src/main/java/com/inwekobong/ekpe/*` - EKPE code, unchanged except `MainActivity.kt` (deep-link to a tool, "Toolkit" tab, theme and master-switch checks),
  `UiKit.kt` (colours now come from the shared theme), `GuardHome.kt`, `RadarEngine.kt` (`stop()`), `RadarSection.kt` (theme colours).
* `app/src/main/python`, `app/src/main/assets/radar` - EKPE Python and radar assets, unchanged.
* `AndroidManifest.xml` - union of both manifests (all permissions, all services, the `<queries>` block). EKPE's activity is no longer a launcher
  entry; the main screen is the only launcher entry. EKPE's accessibility config was renamed to `ekpe_accessibility_config.xml` to avoid a name clash.
* `app/build.gradle.kts` - both dependency lists merged (plus `material-icons-extended` for the tile icons), Chaquopy plugin added, fixed signing config, APK named `EKPE`.
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
