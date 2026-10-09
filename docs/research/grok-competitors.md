# Android competitive inventory for Activity+

Research date: 8 October 2026. Web sources only. No app was installed, and no device was measured for this note.

Activity+ on macOS (https://activityplus.xyz, https://github.com/robbyczgw-cla/activity-plus) is a system monitor whose product idea is responsibility plus a next step: which app is using the machine, a month of history, a plain-language answer to “why is this slow?”, alerts, a customizable menu bar, and no account or analytics. The Android app is planned as native Kotlin/Compose, with no root, and ideally with no `INTERNET` permission. This note inventories the Android apps already in that neighborhood and says what they leave open.

## How to read this

Store ratings, review counts, and install buckets move. Figures below are what public pages showed in late September and early October 2026. Where two counters disagree, both are given. “Play installs” means the Google Play bucket (`1M+`, `10M+`). Cumulative install estimates from AppBrain are labeled as estimates.

“Unverified” means a claim that showed up in only one secondary page, a marketing site, or an old document whose 2026 behavior was not re-checked. Exodus Privacy reports are static scans for known tracker signatures. Exodus itself says a signature is not proof the tracker runs.

## What Android lets a normal app see

The shape of this market is the permission model, not a lack of ideas.

- Live battery current, voltage, temperature, and level come from `BatteryManager`. They are available to a normal app. They are often the charger-side or one-cell reading, so watts disagree with the brick printed on the charger. DevCheck documents this directly: phones differ on whether they measure at the battery or the charger, and dual-cell packs can report one cell ([DevCheck FAQ](https://devcheck.app/faq)).
- Per-app battery in Settings is Android’s own power model (manufacturer power profile plus, on newer releases, measured energy where the hardware provides it). Since Android 4.4, raw battery statistics are not a normal-app API. GSam’s own guide says enhanced stats need the privileged `BATTERY_STATS` permission, granted with root or with an ADB `pm grant`, and that Pie and later further split what a normal app can call ([GSam user’s guide](https://blogger.gsamlabs.com/2011/11/badass-battery-monitor-users-guide.html)).
- Per-process CPU from `/proc` was closed to ordinary apps. Simple System Monitor’s own release note says Android Oreo blocked CPU-usage stats, and that from Nougat the process list collapses to the monitor itself unless the device is rooted ([Phoneky listing quoting the developer](https://phoneky.com/android/?id=d1d129769)). Shizuku or root can still run the shell commands that read those files.
- Foreground attribution without those privileges is `PACKAGE_USAGE_STATS` (the Usage Access toggle). That is screen time and “which app was in front,” not joules. AccuBattery is explicit that it multiplies measured milliamp-hours by foreground time, and that Android’s profile-based numbers are a different thing ([Play listing](https://play.google.com/store/apps/details?id=com.digibites.accubattery)).
- Per-app mobile and Wi-Fi byte counters are available through network-stats APIs, typically with usage access. Seeing every connection, and blocking it, is a different trick: a local `VpnService`. Android allows one VPN at a time. NetGuard documents that limit ([Play listing](https://play.google.com/store/apps/details?id=eu.faircode.netguard)).
- An app cannot draw arbitrary text into the status bar the way a Mac menu bar extra can. The practical substitutes are a persistent notification, a home-screen widget, a Quick Settings tile, and a draw-over-other-apps overlay. Overlays are what users reach for while a game is fullscreen, and they are also what Android warns about when the user tries to change permissions.
- Continuous monitoring means a foreground service. Reviewers notice when that service is itself the top battery user.

Shizuku (https://github.com/RikkaApps/Shizuku) is the 2024–2026 compromise: wireless debugging or a one-time ADB start, no root, and other apps can then hold privileged APIs. It dies on reboot until started again. Anything that needs it is a power-user feature, not the default path.

## The market in one page

Four products exist, and almost nobody ships more than one of them.

1. **Spec sheet.** CPU-Z, Device Info HW, and the free half of DevCheck. Identity of the SoC, cameras, sensors, and a live frequency. Useful when buying a phone or checking a repair. No story about which app is at fault.
2. **Battery diary.** AccuBattery and Battery Guru. Coulomb counting, wear, charge alarms, a foreground-app split, widgets. This is the mass market. Ads and a short free history are how they get paid.
3. **Expert drain tool.** GSam, BetterBatteryStats, BatStats. Wakelocks, partial wakes, per-UID power. This is the only group that can answer “what held the CPU awake.” The vocabulary is the feature, and it is also why most people never finish setup.
4. **Per-app network.** NetGuard and GlassWire. Bytes and a block switch. They do not know CPU or battery, and the firewall takes the VPN slot.

OEM software (Pixel Battery, Digital Wellbeing, Samsung Device Care) already does a weak version of (2) and a habit version of screen time, with a little diagnosis language arriving only on Pixel 10 and later. Charge limiting, which used to require root, is now a Samsung and Pixel setting.

The open product is the intersection Activity+ already occupies on Mac: one app named as the cause, a sentence that says what to do, a month of that history, a glanceable strip, and no account, ads, or analytics.

---

## App profiles

### DevCheck

- Package `flar2.devcheck`, developer flar2. Play: in-app purchases, no “Contains ads” line. About **4.5** from about **33k** ratings (Kotaku cited 4.5 / 33,184 votes on a 26 Sep 2026 snapshot; chrome-stats 4.54 / ~33k). Play installs **10M+**. AppBrain’s cumulative estimate was about 13 million. Version 6.57 around 26 Sep 2026. Listing: https://play.google.com/store/apps/details?id=flar2.devcheck
- **Measures:** SoC, per-core clocks, GPU, RAM, storage, display, cameras, sensors, battery (level, health, voltage, current, power, capacity when the driver exposes them), network, deep sleep, uptime. Pro adds a benchmark (CPU, memory, disk, GPU), battery monitor with screen-on and screen-off stats, usage stats, and more hardware tests ([APKMirror 6.57 notes](https://www.apkmirror.com/apk/flar2/devcheck-system-info/devcheck-device-system-info-6-57-release/), [FAQ](https://devcheck.app/faq)).
- **Per-app:** an installed-app browser (permissions, installer, manifest), not a power bill. “Running apps” memory is precise only with root on Nougat and later; the FAQ says root or Shizuku also unlocks per-app memory, CPU load, temperatures, charge cycles, and battery health. Usage Stats is a Pro tool. This is not “which app drained the battery” in the AccuBattery sense.
- **History:** live dashboard. No published 30-day per-app resource log. Benchmark rankings are optional uploads, aggregated by model.
- **Surfaces:** home-screen widgets and floating monitors are Pro. FAQ: floats can show clocks, temperatures, battery, network, memory, and load over other apps. No Quick Settings tile was described.
- **Diagnosis:** none in plain language. The app is a readout plus tests (root check, Wi-Fi scan, integrity check).
- **Privileges:** no root required. Shizuku is suggested and documented. Root is optional. Several permissions, which the listing says are for reading hardware.
- **Privacy:** developer states there are no ads and no analytics frameworks such as Google Analytics ([privacy policy](https://devcheck.app/app-privacy-policy/)). The same FAQ says the app sends anonymous hardware-verification requests, can submit an anonymous spec when a configuration is new, uses ipify to learn the public IP, and submits benchmark scores only if the user asks. So it is ad-free and not a tracker bundle, and it is also not an app with zero network.
- **Price:** free core. Pro is a Play monthly subscription, a yearly subscription, or a lifetime unlock. Prices are country-specific and were not copied here. Old one-time buyers keep Pro.
- **Complaints:** chrome-stats’ review summary (30 Sep 2026) clusters on licensing, restore-purchases, and cross-device entitlement, plus wrong screen size or OS/API reporting. The FAQ itself is a list of the accuracy complaints: missing temperatures on MediaTek, wrong marketing name for a chip, camera count that does not match the advertisement, charge current that looks “too low” on fast charge. A Pixel subreddit thread compares DevCheck cycle counts with AccuBattery and with the OEM health page and finds disagreements ([thread](https://www.reddit.com/r/pixel_phones/comments/1lfw37i/is_devcheck_legit_on_battery_report)).

Closest cousin to a hardware Activity+ dashboard. Weak on responsibility and on history.

### CPU-Z

- Package `com.cpuid.cpu_z`, CPUID. **Contains ads** and in-app purchases. Play rating about **3.2** from on the order of **360k–377k** reviews, **100M+** installs. Updated 1 Aug 2026, version 1.60 ([Play](https://play.google.com/store/apps/details?id=com.cpuid.cpu_z), [APKMirror](https://www.apkmirror.com/apk/cpuid/cpu-z/cpu-z-1-60-release/cpu-z-1-60-android-apk-download/)).
- **Measures:** SoC name, architecture, per-core clock, device model, screen, RAM, storage, battery level/status/temperature/capacity, sensor list. A system-of-record for “what chip is this,” kept current for new silicon (the 1.57/1.60 notes name Snapdragon 8 Gen 5, Tensor G5, and others) ([CPUID Android page](https://www.cpuid.com/softwares/cpu-z-android.html)).
- **Per-app:** no. **History:** no usage history described.
- **Surfaces:** none that matter. A 30 Aug 2026 Play review asks for notifications so sensors can be watched while another app is open. That single review is the product gap in miniature.
- **Diagnosis:** none.
- **Privileges:** no root, no usage access, no overlay described. `INTERNET` is required for “online validation,” which uploads the hardware spec into CPUID’s database. `ACCESS_NETWORK_STATE` is declared for statistics ([Play listing](https://play.google.com/store/apps/details?id=com.cpuid.cpu_z)).
- **Privacy:** ads. The in-app purchase removes them ([CPUID FAQ](https://www.cpuid.com/softwares/cpu-z-android.html)). Validation is an upload by design. The 1.57 notes include “fix intrusive ads,” which is a developer acknowledgement of the complaint.
- **Price:** free with ads. Ad-removal IAP; the price was not captured.
- **Complaints:** the 3.2 rating, across hundreds of thousands of reviews, is the signal. Documented themes are ads, no monitor-while-I-use-the-phone, and battery use of the app itself (the vendor FAQ has a heading for it). It remains installed because people want the chip name, not because they want a monitor.

### AccuBattery

- Package `com.digibites.accubattery`, Digibites (Rotterdam). **Contains ads** and in-app purchases. About **4.6** from about **585k–606k** reviews, Play installs **10M+**. AppBrain estimated ~41 million cumulative installs and ~470k in a recent 30 days (early Oct 2026). Version in the wild is 2.1.x (Exodus and AppBrain say 2.1.8; one Play snapshot still described an older build dated 6 Nov 2025). Listing: https://play.google.com/store/apps/details?id=com.digibites.accubattery
- **Measures:** coulomb count from the charge controller, estimated full capacity in mAh, wear per session, design-capacity override, charge and discharge speed, voltage, remaining charge time, screen-on vs screen-off, deep sleep, Bluetooth device battery is **not** the core pitch (that is Battery Guru).
- **Per-app:** yes, and the method is the whole design. Measured mAh is assigned to whichever app is in the foreground. The listing says Android’s profile-based percentages are a different, often inaccurate, number. Background drain is therefore invisible except as “screen off” or deep sleep. Exodus and chrome-stats both show `PACKAGE_USAGE_STATS`.
- **History:** free tier keeps sessions for **1 day**. Pro unlocks older sessions. A secondary pricing page, citing a Zendesk article, says Pro shows the last 150 session cards ([Laptops251](https://laptops251.com/programs/accubattery/), checked by that site 22 Sep 2026). Day/week/month capacity charts and cycle counts over 7 days, 30 days, 1 year, and since install are described on NamuWiki for v2.1.4; treat the exact chart set as **unverified for the current build** ([NamuWiki](https://en.namu.wiki/w/AccuBattery)).
- **Surfaces:** ongoing notification (detailed stats are Pro), charge alarm from 50–99%, Pro stats overlay (`SYSTEM_ALERT_WINDOW` is in the Exodus permission list). No Quick Settings tile was confirmed.
- **Diagnosis:** charge-to-80% advice via the alarm, and a wear number. No “your phone is slow because…” and no action that opens the offending app’s battery restriction.
- **Privileges:** no root. Usage access. Overlay for the Pro stats layer. `DUMP` appears in the Exodus permission list; whether the current app can use it without ADB was not verified.
- **Privacy:** the listing says the app does not need privacy-sensitive information. That sentence does not survive the artifacts. Play’s data-safety form, as quoted by a September 2026 review, says the app may share location, app activity, and device or other IDs ([iTechGuides](https://www.itechguides.com/best-system-monitoring-apps-for-android-devcheck-3c-aida64-and-accubattery/)). Exodus on 19 Nov 2025, version 2.1.8, found **8 tracker signatures**: ACRA, Amazon Ads, AppLovin, Facebook Ads, AdMob, Firebase Analytics, Google Tag Manager, IAB Open Measurement, plus `INTERNET` ([report](https://reports.exodus-privacy.eu.org/en/reports/com.digibites.accubattery/latest/)). Signatures are not proof each SDK phones home. Pro removes ads, not the fact of an ad-supported free tier.
- **Price:** free with ads. Pro is an IAP (themes, history, notification detail, no ads). A current dollar price was **not verified**. A modding site describes Pro as a subscription-style gate; that is an unofficial source and not a price.
- **Complaints:** health percentage disagrees with Samsung and with Battery Guru, sometimes by more than ten points, and both apps require long charges before the number settles ([MakeUseOf, 28 Sep 2026](https://www.makeuseof.com/my-galaxys-battery-said-it-was-healthy-until-accubattery-told-me-the-truth/), [r/LineageOS](https://www.reddit.com/r/LineageOS/comments/158uki2/discrepancies_in_battery_reporting_accubattery_vs/)). A recurring electrical complaint is wattage computed as current times 5 V, which under-reports VOOC/Warp and other high-voltage chargers ([r/batteries](https://www.reddit.com/r/batteries/comments/1r5bkeh/did_i_get_scammed_oneplus_new_battery)). Ads, and the one-day history wall, are the commercial complaints. Calibration delay (“it needs several cycles”) is both documented by the vendor and felt as a bug.

This is the app a normal person already has. It owns “mAh health” and a simple per-app list, and it spends that trust on ads and a foreground-only model.

### Battery Guru

- Package `com.paget96.batteryguru`, Paget96. **Contains ads** and in-app purchases. About **4.4–4.5** from about **32k–35k** reviews, Play installs **1M+**. AppBrain estimated ~3.9 million cumulative. Version 2.5.0.8 around early September 2026. Listing: https://play.google.com/store/apps/details?id=com.paget96.batteryguru
- **Measures:** estimated capacity and health over time, live current in mA and watts, voltage, temperature, time-to-full with screen on and screen off separated, charger and cable comparison, screen-on/off, awake, deep sleep, Bluetooth device batteries, always-on display mode ([APKMirror about-text](https://www.apkmirror.com/apk/paget96/battery-guru-health-saver/), [XDA thread](https://xdaforums.com/t/app-6-0-battery-guru-health-monitor.3980767/)).
- **Per-app:** “battery usage by app running in the foreground.” Same class of attribution as AccuBattery. The developer documents an optional deeper path: grant `BATTERY_STATS`, `PACKAGE_USAGE_STATS`, `WRITE_SECURE_SETTINGS`, and `DUMP` through Shizuku plus aShell ([guide](https://paget96projects.com/guides/grant-permissions-through-shizuku-and-ashell-applications)). What those grants add in the current UI was not independently listed beyond “additional permissions.”
- **History:** charging sessions (start, end, capacity). Pro: “full session history” and “complete health history.” A day count was **not verified**.
- **Surfaces:** home widgets (level and temperature, plus a clock widget), a draggable overlay (current, temperature, voltage), alarms, a temperature status-bar icon that users say breaks. A Play review from Indonesia (March 2026) says the battery-temperature notification icon does not show on Android 16; the developer replied that it should. No Quick Settings tile confirmed.
- **Diagnosis:** alarms and a health number. The listing explicitly says the app does not claim to speed charging or extend life. That is a useful restraint other “optimizer” apps skip.
- **Privileges:** usable with none beyond notifications. Usage access and Shizuku/ADB grants are optional. No root required.
- **Privacy:** ads. Pro is ad-free. “Watch a short ad for 24 or 48 hours of Premium, unlimited times” is in the listing. Data-safety details were not fully captured. AppBrain lists on the order of 18 libraries.
- **Price:** free with ads. Pro IAP, price not captured, plus the rewarded-ad rental.
- **Complaints:** ads (“I would rather use AccuBattery”), days of missing logs right when a drain needs diagnosing (Play review, 22 May 2026: the log only contains the seconds since launch), dual-cell capacity wrong even with the dual-cell toggle on, health that drifts while the OEM page says no degradation ([RuStore reviews](https://www.rustore.ru/catalog/app/com.paget96.batteryguru/reviews)), and a developer-facing bug report that the app takes the absolute value of current, so a slow charger plus a heavy load is counted as charging ([r/batteryguru](https://www.reddit.com/r/batteryguru/comments/1is3y09/some_issues_with_accuracy_of_battery_guru/)). Session cutoff at 100% while current is still flowing is the second half of that report.

Feature-richer than AccuBattery on widgets, overlay, Bluetooth batteries, and the Shizuku grant path. Less trusted on the health number, and the ad model is louder.

### GSam Battery Monitor

- Free package `com.gsamlabs.bbm`. **Contains ads**. English Play listing about **4.4** from about **69k** reviews, **1M+** installs, updated 2 Jan 2026, version 3.47 (“bug fixes for Android 16”). A Russian Play snapshot showed 4.0 for a similar review count; locale aggregates differ. Pro is a separate paid app, `com.gsamlabs.bbm.pro`, **$2.49**, about **8.5k** reviews, **50K+** installs, updated the same day. Listings: https://play.google.com/store/apps/details?id=com.gsamlabs.bbm and https://play.google.com/store/apps/details?id=com.gsamlabs.bbm.pro
- **Measures:** battery state, time-remaining from current and historical use, historical averages (“how long does the battery usually last?”), temperature graphs, sensor state overlaid on the battery graph. App Sucker sorts apps by CPU, sensor use, app wakelocks, wake time, and kernel wakelocks since a chosen time reference.
- **Per-app:** yes, and this is the real one. Since KitKat the good data is “enhanced stats”: `BATTERY_STATS` via ADB, not via a Play permission dialog. Root still works, through the old root companion that has not been updated as a separate APK since the Marshmallow era ([root companion](https://www.apkmirror.com/apk/gsam-labs/gsam-battery-monitor-root-companion/)). How-To Geek (13 May 2026) walks through the single `pm grant` and then the “background CPU only” sort, which is the feature people still install GSam to get ([How-To Geek](https://www.howtogeek.com/this-free-tool-helped-me-catch-the-apps-that-were-silently-draining-my-android-battery-gsam-battery-monitor/)).
- **History:** a movable time reference and averages. A retention length in days was **not verified**. It is “since last charge / custom range,” not a 30-day diary.
- **Surfaces:** optional status-bar notification with state and time left, home widget, a DashClock extension (legacy), an icon-pack add-on that skins the system battery icon. Pro adds tablet layouts, better long-standby estimates, custom alarms for level, temperature, and health, and icon themes. Overlay of *stats* is not the pitch; overlay of the battery *glyph* is.
- **Diagnosis:** the App Sucker is a ranked list with expert columns. A 2026 Play review asks for AccuBattery-style health in the same app and still prefers GSam for detail. There is no plain-language sentence and no “do this next.”
- **Privileges:** runs as a foreground service. Enhanced mode needs ADB or root. The 2011-era guide lists `INTERNET` for Flurry and Google Analytics, with an in-app opt-out. That analytics claim is **old** and was not re-verified against the January 2026 binary.
- **Privacy:** free app contains ads. Analytics status in 2026 is unverified. Pro is a paid download, which is the cleanest price in this set.
- **Complaints:** granting enhanced stats crashes or refuses to launch on some Samsung builds (Play, Galaxy S23 Ultra, May 2023, and the developer shipped Android 16 fixes only in January 2026). The app itself shows up as a top consumer (Galaxy S10 review: 34%). Unplug detection breaks and then every stat is wrong. The UI is a power-user tool that casual AccuBattery users bounce off.

GSam is still the reference for “background, not just foreground,” and it is a poor reference for language, history length, and setup.

### BetterBatteryStats (adjacent, still cited)

Not in the original list, included because every serious drain thread still names it. Package commonly `com.asksven.betterbatterystats` or the XDA edition. It reads partial wakelocks, kernel wakelocks, alarms, network, and CPU states. Full data needs `BATTERY_STATS`, `DUMP`, and usage stats via ADB, or root ([AndroidAyuda guide, 7 Oct 2025](https://en.androidayuda.com/betterbatterystats-the-best-application-to-optimize-the-battery/)). Alarms are called out there as root-only. Distribution has long been XDA/GitHub rather than a healthy Play presence. The UI is a spreadsheet of kernel names. It explains a drain to someone who already knows what a partial wakelock is. It does not explain it to anyone else. Current Play rating was **not verified**.

### 3C All-in-One Toolbox and 3C Task Manager

- Toolbox package `ccc71.at.free`, developer 3c. **Contains ads** and in-app purchases. About **4.3** from about **14.4k–15.7k** reviews, **1M+** installs. Play listing updated 4 Oct 2026. https://play.google.com/store/apps/details?id=ccc71.at.free
- Task Manager package `ccc71.tm`. **Contains ads** and in-app purchases. About **3.6** from **917** reviews on the Play snapshot. APKMirror shows 4.0.1a uploaded 9 Sep 2026. https://play.google.com/store/apps/details?id=ccc71.tm
- **Measures:** the toolbox is a suite: device profiles, file manager, app backup, network manager, task manager, CPU governor and thermal controls, logcat, terminal. The task manager shows CPU, network, and memory and sorts apps by usage. Battery watchers and “usage and standby statistics (find the app draining your battery)” are on the vendor feature list ([3c71.com](https://3c71.com/wp/toolbox/)).
- **Per-app:** yes, as a task list and a usage/standby view, plus backup and permission editing. Depth of the power model was **not verified** from a current screenshot.
- **History:** schedulers, profiles, and recorded parameters are Pro. A day count was **not verified**.
- **Surfaces:** many widgets (text and gauge, customizable). Pro adds a notification shortcut into features. Task Manager’s free widgets are 1×1 text and gauge.
- **Diagnosis:** profiles that apply CPU and radio policy, plus an automatic task killer. That is control, not an explanation. The vendor tells users that killing apps does not stop them restarting.
- **Privileges:** “best on root.” From Android 6, many features need root **or** the 3C Companion PC app. Task Manager uses an **Accessibility service** to force-stop apps on non-rooted devices and says it does not collect information through that service. Xposed unlocks niceness, permission control, and “crystallize” (keep an app from restarting). CPU governor writes need a custom kernel.
- **Privacy:** ads. Play data-safety for the toolbox says it may share location and device IDs, and declares no data collected, which is an odd pair and was not reconciled. A Play review says a firewall flagged the app.
- **Price:** free with ads. In-app unlocks for hiding tabs, schedulers, extra widgets, ad removal. An old AppBrain snapshot listed a separate “Pro key” around $5.99; that price is **stale** and should not be treated as current.
- **Complaints:** complexity (“data impossible to understand”), ads, features split into more apps (Task Manager review, 11 Jul 2026: “constant intrusive ads… they removed features so you have to install another app”), uncancelable automated tasks, backup restore failures (chrome-stats summary). A user on a Chinese download write-up reports that “kill all” in the task manager left Wi-Fi dead until a reflash. That is one anecdote, and it matches the class of bug a force-stop toolbox can cause.

3C is the cautionary all-in-one: power users who want knobs, a reputation for danger, and an ad-supported shell around features that used to be one paid app.

### Simple System Monitor

- Package `com.dp.sysmonitor.app`, Darshan Parajuli. Last build on APKMirror is **3.7.5, uploaded 26 Aug 2020** ([APKMirror developer index](https://www.apkmirror.com/apk/darshan-parajuli/)). The same developer was still shipping Logcat Reader in April 2026, so the author is active and this app is not. Whether a Play listing still exists in 2026 was **not verified**. A third-party page’s “updated May 2026” line contradicts APKMirror and looks untrustworthy.
- **Measures (when it worked):** per-core CPU use and frequency, time-in-state, GPU use and frequency (Adreno only, and often unreadable), RAM plus a “clear RAM” button, network throughput, disk I/O and a disk benchmark, a file browser, cache cleaner, temperatures, battery health and temperature, and a process list with per-process CPU, RAM, network, PID, and UID.
- **Per-app:** the process list was the attribution. The developer’s own note says that from Nougat the list shows only Simple System Monitor, and that CPU percentages do not work on Oreo and later without root.
- **History:** live graphs and frequency residency. No multi-day diary described.
- **Surfaces:** floating window, and a service that survives being swiped away. Widgets were not in the feature list captured.
- **Privileges:** root optional, required for process kill, cache cleaner depth, and for the post-Nougat process and CPU views.
- **Privacy / price:** no ad statement was verified. Treat the app as abandonware on current Android.
- **Complaints:** the release notes are the complaints. Android closed the data, the app could not follow, and the floating-monitor job moved to ad-supported clones.

Useful as a fossil of the feature list users still describe: per-core clocks, a tiny float, per-process CPU. Not a 2026 competitor.

### Ampere

- Package `com.gombosdev.ampere`, Brain_trapp. **Contains ads** and in-app purchases. About **4.5** from about **320k–331k** reviews, **10M+** installs. AppBrain estimated ~33 million cumulative. Version 4.36.1, updated 4 Jun 2026 (“Android 16 release”). https://play.google.com/store/apps/details?id=com.gombosdev.ampere
- **Measures:** charging and discharging current only, plus the conditions that move it (charger type, cable, screen brightness, Wi-Fi, GPS, foreground work). The listing says it samples, drops outliers, and averages, and that the user should wait through a “measuring” state. A 2026 Times of India piece describes about three samples a second and a trimmed mean; treat the exact filter constants as **press description, not vendor spec** ([Times of India, 3 Sep 2026](https://timesofindia.indiatimes.com/app-of-the-week-ampere-shows-you-what-your-charger-is-really-doing/articleshow/133736138.cms)).
- **Per-app:** no. **History:** no session diary in the listing.
- **Surfaces:** widgets, a notification, on-device alerts, and Android Wear alerts are **Pro**. The measuring screen wants the display left on; locking restarts the sample.
- **Diagnosis:** relative, on purpose. The listing, in bold, says the app is **not meant to be mA-accurate** and exists to compare charger and cable combinations on the same phone. Some Samsung devices report the maximum possible current rather than the measured one (S5 is the vendor’s example). A list of unsupported phones is in the listing.
- **Privileges:** no root, no usage access. `INTERNET` is implied by ads; the permission list was not fully captured.
- **Price:** free with ads. Pro IAP for the surfaces above. Price not captured. A Play review calls the ads unobtrusive and the buyout “a few bucks.”
- **Complaints:** 0 mA until “old measurement method” is enabled, stuck “measuring” after a firmware update (vendor FAQ: clear data), unsupported chipsets, and newer GaN chargers that users say read wrong ([RuStore reviews](https://www.rustore.ru/catalog/app/com.gombosdev.ampere)). People also want voltage next to current, because current alone lies about watts.

Ampere is a single-purpose instrument with an honest accuracy disclaimer. That disclaimer is the feature to copy. The lack of per-app and of history is the limit.

### Device Info HW

- Package `ru.andr7e.deviceinfohw`, Andrey Efremov. Play snapshot: **5M+** installs, about **13.8k** reviews, updated 31 Aug 2026. The star rating is inconsistent across mirrors: Gizmodo cited 4.6 / 13,468; chrome-stats on 6 Oct 2026 said **4.35 / 13,810**; AppBrain said **4.51 / ~12.4k**. Use “mid-4s, low tens of thousands of reviews.” The Play snippet did **not** say “Contains ads.” AppBrain’s table says no ads. https://play.google.com/store/apps/details?id=ru.andr7e.deviceinfohw
- **Measures:** component identity that other apps skip: LCD, touch controller, camera modules, sensors, RAM and flash chips, audio, NFC, charger IC, Wi-Fi chip, battery, thermal zones, kernel, partitions, codecs. Also CPU clocks and load when the kernel allows, a power profile, and small hardware tests (colors, multi-touch).
- **Per-app:** not a resource attribution app. An app list exists in some descriptions; it is an inventory.
- **History:** none described. Live thermal and CPU.
- **Surfaces:** no overlay, widget, or notification was confirmed for this package. (A different “Device Info” app, `com.liuzh.deviceinfo`, advertises a floating FPS/CPU/battery monitor. Do not merge them.)
- **Diagnosis:** none. It tells a technician what part is fitted. On recent Android it says some reads are blocked and how; root in settings reads more ([Play listing](https://play.google.com/store/apps/details?id=ru.andr7e.deviceinfohw)).
- **Privileges:** no root required; root optional. chrome-stats lists `INTERNET`, Bluetooth, high-rate sensors, and Wi-Fi state. Why it needs the network was **not verified**.
- **Privacy:** widely described by reviewers as lightweight and ad-free. An independent tracker scan was **not** pulled for this package. `INTERNET` means “no network” is not a safe claim.
- **Price:** free. A Gizmodo download page mentions a Pro upgrade; that was **not** confirmed on the Play listing captured, so treat Pro as unverified.
- **Complaints:** missing fields on new Android versions, and values that need root. The praise is accuracy of part numbers and the absence of ads, versus CPU-Z.

The spec-sheet specialist. Not an Activity+ competitor except as a reminder that part identity (which sensor, which cell configuration) is why battery numbers disagree.

### CPU Float and the overlay clones

**CpuFloat** (`com.waterdaaan.cpufloat`), XDA author waterdaan, first posted 2015. chrome-stats records version 2.3.8, last updated **27 Mar 2017**. A current Play listing was **not** found in this pass. Treat it as abandoned and possibly still sideloaded.

It showed, in a vertical or horizontal float or in the status bar: per-core CPU frequency, CPU and GPU temperature with a user trip point that turns the text red, GPU load and frequency, awake vs deep sleep since the app started, battery current and temperature, and up/down network speed ([XDA thread](https://xdaforums.com/t/app-cpufloat-march27-floating-cpu-gpu-temperatures-deep-sleep-network-monitor.3204420/)). Permissions named by the author: overlay, read storage, vibrate. The author said there were no ads and no internet data. Later chrome-stats reviews complain that GPU values are missing, the float sticks and blocks touches, and a 2026 review says it asks for photos and videos. Per-app: no.

The idea survived. The maintenance did not. Current Play clones:

- **Overlay Monitor CPU/Battery** (`com.lufesu.app.usage_overlay`), Lufesu. Ads and a one-time Pro IAP (interval, colors, ad removal). About **126** reviews, **5K+** installs, updated 17 Sep 2026. Overlay for estimated CPU (from current vs max frequency, which is not a scheduler load), memory, storage, and battery electricals. The developer replied on 6 Sep 2026 that per-app usage needs system access they will not take ([Play](https://play.google.com/store/apps/details?id=com.lufesu.app.usage_overlay)).
- **Floating Monitor for Cpu Ram** (`com.glgjing.floating.cpu.ram.monitor`). Ads. About **2.6** stars from ~163 reviews, **10K+**, updated 3 Sep 2026. CPU temperature, frequency, usage, RAM, battery level. Reviews: bubbles vanish, and “overrun with ads, uninstalled.”
- **System Monitor (SysMo)** (`com.outofthebox.androidperformancemonitor`), updated 23 Feb 2026. Floating CPU per core, RAM, battery, SoC temperature “on Snapdragon/Qualcomm.” Banner ads on the dashboard. Rating and install bucket were **not** captured.

Pattern: gamers will install a float; the floats that still ship are ad-funded, device-fragile, and explicit that they cannot name an app. DevCheck Pro is the maintained, paid version of the same float.

### Battery Charge Limit and the charge-limit cluster

**Battery Charge Limit** (`com.slash.batterychargelimit`), SLASH, GPL, root only. It writes a sysfs node so charging stops at a chosen percent and resumes below another. Not every device has a node, and some kernels or ROMs overwrite it. The XDA thread and README are the docs ([GitHub](https://github.com/sriharshaarangi/BatteryChargeLimit), [XDA](https://forum.xda-developers.com/android/apps-games/root-battery-charge-limit-t3557002)). GitHub still had open issues in February 2026, mostly docs and device support dating back years. A third-party APK site listed version 1.1.1 on 22 Mar 2026; that version was **not** matched to a GitHub tag in this pass, so currency is unverified. No per-app role. No ads expected of the open-source app; the Play distribution status in 2026 was **not verified**.

The job has mostly moved:

- **Samsung** Settings, Battery, Battery protection: Basic (gentle top-up at 100%), Adaptive (learns sleep), Maximum (stops at 80%). Menu names vary by One UI ([phoneguiding.com, 3 Oct 2026](https://phoneguiding.com/check-samsung-battery-health/)).
- **Pixel** Adaptive Charging slows a long overnight charge. A separate user-set “stop at 80%” control exists on recent Pixels; the exact menu name on every generation was **not** re-verified against a 2026 support page in this pass. Google’s help page documents Adaptive Charging and Adaptive Battery ([support](https://support.google.com/pixelphone/answer/14853368)).
- **Charge Control [ROOT]** (`com.rhs.ccontrol`) is a newer root app: start/stop thresholds, pause when hot, current limit on some devices, a one-shot charge to 100% from a **Quick Settings tile**, widgets, and a local session history ([Play](https://play.google.com/store/apps/details?id=com.rhs.ccontrol)). A 2023 review says LineageOS rewrites the node and Battery Charge Limit handles that better. Root-only.
- **AccA / Advanced Charging Controller** remains the rooted automation tool. Not researched in depth here.
- AccuBattery and Battery Guru ship an **alarm**, not a cutoff. The user still unplugs. That is the no-root behavior Android allows unless the OEM exposes a limit.

A no-root Activity+ should not pretend to stop charging. It can alarm, and it can deep-link to the OEM protection setting when one exists.

### Digital Wellbeing

System component, not a Play utility. On Pixel: Settings, Digital Wellbeing and parental controls. Required in spirit on GMS phones since Android 9/10; OEMs may ship their own screen-time UI ([historical write-up of the GMS expectation](https://thebinaryhick.blog/2020/02/22/walking-the-android-timeline-using-androids-digital-wellbeing-to-timeline-android-activity/)).

- **Measures:** screen time, per-app time, times opened, notification counts, and on some devices Chrome site time. Dashboard can be flipped between those series ([RottenWiFi, 8 Sep 2026](https://rottenwifi.com/what-is-the-digital-wellbeing-app-on-android/)).
- **Per-app:** yes, for attention, not for CPU, mAh, or bytes.
- **History:** the UI is today, with previous days available by swiping. A 2020 forensic note says the GMS contract required OEM replacements to keep at least a week, and that turning the feature off deletes stored usage within about 24 hours. **2026 retention was not re-verified.** It is not 30 days of resource data.
- **Surfaces:** widgets from Android 9 onward (press and help articles). Timers, Focus mode, Bedtime mode. No resource overlay.
- **Diagnosis:** habit tools. A timer grays the icon at the daily limit and can be dismissed. Focus mode has a break. This is not “why is the phone hot.”
- **Privileges:** it is the system. No extra grant.
- **Privacy:** on-device usage stats. Parental controls via Family Link are a different, account-backed product. No ads.
- **Complaints:** numbers that do not match the OEM’s other screen-time tool or an in-app counter; limits that are easy to override; no link from “you used Maps for 3 hours” to “Maps is why the battery fell.”

Digital Wellbeing is the screen-time half of a per-app story, already installed, and silent on resources.

### Google Battery usage and Pixel diagnostics

**Battery usage** (Settings, Battery, Battery usage, “view by apps”) is the list every other app is compared with. It shows apps and system components, and Google’s own drain guide tells the user to restrict background use there ([Pixel help](https://support.google.com/pixelphone/answer/6090599)). Secondary articles say Android 12 replaced a “since last full charge” headline with a rolling **past 24 hours** window, and that OEM skins differ ([GeekChamp](https://geekchamp.com/android-12-ditched-last-full-charge-battery-usage-stats-shows-past-24hrs/), [positioniseverything](https://www.positioniseverything.net/android-12-ditched-last-full-charge-battery-usage-stats-shows-past-24hrs-2/)). The current Pixel help page captured for this note does **not** state the window. Treat “24 hours on stock” as a well-repeated secondary claim, not as a line copied from Google’s 2026 help.

**Battery Usage Summaries**, Pixel 10 and later only: a short on-device Gemini Nano recap above the graph. Google’s help, as reported 2 Sep 2026, says it names the heaviest apps, watches background activity, and compares the period with the user’s recent average. Path given: Settings, Battery and charging, Battery usage, Summary. Requires a current AI Core. Pixel 9 and 8 are out ([AndroidPure](https://www.androidpure.com/pixel-battery-usage-summary/)). This is the first party feature that speaks a sentence. It is battery-only, flagship-only, and it does not say what to change beyond what the existing usage page already offers.

**Battery diagnostics**, Pixel 6 and later, including Fold: Settings, Battery, Battery diagnostics. Branches for draining quickly, running warm, and charging trouble. The drain branch shows health and links to Battery usage ([How-To Geek, 26 Aug 2026](https://www.howtogeek.com/google-pixel-phones-have-built-in-diagnostic-tools-that-nobody-talks-about/), [support](https://support.google.com/pixelphone/answer/14101646)). Sharing “device and battery diagnostics” with Google can include account, IMEI, model, settings, battery-health signals, and, if the user ticks a box, app battery usage. That upload is optional and is the opposite of Activity+’s privacy default.

**Repair diagnostics** (`*#*#7287#*#*` or the Pixel Troubleshooting surfaces) test display, cameras, sensors, radios, fingerprint, port, and speakers. How-To Geek and MakeUseOf (Sep 2026) describe on the order of 30 component tests. This is a pre-repair checklist, not a daily monitor. Wi-Fi is required for at least one entry path described by MakeUseOf; that detail is **press, not a Google spec**.

Pixel’s own guidance after an update is “wait a few days, Adaptive Battery is learning.” Third-party monitors exist partly because that sentence is not an answer when the phone is hot tonight.

### Samsung Device Care

Preinstalled. Settings, Device care (older copies: Device maintenance). Samsung’s support page, still current as a document dated around May 2026, describes an overview of battery, storage, and RAM, and **Optimize now**: find apps using “excessive” battery, clear memory, delete leftover files, scan for malware, close background apps ([Samsung US](https://www.samsung.com/us/support/answer/ANS10001953/)).

- **Battery usage:** per-app and per-feature. A TechBone guide updated 10 Aug 2026 says hourly bars for the current charge and for the **last 7 days**, with charge markers, under Battery statistics. The same page’s simulator is **Android 11 / One UI 3**. Treat 7-day hourly history as documented for that generation, **not confirmed here for One UI 8 or 9** ([TechBone](https://www.techbone.net/samsung/galaxy/battery-consumption)).
- **Battery health:** newer One UI shows a status and, on some models, a capacity percentage. Members diagnostics rate the battery Normal, Weak, or Bad ([Engadget, 26 Sep 2026](https://www.engadget.com/2265327/how-to-check-samsung-galaxy-phone-health-diagnostics-test/)). “Good” from the OEM and “76%” from AccuBattery is a published disagreement, not a corner case.
- **Battery protection:** Basic / Adaptive / Maximum (80%), above.
- **Sleeping apps:** Device Care watches launches and restricts apps the user does not open. That is automatic behavior change, not a report.
- **Diagnostics:** on Android 12 and later, a large manual suite (touch, cameras, mics, speakers, buttons, sensors, radios, SIM, NFC, USB, charging, fingerprint). Failed tests can offer a troubleshooting tip or a FAQ. One UI 9 adds a Warranty and Care hub (warranty, Samsung Care+, diagnostics, repair path, Bixby) on 2026 foldables ([Android Central, 18 Aug 2026](https://www.androidcentral.com/phones/samsung-galaxy/samsung-just-made-dealing-with-device-repairs-way-easier-on-galaxy-phones)).
- **Surfaces:** the Device Care screen itself, plus the system battery widget/status. No third-party-style overlay.
- **Complaints in the press:** it runs when the user is not in it, closes apps, and duplicates work Android already does with Adaptive Battery and standby buckets ([MakeUseOf, 28 Jul 2026](https://www.makeuseof.com/samsungs-device-care-runs-in-background-constantly-but-can-tell-to-stop/)). “Optimize” is a junk-cleaner metaphor. Users who have been burned by cleaners do not read it as advice.

Samsung is the closest OEM to a combined battery-plus-care UI, and it still does not keep a month, does not explain a stall, and acts on the user’s behalf by sleeping apps.

### NetGuard

- Package `eu.faircode.netguard`, Marcel Bokhorst / FairCode. In-app purchases, no ads line. About **4.2** from about **29k** reviews, **10M+** installs. Listing updated 17 Aug 2026. https://play.google.com/store/apps/details?id=eu.faircode.netguard — source https://github.com/M66B/NetGuard
- **Measures:** which apps attempt the network, optional per-address accounting, and a block decision for Wi-Fi and mobile separately. Not CPU, not battery, except indirectly (a blocked app cannot sync).
- **Per-app:** yes, for flows. Pro adds a searchable log, PCAP export, per-address allow/block, a new-app notification, and a **network speed graph in the status-bar notification**.
- **History:** the log is the history. A retention cap was **not verified**.
- **Surfaces:** the app list, notifications (access attempt, new app), Pro speed notification. A 1 Sep 2026 review with 86 helpful votes asks for that speed in the status bar itself, not only inside a notification. That request is the menu-bar gap again.
- **Diagnosis:** “this app talked to the network.” The action is a switch, which is the right action for a firewall and the wrong action for a CPU stall.
- **Privileges:** no root. Local `VpnService`. Cannot chain with a real VPN. No protection early in boot, which the FAQ states plainly ([FAQ](https://github.com/M66B/NetGuard/blob/master/FAQ.md)).
- **Privacy:** GPL-3, no ads, no analytics, “no calling home,” filtering on-device. The Play build **omits** the hosts-file ad blocker and port forwarding that other builds have, because of store policy. F-Droid builds exist; the author says he does not support them because he does not control their update timing. Trust is high because the code is public, and the VPN slot is still a privilege.
- **Price:** free for allow/block. Pro is a one-time IAP. A 2020 review cites about $8.50 lifetime. **That price was not confirmed on a 2026 price sheet.**
- **Complaints:** VPN conflicts, the boot gap, wanting a true status-bar speed readout, and confusion with fake “NetGuard Pro” repacks. Power users also want the hosts blocking the Play build cannot ship.

### GlassWire

- Package `com.glasswire.android`, Domotz Inc (the Android app; desktop GlassWire is a separate paid product). About **4.1** from about **30k–31k** reviews (chrome-stats 4.14 / 31,244 on 5 Oct 2026), **1M+** installs. Updated 3 Sep 2026. https://play.google.com/store/apps/details?id=com.glasswire.android
- **Measures:** live and historical per-app mobile and Wi-Fi bytes, a graph the user can scrub, speed, data-plan alerts before a cap, and a firewall with separate profiles for mobile and Wi-Fi. Tapping an app can show the system app screen, including force-stop.
- **Per-app:** yes, for bytes. This is the clearest “which app used the data” UI in the set. Not CPU or battery.
- **History:** the vendor says the graph goes back through the week or month ([product page](https://glasswire.com/glasswire-for-android/)). Exact retention in days was **not** copied from a settings screen. Desktop GlassWire’s free tier is a 1-day history; **do not apply desktop plan limits to Android.** Since April 2024 the Android app’s former paid features, including the firewall, are free ([blog](https://www.glasswire.com/blog/2024/04/17/free-android-app/)).
- **Surfaces:** graph, alerts, firewall. A persistent notification is implied by background monitoring. Widgets and Quick Settings were **not** confirmed.
- **Diagnosis:** “this app started using the network” and “you are near the cap.” The firewall is the action. chrome-stats’ review summary praises the picture and the block switch, and clusters complaints on background monitoring that stops, a frozen notification, and speed figures that stall.
- **Privileges:** firewall uses `VpnService`, same single-slot limit as NetGuard. The vendor says the app itself has no network connectivity and that usage never leaves the phone ([help](https://glasswire.com/android-help/)). That is a strong claim and was **not** checked with a tracker scan in this pass.
- **Privacy:** “never shows ads.” No account required for the Android monitor, as described. Desktop pricing (a Personal plan around $2.99/month on a third-party pricing page) is irrelevant to the phone app.
- **Complaints:** it falls asleep, the speed trace lies by going blank, and a slice of reviews wanted a security product rather than a usage picture. When it stays running, users call it the obvious data answer.

GlassWire is what “per-app, with a graph, and a next step” looks like for bytes. It does not generalize, and the VPN makes it a poor roommate for a work VPN.

---

## Newer and Shizuku-based monitors

These matter more for a 2026 design than CPU-Z does, because they already use the permission ladder a no-root app must use.

### Device Watch

F-Droid `org.jarsi.devicewatch`, GPL-3.0-or-later, author jrs8205. Version **1.7.0** added to F-Droid on 5 Oct 2026, Android 8+. Not a Play app, so there is no Play rating. https://f-droid.org/packages/org.jarsi.devicewatch/ and https://github.com/jrs8205/Device-Watch

This is the closest existing shape to Activity+’s privacy and history goals.

- Widget, resizable, with battery, memory, CPU, storage, Wi-Fi, mobile data, uptime, today’s screen time.
- Dashboard tabs: Home, Apps, Device, Settings.
- Per-app screen-time donut, top data users, last opened, uninstall.
- **62-day** daily history, on device, of app screen time, screen-on time, unlocks, a filtered notification count, restarts, charging sessions, and storage used.
- Battery and charging sessions for **14 days**, with temperatures and rates.
- Alerts: charge reminders, mobile-data quota, hot battery, low storage, fast drain.
- Since-charge page: drop, drain per hour screen on and off, each app’s share, what kept the phone awake. The battery-stats portion is the optional Shizuku/root path.
- Shizuku or root, off unless the user enables it: real CPU load, chip temperatures, GPU load, battery health, cycle count.
- Charging screensaver (clock, alarm, notification icons, watts).
- CSV and a self-contained HTML report.
- **No `INTERNET` permission.** The 1.7.0 notes say nothing is read until that probe is switched on. Android backup stores settings only, not history.
- Permissions that are still broad: usage stats, query-all-packages, foreground location and nearby Wi-Fi (for the network details), phone state, boot, wakelock, notifications. Location is a real privacy cost even with no network permission.

What it does not do: a sentence that says why the device is slow or hot, a customizable “menu bar” beyond one widget, and 30 days of per-app **CPU or milliamp-hours**. Fourteen days of battery sessions and 62 days of attention counters are adjacent, not the same series. There is no Play-scale evidence of what mainstream users think of it.

### BatStats

GPL-3, https://github.com/mlm-games/batstats. AlternativeTo lists the repo at about 222 stars with a push around 30 Sep 2026. No Play rating was found. The feature list below is the project’s own description ([AlternativeTo mirror of the README](https://alternativeto.net/software/batstats/about/)).

- Live level, current, voltage, temperature, watts. Sample interval 5–60 seconds.
- Charge and discharge sessions with estimated capacity.
- **Per-app drain in two modes:** heuristic (usage stats, foreground) and enhanced (Shizuku, system battery stats).
- Enhanced breakdown per app: CPU, wakelock, network, GPS, sensors, camera, Bluetooth; plus kernel and app wakelocks, per-app mobile/Wi-Fi, alarms, jobs, syncs, signal bins, Doze, process CPU time.
- Root adds cycles, true vs design capacity, health percent, kernel wakelocks, CPU time-in-state, thermal zones.
- Alarms for a charge limit (notify, not cut off), high temperature, high discharge.
- Three widgets: percent, temperature, time remaining.
- “No accounts, no cloud, no tracking.” Whether the manifest omits `INTERNET` was **not** verified from the manifest in this pass.

BatStats is the attribution ceiling for a no-root app in 2026: the same batterystats split GSam shows, reached through Shizuku, with a usage-stats fallback that is honest about being a fallback. It is young, small, and English-only on AlternativeTo. It is not a plain-language product.

### Scene

Chinese performance suite, long associated with package `com.omarea.vtools` (a code overview still describes Scene 4.7.3 as root-first and Xposed-capable). A 2026 marketing site talks about Scene 8.1.9, Android 16, and an ADB or Shizuku mode for “most core features” without root ([scene5.com.cn](https://www.scene5.com.cn/), marketing copy, treat download and “100% free” claims as **unverified**). A December 2024 Japanese walkthrough shows the practical UI: classic and mini floating monitors (CPU, GPU, RAM, battery), a process list sorted by CPU, a thread monitor for the foreground app, an FPS recorder with power, and temperature tiles. CPU/GPU clocks and some sensors are SoC-dependent or paid. Per-app **performance profiles** (clocks, refresh rate) are the point, not a diary ([ritorain.jp](https://ritorain.jp/17945/)).

Scene is what a gamer installs instead of CpuFloat. It is a tuner. Privacy, ads, and account requirements of the current distributor were **not** verified. It is a poor model for a no-`INTERNET`, no-root, explain-don’t-tweak app, and a good model for the overlay and the “this foreground app, these threads” view.

### Other notables

- **App Manager** (`io.github.muntashirakon.AppManager`), F-Droid, actively updated (4.1.1 around Oct 2026). Usage access shows per-app screen time, mobile and Wi-Fi data, and storage. ADB or root adds process list, force-stop, battery-optimization toggles, and net policy. It is a package manager that happens to show usage, not a monitor. https://f-droid.org/packages/io.github.muntashirakon.AppManager/
- **Battery Monitor** (`codes.swistak.batterymonitor`), F-Droid. Persistent notification or, on Android 16, a Live Update; alarms; event history with CSV; widgets; optional root or Shizuku for deeper battery fields; “does not collect personal data.” Per-app attribution was **not** in the feature list captured. https://f-droid.org/packages/codes.swistak.batterymonitor/
- **Minimal Kernel Manager** (`com.ivarna.mkm`), F-Droid, GPL-3. Real-time overlay, CPU/GPU clocks, thermals, RAM and swap. Root or Shizuku. A tuner with a monitor, updated into 2026. https://f-droid.org/packages/com.ivarna.mkm/
- **SysReadout Launcher** (AndSni, GitHub). A home screen that is the monitor: optional rows for CPU, memory, thermals, watts, network, and, with Shizuku, processes by CPU or memory, connections per app, wakelocks, per-app battery, per-core load. F-Droid submission was in review when the README was captured. https://github.com/AndSni/SysReadout-Launcher
- **System “cleaner” and “battery doctor” apps** were excluded on purpose. Their reviews are ads, aggressive notifications, and optimization claims. They are the reputation Activity+ has to avoid, not a feature source.

---

## Feature matrix

Legend: **Y** yes on the default path, **P** partial, paid, OEM-only, or needs Shizuku/ADB/root, **N** no, **?** not verified. “No net” means the app can do its job with no `INTERNET` permission. “Advice” means a sentence that names a cause and a next step, not a raw chart.

| App | Live CPU | Battery electrical | Per-app battery | Per-app CPU | Per-app data | History | Widget | Ongoing notif. | Overlay | Plain advice | No root | No net | Ads |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DevCheck | P | Y | N | P | N | N | P | ? | P | N | Y | N | N |
| CPU-Z | Y clocks | Y basic | N | N | N | N | N | N | N | N | Y | N | Y |
| AccuBattery | N | Y | P foreground | N | N | P 1 day free | N | P | P | P charge alarm | Y | N | Y |
| Battery Guru | N | Y | P foreground | N | N | P sessions | Y | Y | Y | P alarms | Y | N | Y |
| GSam | P | Y | Y with ADB | P with ADB | N | P custom range | Y | Y | N | N | P | ? | Y |
| 3C Toolbox | P | P | P | P | P | ? | Y | P | ? | N | P | N | Y |
| Simple System Monitor | P dead on modern OS | Y | N | P root | P old OS | N | N | P service | Y | N | P | ? | ? |
| Ampere | N | Y current | N | N | N | N | P | P | N | N | Y | N | Y |
| Device Info HW | P | Y | N | N | N | N | N | N | N | N | Y | N | N |
| CpuFloat (2017) | Y | Y current | N | N | speed only | N | N | P status text | Y | N | Y | Y claimed | N |
| Battery Charge Limit | N | P | N | N | N | N | ? | Y | N | N | N | Y likely | N |
| Digital Wellbeing | N | N | N | N | N | P days to ~a week | Y | N | N | P timers | Y | Y | N |
| Pixel Battery | N | Y | Y | N | N | P ~24 h, unverified | system | system | N | P Pixel 10+ summary | Y | Y | N |
| Samsung Device Care | N | Y | Y | N | N | P up to 7 days on old One UI | system | system | N | P “optimize” | Y | Y | N |
| NetGuard | N | N | N | N | Y | P log | N | P speed is Pro | N | P block | Y | Y local | N |
| GlassWire | N | N | N | N | Y | P week/month claimed | ? | Y | N | P cap and block | Y | Y claimed | N |
| Device Watch | P | Y | P with Shizuku | P with Shizuku | Y totals | Y 62 d usage, 14 d battery | Y | Y alerts | N screensaver | P threshold alerts | Y | Y | N |
| BatStats | P root | Y | Y Shizuku, else foreground | P Shizuku | P Shizuku | P sessions | Y | Y alarms | N | N | Y | ? | N claimed |

Quick Settings tiles are rare in this set. The one confirmed example is Charge Control’s “charge to full once” tile. Status-bar **text** exists only as a notification or as CpuFloat’s old status-bar mode. Nobody ships a user-arranged strip of several stats that survives every screen, which is what Activity+’s menu bar is on the Mac.

---

## Gaps nobody fills well

**Which app is responsible, across more than one resource.** Stock Battery usage and GSam/BatStats can rank battery. GlassWire can rank bytes. Digital Wellbeing can rank minutes. DevCheck can show that the CPU is busy and not who is busy, unless Shizuku is up and the user reads a process list. No mainstream app puts those on one row per app. AccuBattery’s row is foreground time times milliamp-hours, so a navigator with the screen off and a misbehaving sync do not appear as themselves.

**A sentence, and a single next step.** Pixel’s Battery Usage Summary is the existence proof, and it is battery-only, Pixel 10-only, and still sits on top of the same app list. Samsung’s “Optimize now” closes apps and clears memory, which is an action without a reason. 3C’s task killer is the same action with more risk. GSam and BetterBatteryStats have the reason (“partial wakelock, 40 minutes”) and no sentence. The gap is: “Maps held the phone awake for 40 minutes overnight. Restrict its background use.” One app, one mechanism, one button into the system setting. Not a cleaner.

**A month of that story.** Device Watch’s 62 days are screen time, unlocks, and storage, with 14 days of charge sessions. AccuBattery’s free history is one day, and Pro history is sessions, not a daily per-app resource table. Samsung’s 7-day hourly chart, where it still exists, is the OEM ceiling and it is not portable. Digital Wellbeing is about a week of attention. Nothing surveyed keeps 30 days of per-app CPU, battery share, and bytes together.

**Glanceable, chosen by the user, without ads.** The Mac menu bar has no healthy Android equivalent. Users ask CPU-Z for a notification, ask NetGuard for status-bar speed, and install floats that then fill with ads or stop updating. DevCheck Pro’s floats and widgets are the best paid version and they are hardware tiles, not “the app at fault.” Device Watch’s widget is the best no-network version and it is one widget, not a layout the user edits stat by stat.

**Honest numbers.** Fast-charge watts, dual-cell packs, and OEM health versus coulomb counting are a known mess. The apps that publish one confident percentage earn one-star reviews the week they disagree with Samsung Members. Ampere is the only popular app that says, in the listing, that the number is for comparison. The gap is an app that shows the OEM figure and the estimate as two figures, and says which one it could not read.

**Background, on a phone that is not rooted, without a seminar.** The data is there in batterystats. Reaching it still means Shizuku or an ADB command that GSam has been documenting for a decade. Most people will not do that. A default path that is weaker and labeled, plus an optional path that is the real UID breakdown, is implemented by BatStats and almost nobody else, and BatStats does not explain the result.

**Privacy at the same time as the features above.** AccuBattery is the popular per-app battery app and it ships eight known ad and analytics SDKs. CPU-Z uploads hardware identity. Battery Guru rents Pro for an ad view. The no-network apps (Device Watch, NetGuard’s design, the abandoned CpuFloat) each cover a slice. None of them is the explained, month-long, per-app monitor.

**Charge control without root.** Solved by OEMs for the common “stop at 80%” case, and still alarm-only for everyone else. A third-party cutoff is not an available no-root feature. Building one would be pretending.

**“Why is it slow,” as distinct from “why is the battery down.”** Thermal throttling, a full disk, memory pressure, and a foreground app at a high clock are visible piecemeal in DevCheck, Scene, and the floats. None of them says the phone is slow because the skin is hot and the foreground game is the load. Device Care answers slowness by closing background apps, which is often the wrong cause.

## What users clearly want

Taken from review themes and from the features that keep getting rebuilt:

1. **Name the app.** App Sucker, AccuBattery’s per-app list, GlassWire’s graph, and the Pixel summary are the features reviewers praise when they praise anything.
2. **Separate background from “I was using it.”** GSam’s background-CPU sort is why a 2026 how-to still recommends a 2011-era app. Foreground-only lists get trusted until an overnight drain, and then they look empty.
3. **History longer than today.** The one-day AccuBattery wall is a paywall because people want the older sessions. Samsung bothers to keep up to a week. Device Watch’s 62-day counters are a direct build of this request.
4. **A number that stays visible inside another app.** Notification, widget, or a small float. The CPU-Z review, the NetGuard status-bar request, CpuFloat’s entire design, and DevCheck Pro’s floats are the same request.
5. **An alarm at a charge level, a temperature, a drain rate, or a data cap.** AccuBattery’s 80% alarm, GlassWire’s cap warning, and Battery Guru’s alarms are used. People do not want a new background “optimizer” running the policy for them.
6. **Charger and cable comparisons, with voltage as well as current.** Ampere’s install base (10M+, ~4.5 stars) is this feature alone. The complaints are missing voltage and impossible watts.
7. **No ads in a tool that runs all day.** CPU-Z’s 3.2 rating, Battery Guru’s “I switched back to AccuBattery” reviews, and the 2.6-star floating monitor that “got overrun with ads” are the same complaint. GSam Pro at a flat $2.49 and DevCheck’s lifetime option are the price shapes people accept. Subscriptions and “watch an ad for 48 hours of Pro” are the shapes they resent.
8. **Health as a trend, and a warning when the phone’s own number disagrees.** Users open AccuBattery because Samsung said Good. They then open a second app because the first percentage moved. They want the disagreement explained.
9. **Do not kill all apps, do not delete my files, do not take Accessibility.** 3C and Device Care own this distrust. Advice that deep-links to “restrict background” or “uninstall” matches the Pixel help article people can already follow. A turbo button does not.
10. **Data per app without giving up a VPN** when the user already has one. GlassWire and NetGuard are loved until they collide with a work VPN. Byte counters from network stats do not need that slot. A firewall does.

## Top 10 recommendations

Ranked for the Android Activity+. Each line assumes no root and a default install with no `INTERNET` permission.

1. **A home card that names one app, one mechanism, and one next step** — every popular tool stops at a chart, and the only sentence on the market is a Pixel 10 battery recap.
2. **Make the app the unit of the UI, with battery, screen time, and bytes on the same row** — users already look this up in three different places and never see one culprit.
3. **Keep 30 days of those daily rows on device** — the longest honest histories nearby are a week of OEM battery or Device Watch’s 62 days of attention, not of responsibility.
4. **Ship with no `INTERNET` permission, no account, and no ads** — the best-known per-app battery app has eight tracker signatures, and ad-supported floats are the ones users uninstall.
5. **Use a permission ladder and label the method** — Usage Access for a foreground estimate on day one, optional Shizuku for real batterystats and process CPU, and never a single unlabeled percentage.
6. **Replace the menu bar with a user-built widget, a persistent notification, an optional small overlay, and one Quick Settings tile** — those are the only surfaces Android still allows, and each one is a feature users already beg other apps for.
7. **Alert in a sentence when drain, heat, or data is abnormal, and name the app** — threshold notifications exist everywhere; none of them say who, or what to tap.
8. **Show OEM battery health and a coulomb estimate as two numbers, plus voltage next to current** — false precision on watts and “health %” is the most common trust failure in this category.
9. **Count per-app network use from network stats, and do not take the VPN slot** — GlassWire and NetGuard already own firewalls, and a VPN would break both the no-`INTERNET` goal and the user’s real VPN.
10. **Deep-link advice into system settings, and do not ship a cleaner or a task killer** — Device Care and 3C taught people that “optimization” means closing apps, which is the wrong fix for a wakelock and a reputation problem.

Explicit non-goals, given the inventory: a rootless charge cutoff, a universal per-process CPU meter without Shizuku, a status-bar text row Android will not permit, and a second copy of CPU-Z’s spec sheet. Part identity can be a small Device screen later. It is not the product.

## Source list

Primary listings and docs:

- DevCheck Play: https://play.google.com/store/apps/details?id=flar2.devcheck
- DevCheck FAQ: https://devcheck.app/faq
- DevCheck privacy: https://devcheck.app/app-privacy-policy/
- DevCheck 6.57 notes: https://www.apkmirror.com/apk/flar2/devcheck-system-info/devcheck-device-system-info-6-57-release/
- CPU-Z Play: https://play.google.com/store/apps/details?id=com.cpuid.cpu_z
- CPU-Z vendor: https://www.cpuid.com/softwares/cpu-z-android.html
- AccuBattery Play: https://play.google.com/store/apps/details?id=com.digibites.accubattery
- AccuBattery Exodus (2.1.8, 19 Nov 2025): https://reports.exodus-privacy.eu.org/en/reports/com.digibites.accubattery/latest/
- Battery Guru Play: https://play.google.com/store/apps/details?id=com.paget96.batteryguru
- Battery Guru Shizuku guide: https://paget96projects.com/guides/grant-permissions-through-shizuku-and-ashell-applications
- GSam Play: https://play.google.com/store/apps/details?id=com.gsamlabs.bbm
- GSam Pro ($2.49): https://play.google.com/store/apps/details?id=com.gsamlabs.bbm.pro
- GSam guide: https://blogger.gsamlabs.com/2011/11/badass-battery-monitor-users-guide.html
- 3C Toolbox Play: https://play.google.com/store/apps/details?id=ccc71.at.free
- 3C feature list: https://3c71.com/wp/toolbox/
- 3C Task Manager Play: https://play.google.com/store/apps/details?id=ccc71.tm
- Simple System Monitor APKMirror index: https://www.apkmirror.com/apk/darshan-parajuli/
- Ampere Play: https://play.google.com/store/apps/details?id=com.gombosdev.ampere
- Device Info HW Play: https://play.google.com/store/apps/details?id=ru.andr7e.deviceinfohw
- CpuFloat XDA: https://xdaforums.com/t/app-cpufloat-march27-floating-cpu-gpu-temperatures-deep-sleep-network-monitor.3204420/
- Battery Charge Limit: https://github.com/sriharshaarangi/BatteryChargeLimit
- NetGuard Play: https://play.google.com/store/apps/details?id=eu.faircode.netguard
- NetGuard FAQ: https://github.com/M66B/NetGuard/blob/master/FAQ.md
- GlassWire Android: https://glasswire.com/glasswire-for-android/
- GlassWire help: https://glasswire.com/android-help/
- GlassWire free announcement (17 Apr 2024): https://www.glasswire.com/blog/2024/04/17/free-android-app/
- Device Watch: https://f-droid.org/packages/org.jarsi.devicewatch/
- BatStats: https://github.com/mlm-games/batstats
- App Manager: https://f-droid.org/packages/io.github.muntashirakon.AppManager/
- Pixel battery help: https://support.google.com/pixelphone/answer/6090599
- Pixel battery life settings: https://support.google.com/pixelphone/answer/14853368
- Pixel battery diagnostics share note: https://support.google.com/pixelphone/answer/14101646
- Samsung Device Care: https://www.samsung.com/us/support/answer/ANS10001953/

Reviews, press, and secondary (used for complaints and for behavior the listing does not spell out):

- How-To Geek on GSam (13 May 2026): https://www.howtogeek.com/this-free-tool-helped-me-catch-the-apps-that-were-silently-draining-my-android-battery-gsam-battery-monitor/
- AndroidPure on Pixel Battery Usage Summaries (2 Sep 2026): https://www.androidpure.com/pixel-battery-usage-summary/
- How-To Geek on Pixel diagnostics (26 Aug 2026): https://www.howtogeek.com/google-pixel-phones-have-built-in-diagnostic-tools-that-nobody-talks-about/
- MakeUseOf on AccuBattery vs Samsung health (28 Sep 2026): https://www.makeuseof.com/my-galaxys-battery-said-it-was-healthy-until-accubattery-told-me-the-truth/
- MakeUseOf on Device Care in the background (28 Jul 2026): https://www.makeuseof.com/samsungs-device-care-runs-in-background-constantly-but-can-tell-to-stop/
- Engadget on Samsung diagnostics (26 Sep 2026): https://www.engadget.com/2265327/how-to-check-samsung-galaxy-phone-health-diagnostics-test/
- TechBone on Samsung 7-day stats (page updated 10 Aug 2026, UI shown is One UI 3): https://www.techbone.net/samsung/galaxy/battery-consumption
- r/batteryguru current-sign bug: https://www.reddit.com/r/batteryguru/comments/1is3y09/some_issues_with_accuracy_of_battery_guru/
- r/batteries wattage complaint: https://www.reddit.com/r/batteries/comments/1r5bkeh/did_i_get_scammed_oneplus_new_battery
- Scene marketing (unverified claims): https://www.scene5.com.cn/
- Scene overlay walkthrough (Dec 2024): https://ritorain.jp/17945/

## Unverified or weakly sourced

- Exact 2026 retention of Digital Wellbeing, stock Battery usage (“past 24 hours”), and current One UI battery history. The 24-hour and 7-day figures come from secondary pages, and the Samsung page’s screenshots are One UI 3.
- AccuBattery Pro price, DevCheck Pro price, NetGuard Pro price, Ampere Pro price, Battery Guru Pro price. GSam Pro at $2.49 was read off Play.
- Whether GSam still sends Flurry or Google Analytics. The permission note is from the old user’s guide.
- Device Info HW’s star rating (sources span about 4.35 to 4.6) and whether a Pro SKU exists.
- CpuFloat’s presence on Play in 2026, and Simple System Monitor’s presence on Play after 2020.
- Battery Charge Limit version 1.1.1 (third-party APK site only).
- GlassWire’s “no network of our own” claim (vendor statement, no tracker report pulled).
- BatStats manifest: absence of `INTERNET` was not checked in source.
- Scene’s current package name, privacy, and the marketing site’s user counts.
- NamuWiki’s AccuBattery chart list (written against v2.1.4).
- AppBrain cumulative install totals. They are estimates beside Play’s buckets.
- Pixel “charge limit 80%” as a distinct toggle on every current model. Adaptive Charging is documented; the separate cap was not re-read from a Google help URL in this pass.
- Any claim about how these apps behave on a specific 2026 phone. This note did not install them.
