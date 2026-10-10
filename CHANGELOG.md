# Changelog

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
