# Contact Repair

Android app that repairs corrupt or nonstandard contact files (vCard, CSV, text) on-device and exports a .zip with a vCard 3.0 file, a Google Contacts CSV, and a repair report.

## What it does

- Opens .vcf, .vcard, .csv, .txt, and .zip files via the system document picker
- Repairs on-device: decodes BOM/UTF-16, joins folded vCard lines, decodes quoted-printable, parses phones with libphonenumber, normalizes emails, fixes shouty names, merges duplicates
- Writes one .zip: `contacts.vcf`, `google-contacts.csv`, `repair-report.txt`
- No internet, no contacts permission, no account

## Setup

```bash
# Source the environment (JDK 17, Android SDK, Gradle 8.13)
source $HOME/contact-repair/env.sh
```

## Build

```bash
# Run core unit tests
gradle :core:test --no-daemon

# Build release APK
gradle :app:assembleRelease --no-daemon

# Build release AAB (for Play Store)
gradle :app:bundleRelease --no-daemon
```

## Output paths

| Artifact | Path |
|---|---|
| Release APK | `app/build/outputs/apk/release/app-release.apk` |
| Release AAB | `app/build/outputs/bundle/release/app-release.aab` |
| Upload keystore | `secrets/upload-keystore.jks` |
| Keystore credentials | `secrets/keystore.properties` |
| 512 Play icon | `app/src/main/play/listings/en-US/graphics/icon/icon-512.png` |
| Play listing | `PLAYSTORE.md` |

## Modules

- `:core` — pure Kotlin JVM repair engine (no Android imports)
- `:app` — Android application (Jetpack Compose, Material 3)
