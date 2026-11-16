# Contact Repair — Google Play Store Listing

## App Name
Contact Repair

## Default Language
English (United States)

## Category
Tools

## Tags
contacts, vcard

## Short Description (80 chars max)
Fix broken contact files on your phone. Nothing is uploaded.

## Full Description

Contact Repair fixes broken, corrupt, or nonstandard contact files right on your phone.

**Country list:** The country list follows the phone library's full region set — every territory libphonenumber can parse, including regions a short ISO list would drop. You can also type a calling code such as +90 to set the region for numbers that have no country code.

**What it accepts:** .vcf, .vcard, .csv, .txt files, and .zip archives containing them. Open one or more files through the system document picker.

**What it produces:** A single .zip file containing:
- contacts.vcf — UTF-8 vCard 3.0, ready to import
- google-contacts.csv — Google Contacts CSV with all standard columns
- repair-report.txt — human-readable summary of what was fixed and any warnings

**Original values kept in Notes:** When the app changes a field (a name, phone, email, or any other contact value), the previous value is written into that contact's Notes inside the exported file. The note block is labeled "Original details (before repair):" and appears in both the vCard NOTE and the CSV Notes column. If nothing changed, no note is added.

**On-device processing:** All repair work happens in memory on your phone. No files are uploaded to any server. The app has no internet permission and makes no network calls.

**How to import into Google Contacts:**
1. Unzip the .zip file (on your phone or computer).
2. On your phone: open Google Contacts → tap the menu → Import → select the contacts.vcf file.
3. On desktop: go to contacts.google.com → Import → select the contacts.vcf file.

**No address book access:** This app does not request READ_CONTACTS, WRITE_CONTACTS, or any storage permission. Your phone's address book is never read or modified.

## Contact Email
support@example.com
> **Publisher note:** Replace support@example.com with your real support email before submitting to Google Play.

## Privacy Policy

Contact Repair processes contact files entirely on your device. Here is our complete privacy policy:

- **No account.** The app does not require an account and has no login.
- **No internet permission.** The app does not declare or use the INTERNET permission. It makes no network calls of any kind.
- **No transmission.** Contact data from your files is never sent to any server, cloud service, or third party.
- **Processing in memory.** Files you choose are read into memory, repaired, and written to a .zip file you choose to save. The data exists only in the app's memory while the app is running.
- **User-chosen files only.** The app accesses only the specific files you select through the system document picker. It does not scan, index, or access other files on your device.
- **No sale of data.** We do not sell, share, or transfer any data to any third party.
- **No retention after process kill.** Contact data from your files is not retained after the app process is killed, except for the .zip file you explicitly save to your storage.
- **Original values in the saved zip.** The .zip file you save may contain original values in the Notes of each contact (the "Original details (before repair):" block). Those original values exist only in that saved file. They are not transmitted, uploaded, or stored anywhere else.
- **No analytics, no advertising ID.** The app does not collect analytics, does not use the advertising ID, and does not include any third-party SDKs.

> **Publisher note:** Host this privacy policy text at a public, stable URL (e.g., a web page on your domain) and provide that URL in the Google Play Console. The URL must be accessible without a login.

## Data Safety Form Answers

- **Data collected:** None. The app does not collect any data.
- **Data shared:** None. The app does not share any data with third parties.
- **Permissions:** The app declares no permissions. App info shows none. Access to files is granted by the system file picker, not by a permission.
- **Contact data in user files:** Processed locally on the device. Not collected by the developer. Not transmitted.
- **Analytics:** Not used.
- **Advertising ID:** Not accessed.
- **Data deletion:** N/A — no data is retained server-side.

## Content Rating

- No violence
- No social features
- No user-generated public sharing
- No unrestricted web view
- Not a dating app
- Not directed at children (13+)
- Age rating: follow the Google Play questionnaire result. Do not claim a specific ESRB letter.

## Ads
No.

## In-App Purchases
No.

## Government App
No.

## COVID-19
No.

## Target API
36 (Android 16). This meets the Google Play requirement that new apps and updates target Android 16 (API 36) or higher as of August 31, 2026.

## Signing

- **Play App Signing:** Enroll in Play App Signing in the Google Play Console.
- **Upload key:** `secrets/upload-keystore.jks`
- **Credentials:** `secrets/keystore.properties` (storeFile, storePassword, keyAlias, keyPassword)
- **Backup:** Back up both the .jks file and keystore.properties outside this machine before store submission. Losing the upload key after Play App Signing enrollment is recoverable only through Google's upload-key reset process.

## Artifacts to Upload

- **App bundle:** `app/build/outputs/bundle/release/app-release.aab`
- **512 icon:** `app/src/main/play/listings/en-US/graphics/icon/icon-512.png`
- **Phone screenshots:** Must be captured from a phone or emulator by the publisher. This environment did not boot an emulator. Three screens to capture:
  1. **Repair screen** — shows the "Choose contact files" button, country dropdown, and repair option switches.
  2. **Review screen** — shows the list of repaired contacts with counts and warning dots.
  3. **Save screen** — shows the "Save repaired contacts" button and import instructions.

## Permissions

The listing declares no permissions. App info shows none. Access to files is the system file picker — the user picks a file, and that grant is the access. Android does not list that grant under App permissions, which is why App info is empty.

> **Publisher note:** Do not declare all-files access (MANAGE_EXTERNAL_STORAGE) or contacts access (READ_CONTACTS / WRITE_CONTACTS) in the Play Console data-safety form. The app does not need them and does not use them.

## Declarations

- The app does not use the Photo and Video Permissions policy beyond the system document picker (SAF).
- The app does not use All files access (MANAGE_EXTERNAL_STORAGE).
- The app declares no permissions in its manifest beyond the app-internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (auto-generated by AndroidX).
