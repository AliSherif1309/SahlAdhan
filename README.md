<div align="center">

# سهل للأذان · Sahl Adhan

**Prayer-time widgets and adhan alerts for Android, in Arabic and English.**

[![Latest release](https://img.shields.io/github/v/release/AliSherif1309/SahlAdhan?label=download&color=0B5D4B)](https://github.com/AliSherif1309/SahlAdhan/releases/latest)
[![Build](https://github.com/AliSherif1309/SahlAdhan/actions/workflows/build.yml/badge.svg)](https://github.com/AliSherif1309/SahlAdhan/actions)

</div>

---

## Features

| | |
|---|---|
| **3 home-screen widgets** | **Khatmah style** (all six times + "باق على" countdown), **Next prayer** (compact, live countdown) and **Today** (five prayers in one row, next one highlighted) |
| **Accurate offline times** | Calculated on the phone, no internet or location tracking. Default **Qatar (Awqaf)** method, matching the ختمة timetable for Doha |
| **Settings** | 13 cities (Qatar, Gulf, Makkah, Madinah, Cairo), 6 calculation methods, Standard or Hanafi Asr, ±15 minute adjustment per prayer |
| **Adhan alerts** | Per prayer: **Full adhan**, **Short alert** or **Off**. Works with the screen locked and the app closed. Stop button on the notification |
| **Choose your sound** | Full: Adhan 1 (2:34), Adhan 2 (0:42), phone alarm. Short: Takbir, soft chime, double chime, phone sound. Preview before choosing |
| **Arabic & English** | In-app language switch, Thmanyah Sans font, right-to-left layout |
| **Light & dark** | Emerald and gold, follows the phone's theme |
| **Self-updating** | Checks GitHub Releases on open and once a day, installs new versions in one tap |

## Install

1. Download the latest **`SahlAdhan-v1.x.apk`** from [**Releases**](https://github.com/AliSherif1309/SahlAdhan/releases/latest).
2. Open it and allow installing from your browser if Android asks.
3. Open the app, allow **notifications**, and tap **Test full adhan** with the screen locked.
4. Long-press the home screen → **Widgets** → **Sahl Adhan**.

### Honor / Huawei phones
To make sure alerts arrive when the phone is locked:
**Settings › Apps › Sahl Adhan › Battery › App launch → Manage manually**, and turn on all three switches.

## Updates

The app updates itself from this repository's releases:

- On open, a **"إصدار جديد متاح"** card appears when a newer release exists → tap **حدّث الآن**.
- Once a day it checks in the background and sends a notification.
- Or tap **التحقق من التحديثات** at the bottom of the main screen.

The first time, Android asks you to allow Sahl Adhan to install apps.

## How releases work

Every push to `main` builds the app and publishes a new release with the APK attached, so the app always finds the newest version.

## Project layout

```
app/src/main/java/com/sahl/adhan/
├── PrayerCalc.kt     Prayer-time math, cities and calculation methods
├── Prefs.kt          Settings, language, sound catalogue
├── Scheduler.kt      Exact alarms for prayers and widget refresh
├── Receivers.kt      Alarm, boot, time and time-zone events
├── AdhanService.kt   Plays the full adhan with a Stop button
├── Widgets.kt        The three home-screen widgets
├── Updater.kt        GitHub Releases update check and install
└── MainActivity.kt   Today screen and settings
```

Built with Kotlin and plain Android APIs (no extra libraries). Min Android 8.0, target Android 14.

## Credits

- **Adhan 1:** "Beautiful adhan.ogg" by Adam-synagda, [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Beautiful_adhan.ogg), CC0 1.0
- **Adhan 2 and Takbir clip:** "Adhan.ogg", [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Adhan.ogg), CC0 1.0
- **Soft and double chimes:** original, made for this app
- **Font:** Thmanyah Sans by Thmanyah
