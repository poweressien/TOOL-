# EKPE (Privacy Toolkit + EKPE suite) - v4.0, one app

This is **PrivacyToolkit v3.0 and EKPE v1.2 merged into a single Android app**. Nothing was removed from either one.
The app name is **EKPE** and the launcher icon is your photo (see `branding/`).

| | |
|---|---|
| App name | EKPE |
| applicationId | `com.toolkit.privacy` |
| minSdk / targetSdk | 26 / 34 |
| Output file | `EKPE.apk` (debug build, signed with the fixed `app/ekpe.jks` key so each build installs over the last) |

## How the app is organised (v5)
* **Home screen** = a grid of app tiles. Every tool of both apps is its own tile (83 in total) with its own icon and a name tag,
  grouped in coloured sections. A search box filters them. Tiles for EKPE tools open the EKPE screen straight on that tool.
* **Hamburger menu**: Home, Settings, Appearance, About, Developer, and the master switch.
* **Master switch** (top bar and menu): OFF stops every background job and blocks all tools, ON restarts what was running.
* **Themes** (Appearance): Powder Black (default), Butter White, Follow system, System colors (Android 12+), Custom.
* Same APK, same icon, same process. The EKPE Python engine (Intel, Script Lab) is bundled with Chaquopy.
* Where both apps have a similar tool (Vault, Fonts, LAN, Radar/Radio, Audit, Sound/Noise, Network toolkit...) **both versions are kept**.

## Project structure
```
app/src/main/java/com/toolkit/privacy/
  core/    Theme.kt (colours, AppTheme), MasterSwitch.kt (stop/start everything)
  home/    Catalog.kt (the list of tools + sections), GlyphIcons.kt (icon set), HomeUi.kt (grid, pages), HomeApp.kt (drawer + navigation)
  *.kt     the Privacy Toolkit tools and services (one file per tool), MainActivity.kt = entry point
app/src/main/java/com/inwekobong/ekpe/   EKPE suite (views based), MainActivity.kt = EKPE screen
app/src/main/python, app/src/main/assets/radar   EKPE Python engine and radar page
```
To add a tool: add a `ToolDef(...)` line in `home/Catalog.kt` and a branch in `ToolContent` in `home/HomeApp.kt`.

## What changed technically
* `app/src/main/java/com/toolkit/privacy/*` - Privacy Toolkit code, unchanged except the new `core/` and `home/` packages and `MainActivity.kt`.
* `app/src/main/java/com/inwekobong/ekpe/*` - EKPE code, unchanged except `MainActivity.kt` (header with back arrow, opens a chosen tool, follows the theme and master switch) and `UiKit.kt` (theme colours).
* `app/src/main/python`, `app/src/main/assets/radar` - EKPE Python and radar assets, unchanged.
* `AndroidManifest.xml` - union of both manifests (all permissions, all services, the `<queries>` block). EKPE's activity is no longer a launcher
  entry; the main screen is the only launcher entry. EKPE's accessibility config was renamed to `ekpe_accessibility_config.xml` to avoid a name clash.
* `app/build.gradle.kts` - both dependency lists merged, Chaquopy plugin added, fixed signing config, APK named `EKPE`.
  Toolchain: AGP 8.5.2, Kotlin 2.0.20, Gradle 8.9, Chaquopy 17.2.0, Python 3.11 (3.12+ drops armeabi-v7a, so 3.11 is what keeps 32-bit phones working).
* ABIs: arm64-v8a, armeabi-v7a, x86_64.

## Build (GitHub Actions)
1. Push the **contents of this folder** to a GitHub repo (the `.github` folder must be at the repo root).
2. Actions tab -> "Build EKPE APK" (runs on every push, or press *Run workflow*).
3. When green, open the run -> Artifacts -> **EKPE-apk** -> `EKPE.apk`. The **build-log** artifact and the run summary list any compile errors.

Locally: JDK 17, Android SDK 34, Python 3.11 on PATH, then `gradle assembleDebug`.

## Permissions to allow after install
Location, Nearby devices, Phone, Contacts, SMS, Camera, Microphone, Notifications, Photos/Videos, overlay ("Display over other apps"),
Usage access, Modify system settings. Turn on Bluetooth, Wi-Fi and Location for Radar. Details for each tool are in the two original READMEs:
`README_PrivacyToolkit_original.md` and `README_EKPE_original.md`.
