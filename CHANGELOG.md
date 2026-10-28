# Changelog

## 0.6.2

- Battery per app shows real mAh on phones whose battery report uses another unit: a vivo X300 on Android 16 reported "285078419 mAh" for YouTube, apparently microcoulombs (3.6 million per mAh). Activity+ now reads the unit from the report itself (its computed total against the charge the battery really lost, or its stated capacity), remembers it for the phone, and converts once the history already stored in the wrong unit

## 0.6.1

- "Since the update" no longer shows absurd factors ("8066270× as much") when an app used next to nothing before: Android reports idle apps with crumbs of a milliamp hour, and the finding now names the new amount instead ("now uses about 20 mAh a day in the background"); increases beyond 20× are told the same way
- The throttling finding gives advice that fits any app, not only games

## 0.6.0

- Today at a glance: the Battery screen opens with what matters now, then its cards in groups (Now, Last night, Apps, Charging and health, Planned and background); cards with nothing to say fold into one line with the reason; any card can be hidden in Settings. The Overview is shorter
- No silent gaps: every card says why it is empty and when that changes (no grant, too little history, not on this phone)
- Activity+ itself: its own CPU time, wakeups and wakelocks from Android's counters, and what runs in the background; heavy reports no longer run in Doze and wait for the screen when it is off
- Landscape, tablets and foldables: navigation on the side and two columns of cards
- Import: bring your history to a new phone from an export ZIP (merged, nothing overwritten)
- Accessibility: TalkBack reads charts and values, large text no longer clips, bigger touch targets, better contrast
- Translations reviewed in all languages

## 0.5.0

- Night check: every morning, did the phone sleep well? Deep sleep share, when deep Doze started, drain per hour against your usual nights, and a verdict with the next step: an app kept the phone awake (named with the grant), Android itself did not reach deep sleep, or the drain was high despite deep sleep (e.g. a weak signal). The last 14 nights at a glance
- "Above Google's limit": apps that keep the phone awake for 2 hours or more, as Google Play counts it, get a badge and a finding
- Planned for tonight: which app has set alarms and background jobs for the coming night, and which exact alarms ignore Doze (computer grant or Shizuku)
- Sensor log: which app used location, camera, microphone, body sensors or nearby devices, when, and whether in the background (computer grant)
- Since the update: what changed for an app after an update (background battery, wakeups, data, storage)
- Heat and throttling: when Android slowed the phone down because of heat, and which app was on screen; heat per app
- Time remaining by your pattern, charging habits with advice and a battery ageing forecast, and the cost per hour of use per app
- Share a report as text or image, export your data as CSV/JSON in a ZIP, and two new widgets: night check and a 6-hour history graph
- With the computer grant Activity+ now also reads Android's read-only reports on app ops, alarms, jobs, thermal state and Doze

## 0.4.0

- Who woke the phone: wakeups, wakelocks, CPU time and jobs per app from Android's own counters, for today and last night, with an "unusual" badge, a 7-day view in each app's details and a line in the "Last night" card
- Battery passport: Android's state of health, manufacturing and first-use date, charging policy (e.g. limited to 80 %), original or replaced part, cycles per month, compared with Activity+'s own capacity measurement; each row only where the phone reports it
- Where the power goes: power per component (display, CPU, GPU, mobile radio …) from Android's power monitors on Android 15 and newer, as averages for the last hour and today; live values where the phone allows exact readings
- Charging as a Live Update on Android 16: level, power and "full at" in the status bar and on the lock screen while charging
- The pro mode can grant Activity+ two more read permissions (BATTERY_STATS, and where the phone knows it ACCESS_FINE_POWER_MONITORS), with one tap through Shizuku or with the computer line
- Status notification: long labels end with "…" instead of running into the next column

## 0.3.0

- Storage page (tap the storage card on the overview):
  - Breakdown: apps and their data, images, videos, audio, system and other, free
  - Growth per app over 7 and 30 days, unusual growth, and "storage full in about N days" from a daily snapshot (90 days)
  - Caches: total and the biggest apps, with a link to clear them in app info
  - Unused apps: not opened for 30, 60 or 90 days, with their size and an uninstall button (Android asks first)
  - Check media (asks for photo, video and audio access only when you use it): largest videos, screenshots and messenger media, old large files, duplicates; deleting goes through Android's own dialog
  - Storage speed test: sequential write and read, random reads
- "Storage used" as a status bar and widget value

## 0.2.0

- Background battery per app, automatically: with the computer grant Activity+ reads Android's battery report when you plug in, each morning and every 3 hours on battery, and keeps 30 days per app; a "Last night" card in the morning, "Background today" on the Battery screen, a 7-day line and an "unusual" badge in each app's details
- Charging: alarms at 80–95 %, when full and when the battery gets warm while charging; a 30-second charger and cable test with a saved list; the battery's measured capacity as a trend over months
- Weekly report every Monday: top apps by battery, screen time and data against the week before, screen-off drain, charge sessions
- "Measuring was interrupted": when the phone maker stops Activity+ in the background, a card shows the setting for your phone and can exempt Activity+ from battery optimization
- VPN apps such as Tailscale are no longer reported as "working in the background"
- The battery report is read with `dumpsys batterystats -c`, which only reads (no per-app CPU time in it)

## 0.1.2

- Measure precisely: with the computer grant in place, a running Shizuku can now be allowed too, for memory and CPU per app

## 0.1.1

- Choose the app's language on the first screen and in settings (Android 13+); the phone's language stays the default

## 0.1.0

First release.

- Overview with a verdict that names one app and the next step; live cards for battery, memory, network, storage, CPU clock and temperature
- Apps: battery (measured while on screen), screen time, data with the background share and storage per app; "unusual for this app"; app info one tap away
- Battery: power averaged and live, current next to voltage, percent per hour, time to full or empty, charge speed and charger offer, sessions from plug to unplug, Android's health next to the measured capacity, battery by app today
- History: 30 days on the phone, the app on screen at every point
- Why slow?: plain-language findings with a button into the right Android screen
- Hardware: device, chip with live clock per core, GPU, RAM split and pressure, storage volumes, display modes and HDR, Wi-Fi, cameras, Widevine level, hardware decoders, features, sensors
- Status bar: a live value as the icon, up to eight values in the notification, Quick Settings tile, alerts that name the cause
- Widgets: one value, a panel with six values and today's top battery app, a battery ring
- Pro mode on demand: battery per app since the last charge (background included), memory and CPU per app, through Shizuku or a one-time computer grant; only three read-only reports
- No internet permission; English, German, French, Spanish, Italian, Portuguese (Brazil), Japanese, Simplified Chinese
