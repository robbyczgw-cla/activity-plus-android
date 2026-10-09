# Activity+ for Android: Feasibility Research (Haiku angle)

Scope: (A) what users of similar Android apps praise and complain about, and (B) whether each feature idea can be built as a no-root app on Android 13 to 16 (minSdk 29, targetSdk 36, ideally no INTERNET permission).

Date of research: 2026-10-08. Method: web search and a limited number of direct fetches of developer.android.com and Google Play policy pages. No app was installed or tested on a device.

## Verification legend

- **[V]** verified in a fetched or searched primary source (URL given)
- **[S]** supported only by a secondary or third-party source (URL given)
- **[K]** from general knowledge, not checked in this session. Treat as unverified until tested on a device.
- **[GAP]** not found

## Key findings (read this first)

1. **30-day history cannot be backfilled from Android.** UsageStatsManager gives aggregated usage, but I could not confirm its retention window. [GAP] The official reference does not state it in the portion I could read. Plan on the app sampling and storing its own history from the first day of install. Measure the real retention on a device (query 7, 14, 30 days back) before promising a 30-day view.
2. **Per-app battery drain is an estimate for every no-root app.** BatteryStats (`BATTERY_STATS`) is signature or privileged. AccuBattery's developer says per-app figures come from its own discharge readings combined with foreground-app attribution. [S] https://apps.apple.com/app/id6737747779 (listing text repeated on APKMirror). Estimating this way is the honest model for us too, and it should be labelled as an estimate.
3. **Per-app CPU and memory for other apps, and wakeups per app, are blocked without root or Shizuku.** `/proc` is mounted with `hidepid=2` and untrusted apps lost access to `/proc/stat` in Oreo. [V] https://android.googlesource.com/platform/system/core/+/c39ba5a (hidepid=2 commit) and the AOSP SELinux commit cited in section B2. Shizuku/ADB is the only realistic route for per-app CPU, wakelocks and wakeups.
4. **Android 16 "Live Updates" is the only platform-native way to put a live value in the status bar.** It needs Android 16 (API 36) and `POST_PROMOTED_NOTIFICATIONS`. Older versions fall back to an ongoing notification. [S] https://proandroiddev.com/live-updates-in-android-16-exploring-the-next-evolution-of-notifications-1a5cf5de2068 and [S] https://docs.customer.io/integrations/sdk/android/live-notifications/set-up.md
5. **Widgets cannot be live.** Glance widgets refresh at most every 30 minutes via `updatePeriodMillis`. Anything faster needs WorkManager (15 minutes minimum, per secondary source). [V on the 30-minute ceiling from the official Glance guide, as summarised in search] https://developer.android.com/develop/ui/compose/glance/glance-app-widget.md.txt
6. **Play policy risk is concentrated in three permissions.** QUERY_ALL_PACKAGES is restricted and needs a declaration form. PACKAGE_USAGE_STATS is a special-access (Settings) permission; I could not confirm Play rules for it. `specialUse` FGS needs a Play declaration. SYSTEM_ALERT_WINDOW is a Settings-granted permission. Plan for an alternative to each (see section B12).
7. **Review sources were thin.** No Google Play review pages were reachable through search, and Reddit results were mostly mirrors or Samsung community threads. The Part A quotes below are therefore mostly from forums and listings. Treat them as directional.

---

# Part A: What users praise and complain about

Per-app coverage is uneven. Where I found nothing, I say so.

## A1. AccuBattery

Praised [S]: quick, plain battery health and capacity information; a simple per-app estimate.

Complaints:
- Capacity and health readings are unstable. One Galaxy user reports health dropping from 81% to 77% after install. Commenters replied that health is an estimate that needs several full charges to settle. https://red.applefritter.com/r/GalaxyNote20/comments/1oadu6e/this_is_odd (Reddit mirror, as surfaced by search)
- "Not accurate at all" because the app relies on the phone's own reported percentage. https://forums.androidcentral.com/threads/accubattery-health.950390
- Capacity figures fluctuate by up to about 10% on Samsung devices. https://r2.community.samsung.com/t5/Galaxy-A/Accu-battery/td-p/5646186
- Does not work on some older Samsung phones that do not expose the right data. [S] https://alternativeto.net/software/accubattery/about/
- Per-app numbers are attribution, not measurement. The developer describes this openly. [S] (listing text above)

Lesson for us: say "estimated" next to every per-app figure, and expose the sample count or the number of charge cycles behind a health estimate.

## A2. GSam Battery Monitor

Praised [S]: the per-app view (battery, CPU, network, sensors, wakelocks) is "the most useful part". Time-since-unplugged windows. https://www.apkmirror.com/apk/gsam-labs/gsam-battery-monitor (listing), and search summary of the same listing.

Complaints [S]:
- Without root, the view is limited. A user reports that "without root access Android System is all I can see". https://feddit.it/comment/9865118 (search-surfaced Reddit-style thread)
- Recent posts say the app may have been delisted. Unconfirmed. Check Play before relying on it. https://feddit.it/comment/9865118
- Older forum reports say some OEMs restrict battery-current readings, which limits accuracy. The thread dates from 2014. https://forums.androidcentral.com/threads/gsam-query.411400

## A3. Digital Wellbeing and Samsung screen-time reporting

Complaints [S], Samsung community mostly, plus one Reddit mirror:
- Background apps counted as active use. A gallery app showed over eight hours it was never opened. https://r2.community.samsung.com/t5/Suggestions/Digital-wellbeing-stock-showing-wrong-screen-time/td-p/14702549 (see also https://r2.community.samsung.com/t5/Tech-Talk/Digital-wellbeing-data-inaccurate/m-p/8474850)
- Media playback undercounted (2 hours of video shown as 3 minutes). https://r1.community.samsung.com/t5/support/issue-with-app-screen-time-in-digital-wellbeing/m-p/20616007/highlight/true
- Totals do not update after midnight, and history is not kept. https://r2.community.samsung.com/t5/Galaxy-Store-Apps-more/Error-in-Screen-time-count/td-p/12556973
- Reddit (r/opensource, May 2025) user asks for a free alternative because Digital Wellbeing did not work for them. One commenter reports Spotify running in the background added 10 to 15 hours of screen time. https://redlib.hackliberty.org/r/opensource/comments/1kknasz/foss_digital_wellbeing_app

Lesson for us: separate "foreground time" (what the user sees) from "background time" (what the system ran). Label them clearly, and do not merge them into one "screen time" number.

## A4. Samsung Device Care (battery and background limits)

Praised/used [S]: "Background usage limits" and sleeping or deep-sleeping apps. https://r1.community.samsung.com/t5/support/save-your-phone-s-battery-by-managing-background-apps/td-p/26171485

Complaints [S]:
- Apps still run after being put into deep sleep. A Galaxy M51 user reports this, and the reply was to disable background data per app. https://r2.community.samsung.com/t5/Galaxy-M/quot-device-care-quot-not-working-on-m51/m-p/7129905/highlight/true
- Users want to know which app is draining, and Device Care does not give a clear per-app drain answer in these threads. https://r2.community.samsung.com/t5/Galaxy-S/UPDATED-Resolve-S20-FE-Exynos-Battery-Drain-and-Heat-Issue/m-p/9276914/highlight/true

Lesson for us: offer a one-tap "what to do" action per culprit (restrict, sleep, uninstall), but do not change system settings without user consent.

## A5. Ampere (charging and battery readings)

Praised [S]: simple charge-current display; works well for many users.

Complaints [S]:
- Readings that contradict reality: a phone draining from 80% to 50% was shown as "charging". https://forum.earlybird.club/threads/ampere-app-strange-readings.1348652/
- Ampere and two other apps disagreed by up to 500 mAh. Same thread.
- A Samsung S5 showed fixed values, and a commenter says the Play listing notes problems with charging current on that model. https://forums.androidcentral.com/threads/ampere-app.628512/ (Play listing text itself not retrieved [GAP])
- A Polish article says the Play listing includes a list of unsupported phones and an "old measurement method" setting. [S] https://antyweb.pl/ampere
- Explainer: some firmware reports mA where Android expects µA (1000x error). Firmware sign bugs can flip charging and discharging. General context. https://androxus.com/blogs/why-battery-apps-show-different-readings

Lesson for us: detect and display the sign and unit convention for the device, and show an "unreliable on this device" state when readings look implausible.

## A6. GlassWire (network per app, no root)

Praised [S]: graph of which apps use mobile or Wi-Fi data; "Data Plan" limits per app; clear UI.

Complaints [S]:
- Background tracking stops on some phones (one vivo user): works in the foreground, nothing in the background. https://forum.glasswire.com/t/using-glasswire-on-non-root-android-phones/14104
- USB tethering traffic not detected; app auto-shuts down and misses data. Same forum.
- The developer's blog says Google made it impossible to graph network data in real time after an API change, and that the issue was marked "won't fix". https://www.glasswire.com/blog/?p=662 (exact post not verified)
- Per-app allow/deny was requested and not available on Android. https://forum.glasswire.com/t/glasswire-android-suggestion/4209
- Ratings fell after Android 10 issues. Unconfirmed on current devices. https://www.trustpilot.com/review/glasswire.com (may refer to Windows app)

Lesson for us: GlassWire is the closest precedent for a VPN-based approach. Its reliability complaints show why we should avoid VpnService (also a privacy and Play review risk).

## A7. DevCheck, CPU-Z and 3C Toolbox (hardware info)

Praised [S]: wide hardware info; live CPU and GPU frequencies; deep-sleep time; governor. https://www.xda-developers.com/devcheck-hardware-system-information-app/ and https://www.apkmirror.com/apk/flar2/devcheck-system-info/devcheck-system-info-4-34-release/

Complaints: none found in this session. [GAP] Play reviews were not reachable.

Limit noted by the listing [S]: some data needs root, and "rooted devices and Shizuku can unlock additional system information on compatible devices". Same APKMirror page.

Lesson for us: a clearly marked "more data with Shizuku" mode is an accepted pattern in this category.

## A8. Shizuku-based monitors

- Running Services Monitor (F-Droid) uses Shizuku because Android 8 restricted `getRunningServices`, and it needs Wireless Debugging on Android 11+. https://f-droid.org/eu/packages/me.biplobsd.rsm [S]
- BatStats offers an "enhanced" per-app breakdown through Shizuku (CPU, wakelocks, network, GPS, thermal zones, cycle count). It is open source (GPL-3.0). Its feedback base is thin (about 87 GitHub stars, no AlternativeTo reviews). https://alternativeto.net/software/batstats/about [S]
- aBattery needs root for full battery detail, and the German PC World article says Shizuku is a workaround for some of it. https://www.pcwelt.de/?p=2802713 (not verified which article matches) [S]
- Shizuku itself runs ADB-level calls on non-rooted devices. On Android 11+ it can start on the phone without a PC. Not every root-expecting app accepts Shizuku. [S] same PC World article.

## A9. Wakelock and wakeup tools (root-free route)

- XDA guide: grant BATTERY_STATS over ADB with `pm grant`, then use BetterBatteryStats. Requires a PC for setup. https://www.xda-developers.com/stop-wakelocks-android-without-root/ [S]
- The `pm grant` route is contradicted in the sources (BATTERY_STATS is listed as signature or privileged). [K] `pm grant` usually cannot grant signature or privileged permissions to ordinary apps on modern Android. Test before relying on it.

## A10. Wishes list (distilled from A1 to A9)

With sources:
1. Per-app drain that is honest about being an estimate (A1, A5). Sources above.
2. Stable health and capacity values that do not swing wildly (A1). Show sample size.
3. Per-app background use separated from foreground use (A3, A4).
4. Per-app network use, including in the background, that actually updates (A6).
5. Working on the phones people own, with a clear "not supported" message (A1, A5).
6. Per-app allow or deny, and a one-tap fix (A4, A6).
7. A clear "more with Shizuku/ADB" mode (A7, A8).
8. Free, no ads, no account (A8 and the reddit thread in A3).

Pain points, ranked by frequency across sources: inaccurate or unstable numbers; background tracking that stops silently; "without root you see nothing"; screen time that counts background time.

## A11. Gaps in Part A

- Google Play review text for AccuBattery, GSam, Ampere, GlassWire and Digital Wellbeing could not be retrieved. Search returned listing pages instead. [GAP]
- Reddit (r/Android, r/androidapps) coverage was thin. Most results were mirrors or Samsung community threads. [GAP]
- XDA threads for per-app CPU and memory without root were not found. [GAP]

---

# Part B: Feasibility per feature

Each entry: best-known app behaviour, the API, the permission, the limits, and whether the answer is "no root", "Shizuku/ADB", or "not possible".

## B1. Per-app battery drain

**Market practice.** AccuBattery combines charge-controller discharge readings with foreground-app attribution, and calls the result an estimate. [S] https://apps.apple.com/app/id6737747779 . GSam shows per-app battery, CPU, network, sensor and wakelock figures. Its no-root view is limited. [S] https://www.apkmirror.com/apk/gsam-labs/gsam-battery-monitor

**Platform.** BatteryStatsManager and `dumpsys batterystats` are system or shell only. [V that the BATTERY_STATS permission is signature or privileged, via search of the BetterBatteryStats docs] https://en.androidayuda.com/betterbatterystats-the-best-application-to-optimize-the-battery/ . Google's profiling guide uses `adb shell dumpsys batterystats`. https://developer.android.com/topic/performance/power/setup-battery-historian (search-surfaced)

**No-root approach.** Estimate: total device discharge (BatteryManager current × voltage, see B6) multiplied by each app's share of foreground time (UsageStatsManager, B5) and of network bytes (NetworkStatsManager, B3). Label it "estimated".

**Shizuku/ADB.** `dumpsys batterystats` can be read through Shizuku (shell-level). BatStats does this. [S] https://alternativeto.net/software/batstats/about

**Verdict.** Feasible as a labelled estimate, no root. Real per-app drain needs Shizuku.

## B2. Per-app CPU and memory of other apps

**Platform.**
- `/proc/<pid>/stat` and `/proc` are hidden from other apps. The Android `/proc` mount uses `hidepid=2`. [V] https://android.googlesource.com/platform/system/core/+/c39ba5a
- `/proc/stat` (system-wide CPU) is in an SELinux neverallow list for untrusted apps since Oreo. [V via search of AOSP commit history] https://gitlab.cs.fau.de/Matombo/AndroidSystemSEPolicy (commit history; the 2018 commit by Jeff Vander Stoep is the source). Secondary: a forum post reports EACCES for `/proc/stat`. [S]
- `ActivityManager.getRunningAppProcesses()` returns only the caller's processes, visible or foreground-service apps. For other apps you need PACKAGE_USAGE_STATS. [S] https://www.volcengine.com/article/663485 (translated Q&A; verify on device)
- `ActivityManager.getMemoryInfo()` is system-wide and needs no permission. [K]
- `Process.getElapsedCpuTime()` (API 34, [K]) and `Debug.threadCpuTimeNanos()` work for the app's own process only. [K]

**Shizuku/ADB.** `dumpsys cpuinfo` and `dumpsys meminfo` are shell commands and can be run through Shizuku. [K] Not tested here.

**Verdict.** Own-app CPU and memory: feasible, no root. Other apps' CPU and memory: not possible without Shizuku or ADB.

## B3. Per-app mobile and Wi-Fi data, foreground vs background

**Platform.** NetworkStatsManager.
- `queryDetailsForUid(networkType, subscriberId, start, end, uid)` returns history buckets. Own UID needs no permission from Android 7.0 (N). Other UIDs need `PACKAGE_USAGE_STATS`, which is a system-level permission granted by the user in Settings > Usage access. [V] https://developer.android.com/reference/android/app/usage/NetworkStatsManager (search summary of the reference)
- Bucket state (default vs foreground) via `NetworkStats.Bucket.getState()` (API 23) [K]. Use it to split foreground and background bytes.
- Thrown exception on permission failure is `SecurityException` (API 28 and later). [V] same reference.
- Mobile subscriberId needs phone-state privileges on some devices. [K] Test on device.

**Market practice.** GlassWire uses a VPN to get live per-app data. Its reliability complaints are in A6.

**Verdict.** Per-app totals and foreground/background split: feasible with PACKAGE_USAGE_STATS (user grant, no root). Live rates without a VPN: not feasible. Avoid VpnService.

## B4. Per-app storage, including cache

**Platform.** StorageStatsManager.
- `queryStatsForPackage(storageUuid, packageName, user)` (API 26). Own package needs no permission. Other packages need `PACKAGE_USAGE_STATS`. Slow, so run it on a worker thread. [V] https://developer.android.com/reference/android/app/usage/StorageStatsManager (search summary)
- `StorageStats` has `cacheBytes`, `codeBytes`, `dataBytes`. `dataBytes` already includes cache. Do not add cache to data. [V] same reference (search summary)
- `queryStatsForUid(storageUuid, uid)` is faster when shared UIDs are involved. [V] same reference.
- Deprecated `PackageStats.cacheSize` (API 26). Do not use. [V] same reference.

**Verdict.** Own app: feasible, no permission. Other apps: feasible with PACKAGE_USAGE_STATS (user grant). Cache for other apps is the same data, not a separate permission.

## B5. Screen time, launches, foreground-service time

**Platform.** UsageStatsManager.
- `queryUsageStats(interval, begin, end)` (API 21) gives totalTimeInForeground per app per interval. `queryEvents()` gives ACTIVITY_RESUMED and PAUSED and other event types. Requires PACKAGE_USAGE_STATS (Settings > Usage access). [K for API levels; V that the permission is special-access, via search summary of the reference] https://developer.android.com/reference/android/app/usage/UsageStatsManager
- **Retention: [GAP].** The reference did not state it in what I could read. Do not promise more than the window you measure on a device. Store your own history from day one (see Key finding 1).
- `getAppStandbyBucket()` (API 28) [K].
- Foreground-service time: derive from FOREGROUND_SERVICE_START and STOP events in queryEvents [K]. Test whether these events exist on each target version.
- Screen-on time: UsageStats does not give per-app screen-on time directly. The Digital Wellbeing complaints in A3 show why foreground time and background time must be labelled separately.

**Verdict.** Feasible, no root, one special permission (user grant), with a retention limit to be measured. Store history locally.

## B6. Battery current, watts, charge speed, health, cycle count

**Platform.** BatteryManager (no permission for these).
- `BATTERY_PROPERTY_CURRENT_NOW` (µA), `CURRENT_AVERAGE`, `CHARGE_COUNTER` (µAh), `ENERGY_COUNTER` (nWh). Added in API 21 [K, page truncated in fetch]. Sign convention and units are vendor-dependent. [S] https://forum.earlybird.club/threads/ampere-app-strange-readings.1348652/ (A5)
- Watts: current (µA) × voltage (from `ACTION_BATTERY_CHANGED` `EXTRA_VOLTAGE`, mV) / 10^9. [K]
- Health: `EXTRA_HEALTH` (categorical). Design capacity is not public. [K]
- Health estimate (inference, [K]): charge-counter change divided by percentage change across a full charge cycle. Requires several cycles. AccuBattery complaints in A1 show this needs time to settle.
- Cycle count: `EXTRA_CYCLE_COUNT` is an int extra on `ACTION_BATTERY_CHANGED`. A third-party .NET binding tags it as API 34. [S] https://developer.android.com/reference/android/os/BatteryManager (the official "Added in" line was not visible in the fetch). Gate it: show only on API 34 and above, else show "not available".
- Charge speed: current × voltage while plugged, with `EXTRA_PLUGGED` for source type. Adapter wattage is not a documented public value. [K]

**Verdict.** Feasible, no root, no permission. Health and cycles are device-dependent and must be labelled. Cycle count needs API 34 (per binding; verify).

## B7. CPU clock per core and CPU usage

**Platform.**
- Current frequency per core: `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq`. [GAP] I could not confirm whether `untrusted_app` can read this on Android 10 to 16. The AOSP history shows the rule has been changed more than once. [S] https://android.googlesource.com/platform/external/sepolicy/+/4e2d224 (commit history)
- Market practice: DevCheck and CPU-Z show per-core frequency. [S] https://www.xda-developers.com/devcheck-hardware-system-information-app/ . Whether they read sysfs or use another path was not verified.
- Overall CPU usage: `/proc/stat` is blocked for untrusted apps (B2). [V via AOSP commits]
- Governor and available frequencies: sysfs, same SELinux question.

**Verdict.** Per-core frequency: [GAP] test on the target device matrix. Possibly feasible, device-dependent. Overall CPU usage: not feasible without Shizuku or ADB.

## B8. Thermal status and headroom; thermal zones

**Platform.** PowerManager (no permission).
- `getCurrentThermalStatus()` and `addThermalStatusListener()`: API 29 [K]. Five levels: NONE, LIGHT, MODERATE, SEVERE, SHUTDOWN. [V the level names via search summary of the reference]
- `getThermalHeadroom(forecastSeconds)` (API 30 for SDK, [V via search summary: Android 11 introduced it; NDK API 31]). Scale 0.0 to 1.0 and can exceed 1.0. Gate to API 30 and above.
- `getThermalHeadroomThresholds()`: Android 15 preview per localized docs [S]. Gate it.
- Source: https://developer.android.com/games/optimize/adpf/thermal and https://developer.android.com/reference/android/os/PowerManager

**Thermal zones.** `/sys/class/thermal/thermal_zone*` is labelled `sysfs_thermal` and read access is granted to system components, not third-party apps. [S] AOSP commit history (https://gitlab.cs.fau.de/Matombo/AndroidSystemSEPolicy). The layout varies per vendor. [S] https://forum.fairphone.com/t/fp3-thermal-hal-non-functional-missing-sepolicy-incorrect-trip-point-index/133186

**Battery temperature.** BatteryManager `EXTRA_TEMPERATURE` (tenths of °C), no permission. [K]

**Shizuku/ADB.** BatStats reports thermal zones through Shizuku. [S] https://alternativeto.net/software/batstats/about

**Verdict.** Thermal status and headroom: feasible, no root, API 30 and above for headroom. Per-zone temperatures: not feasible without Shizuku.

## B9. Live value in status bar, notification, Quick Settings tile, overlay

- **Notification (ongoing).** Feasible on all versions. Update frequency is throttled by the system [K]. Keep updates at a few per minute.
- **Android 16 Live Updates (status-bar chip).** Progress-style notification, ongoing, short summary for the chip, and `POST_PROMOTED_NOTIFICATIONS`. Check with `NotificationManager.canPostPromotedNotifications()`. API 36 only. [S] https://proandroiddev.com/live-updates-in-android-16-exploring-the-next-evolution-of-notifications-1a5cf5de2068 and [S] https://www.androidauthority.com/android-16-qpr1-live-updates-3573399 . Chip size limits are unverified (one secondary source says 96dp). Check the official docs: https://developer.android.com/about/versions/16/features/progress-centric-notifications
- **Quick Settings tile.** `TileService`, bound while the tile is visible (`onStartListening` and `onStopListening`). Updates only while listening. No live timer. Label and subtitle are the only text. [S] https://developer.android.com/reference/android/app/StatusBarManager . Subtitle API level [K].
- **Overlay.** `SYSTEM_ALERT_WINDOW` (API 23 on) is granted in Settings, not at runtime. On API 26+ use `TYPE_APPLICATION_OVERLAY`. [S] https://blog.checkpoint.com/research/android-permission-security-flaw/ (2017 background). Play restricts it; see B12.

**Verdict.** Notification and Quick Settings: feasible, no root. Live status-bar chip: Android 16 only. Overlay: feasible but high policy risk; avoid.

## B10. Home-screen widgets (Glance)

- Glance `GlanceAppWidget`. `updatePeriodMillis` can refresh up to once every 30 minutes. For faster refresh use WorkManager (15-minute minimum per secondary source). Event-driven updates work on user action. [V the 30-minute ceiling] https://developer.android.com/develop/ui/compose/glance/glance-app-widget.md.txt
- Glance `provideGlance` runs as a WorkManager worker with a time limit before content is provided. [S] (search summary of the Glance reference)
- Widgets cannot be live. Show "as of HH:MM".

**Verdict.** Feasible, no root. Latency is 15 to 30 minutes or event-driven.

## B11. Wake-ups and wakelocks per app

- **Platform.** BatteryStats (system or shell only). Per-app wakelock totals appear in `dumpsys batterystats`. [V] Google profiling guide (search-surfaced). Full wakelock timeline needs `--enable full-wake-history`, which overflows in hours. [S] https://en.androidayuda.com/access-more-detailed-battery-statistics-thanks-google/
- **Without root.** Not feasible for other apps. `dumpsys batterystats` needs shell rights.
- **Shizuku.** BatStats enhanced mode reports wakelocks, alarms and jobs. [S] https://alternativeto.net/software/batstats/about
- **Kernel wakeup sources.** Need root (Battery Historian docs). [S]

**Verdict.** Not feasible without root or Shizuku. Offer it as an optional Shizuku module.

## B12. Play Store policy risks

| Item | What we found | Risk for Activity+ | Source |
|---|---|---|---|
| PACKAGE_USAGE_STATS | Special-access permission granted in Settings. Play rules for it: [GAP]. Not found on the sensitive-permissions page I fetched. | Medium. Needs a clear core-function justification and a permission declaration if Play requires one. | [V] https://developer.android.com/reference/android/app/usage/NetworkStatsManager ; policy page checked: https://support.google.com/googleplay/android-developer/answer/9888170 |
| QUERY_ALL_PACKAGES | Restricted. "You may not use QUERY_ALL_PACKAGES if your app can operate with a more targeted scoped package visibility declaration." Must be declared in Play Console. Data may never be sold or shared for analytics or ads. | High. Avoid unless we need the full app list. Use `<queries>` for known packages and the package names from UsageStats instead. | [V] https://support.google.com/googleplay/android-developer/answer/9888170 ; [S] https://support.google.com/googleplay/android-developer/answer/10158779 |
| FGS type specialUse | Requires a manifest property and a Play Console declaration with a use description, impact, and demo video. Not a fallback to rely on. | Medium. Avoid. Use WorkManager for history sampling. | [S] https://developer.android.com/about/versions/15/changes/foreground-service-types (page fetched; specialUse section not present in the fetched text) ; [S] search summary |
| Android 15 time limits | The fetched page lists a 6-hours-in-24 limit for mediaProcessing only. Some secondary sources say dataSync too. [GAP] | Low for us if we avoid dataSync. | [V] mediaProcessing; dataSync [GAP] |
| SYSTEM_ALERT_WINDOW | Play restricts it and directs users to system settings. Exact current rules [GAP]. | High. Avoid the overlay. | [V] https://support.google.com/googleplay/android-developer/answer/9888170 (policy text: "Direct users to the system settings page for approval of special permissions (for example, SYSTEM_ALERT_WINDOW)") |
| INTERNET | Not needed for the features in this document. | Low. Keep it off. | [K] |

Recommendation: keep QUERY_ALL_PACKAGES, SYSTEM_ALERT_WINDOW and specialUse out of the first release. Keep PACKAGE_USAGE_STATS as the only special permission, and justify it on the core "which app is responsible" feature. Confirm the Play rules directly in Play Console before submitting.

---

# Feature feasibility summary

| # | Feature | No-root Android 13 to 16 | Shizuku/ADB needed for | Key API / permission |
|---|---|---|---|---|
| 1 | Per-app drain | Estimate only | Real per-app | BatteryManager + UsageStats + NetworkStats |
| 2 | Per-app CPU/memory (others) | No | All | Own process only: Debug, Process |
| 3 | Per-app data fg/bg | Yes | None | NetworkStatsManager + PACKAGE_USAGE_STATS |
| 4 | Per-app storage incl. cache | Yes | None | StorageStatsManager + PACKAGE_USAGE_STATS |
| 5 | Screen time, launches, FGS time | Yes (retention to measure) | None | UsageStatsManager + PACKAGE_USAGE_STATS |
| 6 | Battery W, speed, health, cycles | Yes (health estimated, cycles API 34) | None | BatteryManager |
| 7 | CPU clock per core | Device-dependent [GAP] | None (if readable) | sysfs cpufreq |
| 7b | Overall CPU usage | No | All | /proc/stat blocked |
| 8 | Thermal status / headroom | Yes (headroom API 30+) | Thermal zones | PowerManager |
| 9 | Live value in UI | Notification yes; status chip Android 16 only; overlay avoid | None | Notifications, TileService |
| 10 | Glance widget | Yes, 30-min ceiling | None | Glance, WorkManager |
| 11 | Wakelocks / wakeups | No | Yes | dumpsys batterystats |
| 12 | Play risk | QUERY_ALL_PACKAGES, overlay, specialUse high | n/a | see B12 |

---

# Open questions to test on a device

1. UsageStats retention on Android 13, 14, 15 and 16 (query 7, 14, 30 days back). Decides the 30-day plan.
2. Whether `scaling_cur_freq` is readable by an untrusted app on the device matrix (Pixel, Samsung, a Chinese OEM).
3. Whether `EXTRA_CYCLE_COUNT` is present on an API 34+ device, and its official "Added in" value.
4. Sign convention of `BATTERY_PROPERTY_CURRENT_NOW` on the device matrix.
5. PACKAGE_USAGE_STATS and QUERY_ALL_PACKAGES current Play declaration rules, read in Play Console.
6. FOREGROUND_SERVICE_START/STOP event availability in `queryEvents` per Android version.
7. Whether a Live Update chip appears for a sampled ongoing notification on Android 16 without a foreground service.

# Sources

Official
- https://developer.android.com/reference/android/app/usage/UsageStatsManager
- https://developer.android.com/reference/android/app/usage/NetworkStatsManager
- https://developer.android.com/reference/android/app/usage/StorageStatsManager
- https://developer.android.com/reference/android/os/BatteryManager
- https://developer.android.com/reference/android/os/PowerManager
- https://developer.android.com/games/optimize/adpf/thermal
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/15/changes/foreground-service-types
- https://developer.android.com/develop/ui/compose/glance/glance-app-widget.md.txt
- https://developer.android.com/topic/performance/power/setup-battery-historian
- https://support.google.com/googleplay/android-developer/answer/9888170
- https://support.google.com/googleplay/android-developer/answer/10158779

AOSP
- https://android.googlesource.com/platform/system/core/+/c39ba5a (hidepid=2)
- https://android.googlesource.com/platform/external/sepolicy/+/4e2d224 (sysfs_devices_system_cpu history)

Apps and forums (secondary)
- https://apps.apple.com/app/id6737747779 (AccuBattery listing)
- https://www.apkmirror.com/apk/gsam-labs/gsam-battery-monitor
- https://alternativeto.net/software/batstats/about
- https://www.apkmirror.com/apk/flar2/devcheck-system-info/devcheck-system-info-4-34-release/
- https://www.xda-developers.com/devcheck-hardware-system-information-app/
- https://www.xda-developers.com/stop-wakelocks-android-without-root/
- https://f-droid.org/eu/packages/me.biplobsd.rsm
- https://forum.glasswire.com/t/using-glasswire-on-non-root-android-phones/14104
- https://forum.glasswire.com/t/glasswire-android-suggestion/4209
- https://www.trustpilot.com/review/glasswire.com
- https://forum.earlybird.club/threads/ampere-app-strange-readings.1348652/
- https://forums.androidcentral.com/threads/accubattery-health.950390
- https://forums.androidcentral.com/threads/ampere-app.628512/
- https://r2.community.samsung.com/t5/Galaxy-A/Accu-battery/td-p/5646186
- https://r1.community.samsung.com/t5/support/issue-with-app-screen-time-in-digital-wellbeing/m-p/20616007/highlight/true
- https://r2.community.samsung.com/t5/Tech-Talk/Digital-wellbeing-data-inaccurate/m-p/8474850
- https://r2.community.samsung.com/t5/Galaxy-Store-Apps-more/Error-in-Screen-time-count/td-p/12556973
- https://r2.community.samsung.com/t5/Galaxy-M/quot-device-care-quot-not-working-on-m51/m-p/7129905/highlight/true
- https://r1.community.samsung.com/t5/support/save-your-phone-s-battery-by-managing-background-apps/td-p/26171485
- https://redlib.hackliberty.org/r/opensource/comments/1kknasz/foss_digital_wellbeing_app (Reddit mirror)
- https://feddit.it/comment/9865118 (Reddit-style thread)
- https://red.applefritter.com/r/GalaxyNote20/comments/1oadu6e/this_is_odd (Reddit mirror)
- https://proandroiddev.com/live-updates-in-android-16-exploring-the-next-evolution-of-notifications-1a5cf5de2068
- https://docs.customer.io/integrations/sdk/android/live-notifications/set-up.md
- https://www.androidauthority.com/android-16-qpr1-live-updates-3573399
- https://androxus.com/blogs/why-battery-apps-show-different-readings
- https://forum.fairphone.com/t/fp3-thermal-hal-non-functional-missing-sepolicy-incorrect-trip-point-index/133186
- https://gitlab.cs.fau.de/Matombo/AndroidSystemSEPolicy (AOSP SELinux commit mirror, secondary)
- https://www.volcengine.com/article/663485 (translated Q&A, secondary)
- https://en.androidayuda.com/betterbatterystats-the-best-application-to-optimize-the-battery/ (secondary)

Note: the AccuBattery listing cited above is the iOS App Store page (apps.apple.com/app/id6737747779). Its developer description was repeated on Android APK mirrors, so the wording is used as a secondary source for the Android app's method only.
