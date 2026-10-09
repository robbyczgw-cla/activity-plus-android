# Setting up Activity+ for Android

[Deutsch](SETUP.de.md)

Everything except the pro mode works right after the first start. The pro mode is optional.

## 1. Install

1. Download the APK from [activityplus.xyz](https://activityplus.xyz/#android) or the
   [releases](https://github.com/robbyczgw-cla/activity-plus-android/releases) and open it.
2. Android asks to allow installs from your browser or file manager. Allow it, install, then turn that
   permission off again.
3. Or let [Obtainium](https://github.com/ImranR98/Obtainium) install and update it: [add Activity+ to Obtainium](https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/robbyczgw-cla/activity-plus-android), or add `https://github.com/robbyczgw-cla/activity-plus-android` by hand.

Updates install over the old version as long as they come from the same place: all releases are signed
with the same key (certificate SHA-256 `07:38:FA:A1:25:50:6A:DD:65:72:8D:08:7D:DB:AE:79:CB:A8:C2:AD:87:B3:96:C0:CC:8D:15:03:32:51:C3:C1`).

## 2. First start

1. **Language**: the first line on the welcome screen (Android 13 and later). The phone's language is the default.
2. **Usage access**: tap *Open usage access* and switch Activity+ on. Without it Activity+ cannot see which
   app is on screen or how much data and storage each app uses.
3. **Notifications**: for the live value in the status bar and for alerts.
4. Tap *Start*.

## 3. Keep it measuring in the background

Several phone makers stop apps in the background on their own. Then the status bar value disappears, the
history gets gaps and widgets stop updating. Allow Activity+ once (menu names differ by version):

| Maker | Setting |
|---|---|
| vivo (OriginOS, Funtouch) | Battery → background power consumption → Activity+ → allow; turn on autostart; lock it in recent apps |
| Xiaomi (HyperOS, MIUI) | App info → Battery saver → No restrictions; Autostart on |
| Samsung (One UI) | App info → Battery → Unrestricted; remove it from "Sleeping apps" |
| OnePlus / Oppo / Realme | App info → Battery usage → allow background activity and auto launch |
| Pixel and others | App info → Battery → Unrestricted |

If the value does not show in the status bar, check that the phone shows notification icons there (some
makers show only a count by default).

## 4. Pro mode (optional)

Android does not let apps see other apps' background battery use, memory or CPU. The pro mode reads
Android's own reports once, when you tap *Measure now* (Apps or Battery → *Measure precisely*). It runs only
three read-only reports (`dumpsys batterystats`, `meminfo`, `cpuinfo`) and refuses everything else.
Activity+ has no internet permission, so nothing it reads can leave the phone.

There are two ways in. You can use both.

| | Computer grant | Shizuku |
|---|---|---|
| Battery per app since the last charge, background included | yes | yes |
| Memory and CPU per app | no (Android keeps those reports closed to apps) | yes |
| Developer options afterwards | off, permanently | needed while you measure |
| After a phone restart | stays | start Shizuku again |

### Computer grant (battery per app, permanent)

1. Settings → About phone → tap the build number (vivo: software version) seven times to turn on
   developer options. Turn on **USB debugging**.
2. Connect the phone to a computer with [adb](https://developer.android.com/tools/releases/platform-tools)
   and accept the prompt on the phone.
3. Run (Activity+ shows this line with a copy button):
   ```
   adb shell "pm grant xyz.activityplus.android android.permission.DUMP && pm grant xyz.activityplus.android android.permission.PACKAGE_USAGE_STATS && pm grant xyz.activityplus.android android.permission.INTERACT_ACROSS_USERS"
   ```
4. **Wait about ten seconds**: Android saves the grant a moment later, and a restart within that time loses it.
5. Turn USB debugging and developer options off again.
6. In Activity+, *Measure precisely* now says **Ready (computer grant, battery only)**.

No cable? *Wireless debugging* (Android 11+) works the same way: pair once with `adb pair <ip>:<port> <code>`,
then `adb connect <ip>:<port>` and run the line above.

Some makers block `pm grant` until an extra switch is on, during setup only:
Xiaomi: *USB debugging (Security settings)*; OnePlus/Oppo: *Disable permission monitoring* (newer: *Disable system optimization*).

### Shizuku (battery, memory and CPU per app)

1. Install [Shizuku](https://shizuku.rikka.app/) and start it through *Wireless debugging* as its app explains.
2. In Activity+, *Measure precisely* → *Allow in Shizuku* → allow.
3. Tap *Measure now*. You can turn developer options off afterwards; the result stays in Activity+.

Shizuku stops when the phone restarts. Start it again only when you want to measure.

## 5. Banking apps and security

- Banking and ID apps often refuse to run while developer options are on. With the computer grant you turn
  them off right after step 5 and keep the pro battery figures. With Shizuku, turn them on only to measure.
- Activity+ uses no accessibility service, no overlay and no root, the usual triggers of those checks.
- After pairing a computer for wireless debugging, remove it under *Wireless debugging → Paired devices* and
  tap *Revoke USB debugging authorizations* in developer options.
- The grant lets Activity+ read system reports, which in principle include more than battery figures.
  Activity+ reads only the battery report, has no internet permission, and the source is in this repository.
  Uninstalling Activity+ removes the grant.

## 6. When the grant is gone

It survives restarts and turning developer options off. It is removed when Activity+ is uninstalled or
reinstalled, on a factory reset, and on Android 11–14 sometimes when Activity+ updates. *Measure precisely*
then says *Not set up*; repeat section 4. Everything else keeps working without it.
