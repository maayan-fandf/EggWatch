# EggWatch

Shows the QR ticket from the Egged app ("egg", `com.egged.egg`) on a Wear OS watch,
so the exit gate and the ticket inspector can scan your wrist instead of your phone.

> Unofficial personal project. Not affiliated with Egged or HopOn. Use it only to show
> your own valid ticket.

## How it works

- **Phone** — an accessibility service that only *watches* the Egged app. While Egged is
  on screen it takes a screenshot about once a second; when a QR code appears it copies
  the code's exact module grid (not a re-encoding of its text) and hands it to the watch
  over the Wearable data layer. It never taps, scrolls or types, and it is idle whenever
  Egged isn't in front.
- **Watch** — keeps the last ticket and shows it full-screen on white at full brightness,
  with how long ago it was captured. A new ticket buzzes the wrist with a notification.
- **Shared** — the phone ↔ watch contract (`/ticket` DataItem) and the module-grid codec.

Nothing leaves your devices: no network access, no accounts.

## What it can and can't do

- ✅ Once the ticket has been copied, the watch shows it with the phone locked in a pocket.
- ❌ It can't create a ticket. Egged only issues the light-rail / Carmelit / cable-car QR
  at the station, so the phone is still needed to start the ride (and to scan the code on a bus).
- ❓ If Egged rotates the code during a ride, the copy on the watch goes stale. The phone
  keeps a ride log so this can be checked on a real ride.

## Install

Both apps must be signed with the same key (they are when built together, or when both
APKs come from the same release).

### Phone (Android 11+)

1. Install `phone-debug.apk` (or build: `./gradlew :phone:assembleDebug`).
2. Open EggWatch → tap **הפעלה** → turn on **EggWatch – שיקוף כרטיס לשעון** in
   Accessibility settings. If Android says it's a *restricted setting*: Settings → Apps →
   EggWatch → ⋮ → **Allow restricted settings**, then try again.

### Watch (Wear OS 3+)

1. Watch: Settings → System → About → Versions → tap **Build number** 7 times.
2. Settings → Developer options → **ADB debugging** on → **Wireless debugging** on
   (watch on the same Wi-Fi as your computer) → **Pair new device**.
3. Computer:
   ```
   adb pair <ip>:<pairing-port> <code>
   adb install wear-debug.apk
   ```
   After pairing, the watch shows up in `adb devices` on its own (via mDNS).

### Check the link

In the phone app, tap **שליחת QR בדיקה**. A test code should appear on the watch.

## Development

- Toolchain: JDK 21+ (Android Studio's bundled JBR works), Gradle 9.6, AGP 9.4
  (built-in Kotlin), compileSdk 37.
- Build: `./gradlew :phone:assembleDebug :wear:assembleDebug`
- Send a test code without touching the phone (debug builds only):
  `adb shell am broadcast -a com.eggwatch.app.SEND_TEST -p com.eggwatch.app`
- Ride log (screens seen, codes appearing/disappearing, sends):
  `adb shell run-as com.eggwatch.app cat files/ride-log.jsonl`
