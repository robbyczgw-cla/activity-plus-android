# `pm grant` of development permissions across reboot (Android 11–16)

**Persists on user builds: depends — because the grant is a normal on-disk permission bit and is restored on boot once that write has landed, and it is dropped if the device reboots before the async flush (about 1–2 seconds), if the package is removed or stops requesting the permission, or if something explicitly revokes it. AOSP does not revoke it on boot, and it does not revoke it when developer options or ADB are turned off. The same persistence path is used for user and userdebug; only the flush race was measured here, on a userdebug Android 15 emulator.**

Research date: 2026-10-09. Source reading was stopped before `AccessPersistence.kt` and the Android 11 branch were opened line by line. Anything not checked in this pass is marked **[unverified]**. Measurements from our Android 15 Google APIs x86_64 emulator (userdebug) are marked **[tested]**.

## What was tested

**[tested]** On that Android 15 emulator:

- `adb shell pm grant` of `android.permission.DUMP`, `android.permission.PACKAGE_USAGE_STATS`, and `android.permission.INTERACT_ACROSS_USERS`, followed by a reboot within about a second, left the grants gone. This happened with developer options on and with them off. That was the earlier result.
- The same grants, followed by a 15-second wait, then a reboot, were still present.
- A further reboot with `Settings.Global.development_settings_enabled=0` also kept the grants.
- With those grants, an app can run `dumpsys batterystats --checkin`.
- `dumpsys meminfo` and `dumpsys cpuinfo` from the app fail with `Can't find service`. That failure is SELinux (the app UID cannot find those services), not a missing `DUMP` grant.

The on-disk file those grants go into on this image is `access.abx`, written about 1–2 seconds after `pm grant` **[tested]**.

## Verdict by version

| Case | After the write has flushed | If you reboot in the first 1–2 seconds |
| --- | --- | --- |
| Android 15–16, AOSP / emulator / the user-build code path | Kept. Boot reconciliation copies the previous development grant back. | Lost. The in-memory grant never reached `access.abx`. |
| Android 11–14, AOSP legacy `PermissionManagerServiceImpl` | Loaded back from `runtime-permissions.xml` on an ordinary boot, according to BetterBatteryStats' own docs and older device reports. | Same flush race. The legacy writer debounces for up to 2 seconds. |
| Developer options or `adb_enabled` turned off | Kept, on the Android 15 emulator **[tested]** and in the AOSP policy that was read. | Unrelated to the setting. |
| Xiaomi / Samsung / OnePlus | The `pm grant` command is often blocked until an extra OEM toggle is on. A 2024–2026 reboot wipe of an already stored `DUMP` grant was not found. | Same flush race, plus whatever the OEM added **[unverified]**. |

## 1. AOSP: protection level, storage, restore

### Protection flags (read from `frameworks/base/core/res/AndroidManifest.xml`)

Tags checked: `android-14.0.0_r1`, `android-14.0.0_r75`, `android-15.0.0_r1`, `android-16.0.0_r1`, `android-16.0.0_r3`, and `main`.

| Permission | protectionLevel on 14, 15, and 16 |
| --- | --- |
| `android.permission.DUMP` | `signature\|privileged\|development` |
| `android.permission.BATTERY_STATS` | `signature\|privileged\|development` |
| `android.permission.PACKAGE_USAGE_STATS` | `signature\|privileged\|development\|appop\|retailDemo` |
| `android.permission.READ_LOGS` | `signature\|privileged\|development` (read on 15 and main; same block on 14 was not printed) |
| `android.permission.WRITE_SECURE_SETTINGS` | `signature\|privileged\|development\|role\|installer` (read on 15) |
| `android.permission.GET_APP_OPS_STATS` | `signature\|privileged\|development` (read on 15) |

`DUMP` is still `signature|privileged|development` on Android 14, 15, and 16. It is not a dangerous runtime permission. `isDevelopment()` in `Permission.java` is "signature base, plus `PROTECTION_FLAG_DEVELOPMENT` (0x20)".

`INTERACT_ACROSS_USERS` was in the emulator grant set and survived **[tested]**. Its manifest line was not re-read in this pass **[unverified]**.

An older AOSP change (`8f596907a5241badad821a6d3490eb2cd7dd23c5`) renamed `signature|system|development` to `signature|privileged|development` for `DUMP`, `READ_LOGS`, and `WRITE_SECURE_SETTINGS`. The `development` flag itself is older than Android 11.

`frameworks/base/core/java/android/permission/Permissions.md` (current master) says that adding `development` lets any user grant the permission permanently with `pm grant`, and that this is discouraged because it removes the signature/privileged guarantee.

### Which code runs

`PermissionManager.USE_ACCESS_CHECKING_SERVICE` is `SdkLevel.isAtLeastV()` on both `android-15.0.0_r1` and `android-16.0.0_r1`.

- Android 15 and 16 use `AccessCheckingService` and `AppIdPermissionPolicy`.
- Android 14 `PermissionManager.java` (`android-14.0.0_r1`) does not have that flag. Android 11–14 use `PermissionManagerServiceImpl`.
- The legacy class is still in the Android 15 tree; it is not selected when the flag is true. The legacy behavior below is cited from that Android 15 copy, not from a line-by-line diff against the Android 11 branch.

Build type is not part of this choice. `user`, `userdebug`, and `eng` at the same SDK level use the same permission backend.

### Android 15–16: how `pm grant` is remembered

File read: `frameworks/base/services/permission/java/com/android/server/permission/access/permission/AppIdPermissionPolicy.kt` on `android-15.0.0_r1`.

`evaluatePermissionState` runs on boot and on package changes. For a signature permission such as `DUMP`:

1. It computes `PROTECTION_GRANTED` only when signature or another static protection flag actually applies (privileged allowlist, installer, preinstalled, and so on). A normal app does not get `DUMP` that way.
2. It then does:

```kotlin
// privileged flag must not wipe dynamic development/role grants
if (permission.isDevelopment) {
    newFlags = newFlags or (oldFlags and PermissionFlags.RUNTIME_GRANTED)
}
```

So the `pm grant` is stored as the internal `RUNTIME_GRANTED` bit, including for permissions that are not dangerous runtime permissions. On the next evaluation the bit is copied from the previously loaded state. `PACKAGE_USAGE_STATS` also keeps `ROLE` and `USER_SET` across that evaluation because it has the `appop` flag.

`onSystemReady()` in that class does not revoke development permissions. It only aborts system start if a privileged permission allowlist is violated.

`resetRuntimePermissions` (called from `onPackageUninstalled`, and from `pm reset-permissions`) returns immediately unless `permission.isRuntime`. `DUMP` and `BATTERY_STATS` are signature permissions, so this reset does not clear them. Uninstall still drops the app id's permission state via `onPackageRemoved` / `trimPermissionStates`.

The policy comment states the behavior this replaced: the old implementation would grant an un-allowlisted privileged permission through the development or role path, then revoke it on the next reconciliation. The new code keeps the dynamic flag on purpose. The Android 16 method body was not re-read **[unverified]**; Android 16 does select this service (`isAtLeastV()`).

Serialization read in `AppIdPermissionPersistence.kt`: each app id's permission flags are written as binary XML. One-time runtime grants are forced off in the serialized flags. `RUNTIME_GRANTED` on a development permission is not one-time, so it is written. The writer asks for `WriteMode.ASYNCHRONOUS`. The delay constant inside `AccessPersistence.kt` was not opened **[unverified]**; the emulator measured 1–2 seconds before `access.abx` held the grant **[tested]**.

### Android 11–14: legacy restore

`PermissionManagerServiceImpl.grantRuntimePermission` (Android 15 tree; this is the pre-V implementation):

- A permission is changeable when it `isRuntime()` or `isDevelopment()`, or when it is a role permission and the caller may manage roles. Anything else throws `SecurityException` ("not a changeable permission type").
- For `isDevelopment()` or `isRole()` it calls `uidState.grantPermission`. The comment says these are not normal runtime permissions, historically applied to all users, and that making the state per-user is an intentional break with undocumented behavior.
- The callback for a non-runtime grant is `onInstallPermissionGranted`, which schedules a settings write. There is no branch on `DEVELOPMENT_SETTINGS_ENABLED` or `ADB_ENABLED` in the grant or revoke methods that were read.

`restorePermissionState` keeps a signature permission that was already granted when `bp.isDevelopment()` (or role, or a privileged "was granted" allowlist) is true and `origState.isPermissionGranted(permName)`. Otherwise it `revokePermission`s it. A commented block in `shouldGrantPermissionByProtectionFlags` records the same rule and says the check was moved under the permission lock inside `restorePermissionState`:

```java
//if (!allowed && bp.isDevelopment()) {
//    // For development permissions, a development permission
//    // is granted only if it was already granted.
//}
```

The privileged-allowlist conjunct sits outside that development exception. Combined with the Android 15 comment quoted above, a full reconciliation on Android 11–14 can drop `DUMP` for a normal app even though `pm grant` succeeded a moment earlier. This pass did not trace every caller, so it is **[unverified]** whether a quiet reboot always enters that branch. Field reports below say an ordinary reboot usually keeps the grant, and an app update sometimes does not. That split matches "boot loads the XML; a later reconcile can revoke."

Legacy debounce, from `Settings.RuntimePermissionPersistence` in the historical `frameworks/base` `Settings.java`: `WRITE_PERMISSIONS_DELAY_MILLIS = 200` and `MAX_WRITE_PERMISSIONS_DELAY_MILLIS = 2000`. The file is `/data/system/users/<userId>/runtime-permissions.xml`. That class is the Android 11–14 writer, not the Android 15 `access.abx` writer.

Flags on non-runtime permissions were dropped across package update (bug 283006437). The May 2023 fix in `restorePermissionState` copies the old flags forward (`updatePermissionFlags(..., MASK_PERMISSION_FLAGS_ALL, flags)`). That fix is about flags, not about the grant boolean.

### What actually revokes a development grant

From the code that was read, plus the emulator:

| Event | Effect |
| --- | --- |
| Reboot after the async write | Grant restored (Android 15 policy; Android 15 emulator **[tested]**). |
| Reboot before the write (about 1–2 s) | Grant gone **[tested]**. |
| `Settings.Global.development_settings_enabled=0`, including across reboot | No revoke in the policy that was read. Survived **[tested]**. |
| Turning ADB off (`ADB_ENABLED`) | No revoke in the policy that was read. Not separately re-tested after a flushed grant **[unverified]**. |
| `pm revoke` | Revoked. Development permissions are an allowed target of `revokeRuntimePermission`. |
| App no longer requests the permission | `trimPermissionStates` sets the flags to 0. |
| Uninstall, or the app id going away | State removed. Clear-data (`pm clear`) was not checked **[unverified]**. |
| Package update, Android 15+ | `RUNTIME_GRANTED` is copied if the app still requests the permission. |
| Package update / reconcile, Android 11–14 | Can revoke an un-allowlisted `privileged\|development` grant (policy comment). Separate from the 2023 flag-loss bug. |
| Android 11+ auto-revoke of unused apps | Applies to dangerous runtime permissions. `resetRuntimePermissions` ignores non-runtime permissions, so it does not clear `DUMP`. |
| Factory reset / wiped `/data` | Store is gone. |

No hook was found in `AppIdPermissionPolicy`, `PermissionManagerServiceImpl` grant/revoke/`restorePermissionState`, or `DevelopmentSettingsEnabler` that walks packages and clears `PROTECTION_FLAG_DEVELOPMENT` grants on boot or when developer options change. Absence from those files is not a proof that every OEM jar is clean **[unverified]** for Samsung, Xiaomi, and OnePlus system images.

## 2. Emulator (userdebug, writable `/data`) versus user builds

The Android 15 emulator result is the flush race, not a userdebug policy.

- `USE_ACCESS_CHECKING_SERVICE` depends on SDK level, not on `ro.build.type`.
- `DevelopmentSettingsEnabler.isDevelopmentSettingsEnabled` defaults the global to 1 only when `Build.TYPE` equals `eng`. userdebug is not eng, so the default is 0 until something turns developer options on. That default does not feed the permission policy.
- Writable `/data` is required for `access.abx` or `runtime-permissions.xml` to stick. A user build has that same writable data partition. A read-only data image would fail the write; a normal user phone is not in that state.
- `pm grant` from the shell works on user images because the shell is allowed to call `grantRuntimePermission` for changeable permissions. That is the path BetterBatteryStats and GSam document for unrooted phones. This pass did not re-read the shell priv-app allowlist **[unverified]**.
- Emulator Quick Boot can save a snapshot from before the async write. An immediate `adb reboot` on this image was enough to lose the grant; waiting 15 seconds was enough to keep it **[tested]**. Treat a sub-second reboot as a bad test of persistence.

No source was found that treats userdebug as "development grants are RAM-only."

## 3. Field reports (weighted toward 2024–2026)

These describe either a one-time permanent grant, a grant that never succeeds, or a live ADB session dying. They are not the same event.

- BetterBatteryStats non-root guide (https://better.asksven.io/betterbatterystats/non-root/): `pm grant` of `BATTERY_STATS`, `DUMP`, and `PACKAGE_USAGE_STATS` "will survive reboots." One grant, not a boot script. Hidden-API settings (`hidden_api_policy`) are a separate step and are also settings, not permission bits.
- BetterBatteryStats Reloaded install notes (https://github.com/asksven/bbs_reloaded-releases, v1.0.0-beta7, Android 14+): still a one-time `adb shell pm grant` of `BATTERY_STATS`, `DUMP`, `PACKAGE_USAGE_STATS`, and `INTERACT_ACROSS_USERS`. No "re-grant after every reboot."
- AOSP `Permissions.md`: granting via `development` is permanent.
- XDA, 2017, Galaxy S8+ stock, unrooted (https://xdaforums.com/t/permissions-granted-to-gsam-pro-and-wakelock-detector-keep-resetting-on-reboot.3595681/): GSam and Wakelock Detector grants "randomly" missing after some reboots, not after every reboot. Too old to treat as current One UI, and it does not match the Android 15 emulator once the write was given time.
- XDA, 2016, GSam on Nougat: one report that the grant held across reboots; another that it had to be repeated per app version. Fits "reboot loads state; update reconciles."
- XDA, 2015, CyanogenMod 12.1: `pm grant` of `BATTERY_STATS` and `DUMP` "preserved across ROM upgrades" that replace `/system`, because the state lives under `/data`.
- GitHub asksven/BetterBatteryStats#917 (2023, /e/ OS, Android 12): `pm grant` appeared to succeed and the app still reported the permission missing. That is a grant-not-effective report, not a reboot report. Common causes of that shape are the appop for `PACKAGE_USAGE_STATS` (the permission bit and `OP_GET_USAGE_STATS` are different checks) and the process still holding an old identity until it is restarted.
- HyperOS 2 / Xiaomi 15, 2024–2025 (https://github.com/orgs/gkd-kit/discussions/767): `pm grant` of `WRITE_SECURE_SETTINGS` throws `SecurityException` until USB debugging, USB install, and "USB debugging (Security settings)" are all on. That is a refusal to grant, not a reboot wipe.
- Priv Kit, 2026 (https://priv-kit.pages.dev/guide/activation): with "USB debugging (Security settings)" off, the shell process is denied `GRANT_RUNTIME_PERMISSIONS`, `WRITE_SECURE_SETTINGS`, and a list of other permissions. The page says those are denied permissions of the privileged shell process, not previously granted app permissions being stripped.
- OnePlus / Oppo, 2026 (https://droidwin.com/disable-permission-monitoring-missing-how-to-fix/): "Disable permission monitoring" was renamed to "Disable system optimization." Without it, `pm grant` and Shizuku-style shell grants fail. Again a grant-time gate.
- AirDroid help article (updated 2024): their non-root activation "becomes invalid" after reboot, and on Android 8+ after USB debugging or developer options are turned off. That feature is a live ADB-started session. It is not evidence that `access.abx` / `runtime-permissions.xml` drops `DUMP`.

No 2024–2026 Pixel, One UI, HyperOS, or OxygenOS report was found that says a `DUMP` or `BATTERY_STATS` grant, confirmed present in `dumpsys package` after the write, is then absent after every reboot while developer options stay on. The search covered GSam, BetterBatteryStats, Battery Guru, DevCheck, Shizuku, XDA, Reddit, and GitHub. Battery Guru and DevCheck did not turn up a reboot-loss report in that search **[unverified]** that they never lose it; they just were not in the hits.

## 4. Turning developer options off

The Settings switch calls `DevelopmentSettingsEnabler.setDevelopmentSettingsEnabled`, which writes `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` to 0 or 1 and sends a local broadcast (`DEVELOPMENT_SETTINGS_CHANGED_ACTION`). The reader ORs in "default on" only for `eng`.

`DevelopmentSettingsDashboardFragment.disableDeveloperOptions` then tells each developer-option controller `onDeveloperOptionsSwitchDisabled`. Those controllers reset their own settings (ADB enablement, animation scales, the ADB authorization timeout, and similar). `DisableDevSettingsDialogFragment` can reboot the phone when a specific hardware offload (Bluetooth HW offload) was on. None of that is a package-permission walk.

`adb_enabled` is a separate global. Turning the developer-options master switch off does turn USB debugging off, because the ADB controller handles that switch. The permission policy that was read does not subscribe to either setting.

**[tested]** Grants that had already been flushed survived a reboot with `development_settings_enabled=0`.

What the master switch does change is the ability to grant again: USB debugging is off, and on Xiaomi-class builds the extra "USB debugging (Security settings)" switch is what lets the shell hold `GRANT_RUNTIME_PERMISSIONS`. Off means the next `pm grant` fails. It does not, on the code and the emulator test, mean the previous grant is deleted.

## 5. What apps do when the grant is missing

They do not, on AOSP, need a boot receiver to re-grant `DUMP` every boot. They need a way to run `pm grant` once, and they need that write to finish.

- BetterBatteryStats and GSam: one `adb shell pm grant` from a computer. The app then checks the permission and, if it is missing, shows the same ADB instructions again. A reinstall or an update that drops the request is the usual reason to repeat it.
- Shizuku (and rish / similar wireless-debugging shells): the Shizuku *process* dies on reboot unless the device is rooted and starts it at boot. That is a dead binder, not a revoked `DUMP` bit. After the user starts Shizuku again (wireless debugging or root), the app can run `pm grant` locally with no computer. Useful when the bit really is missing, and it is the same grant that then persists.
- `WRITE_SECURE_SETTINGS` is the same class of permission (`development`, plus `role` and `installer` on Android 15). Apps that only need to edit secure settings often grant that instead of, or in addition to, `DUMP`. It follows the same store and the same flush rule.
- `PACKAGE_USAGE_STATS` can also be turned on by the user under Settings → Special app access → Usage access, which sets the appop. `pm grant` sets the permission bit; callers that use `AppOpsManager.checkOp(OP_GET_USAGE_STATS)` still depend on the appop. Those two were not measured separately on the emulator **[unverified]**.
- A boot script that re-grants through Shizuku is a workaround for OEMs or for the legacy reconcile, not a requirement of the Android 15 policy. It also loses to the same 1–2 second window if the script reboots immediately afterward.

`DUMP` is not enough for every `dumpsys` target **[tested]**. `batterystats --checkin` works with the grant. `meminfo` and `cpuinfo` fail with `Can't find service` because SELinux does not let an untrusted app UID find those services. Shell can. Re-granting `DUMP` does not fix that.

## Sources

- `frameworks/base/core/res/AndroidManifest.xml` on tags `android-14.0.0_r1`, `android-14.0.0_r75`, `android-15.0.0_r1`, `android-16.0.0_r1`, `android-16.0.0_r3`, and branch `main`.
- `frameworks/base/core/java/android/permission/PermissionManager.java` (`USE_ACCESS_CHECKING_SERVICE = SdkLevel.isAtLeastV()`), Android 15 and 16 tags. Absent on `android-14.0.0_r1`.
- `frameworks/base/services/permission/java/com/android/server/permission/access/permission/AppIdPermissionPolicy.kt`, `android-15.0.0_r1`: `evaluatePermissionState`, `resetRuntimePermissions`, `trimPermissionStates`, `onSystemReady`.
- `AppIdPermissionPersistence.kt` (flag serialization, asynchronous write mode) as linked from the Android 15 policy tree.
- `PermissionManagerServiceImpl.java` on `android-15.0.0_r1`: `grantRuntimePermission`, `revokeRuntimePermissionInternal`, `restorePermissionState`, `shouldGrantPermissionByProtectionFlags`. Used when the access-checking flag is false.
- `Permission.java` `isDevelopment()`; `Permissions.md` "permanently via adb."
- `DevelopmentSettingsEnabler.java` and `DevelopmentSettingsDashboardFragment.java` (master switch writes the global and resets developer-option controllers).
- Historical `Settings.RuntimePermissionPersistence`: 200 ms / 2000 ms debounce, `runtime-permissions.xml`.
- Commit `8f596907a5241badad821a6d3490eb2cd7dd23c5`: `signature|system|development` renamed to `signature|privileged|development`.
- Bug 283006437 / `restorePermissionState` flag-preserve fix (2023) for non-runtime permission flags on package update.
- Emulator notes in the section "What was tested" (2026-10-09).
- https://better.asksven.io/betterbatterystats/non-root/
- https://github.com/asksven/bbs_reloaded-releases/releases/tag/v1.0.0-beta7
- https://xdaforums.com/t/permissions-granted-to-gsam-pro-and-wakelock-detector-keep-resetting-on-reboot.3595681/
- https://github.com/asksven/BetterBatteryStats/issues/917
- https://github.com/orgs/gkd-kit/discussions/767
- https://priv-kit.pages.dev/guide/activation
- https://droidwin.com/disable-permission-monitoring-missing-how-to-fix/
