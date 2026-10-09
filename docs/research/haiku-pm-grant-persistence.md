# Does `pm grant <pkg> android.permission.DUMP` survive a reboot?

## Verdict

**Per AOSP source, a `pm grant` of a `signature|privileged|development` permission such as DUMP or BATTERY_STATS is persistent state, not a session state. Boot does not clear it.** It is removed only by an explicit revoke (`pm revoke`, `revokeRuntimePermission`), by removal of the app ID (uninstall of the last package sharing it), or by the package being trimmed from state. `pm reset-permissions` and the storage/media revoke-on-update path do not touch development grants.

This does not explain the Android 15 emulator observation (grant gone after every reboot, dev options on or off). Nothing in the AOSP code I read clears the grant at boot, so the cause is most likely outside the permission code: a write race, a reinstall, a different user or app ID, or a test-harness step. None of these is verified. The GSam/BetterBatteryStats "permanent" claim is consistent with the source but I could not find the docs that make it.

Confidence: high that the grant is persisted by the framework and not cleared at boot (source-based). Low on the root cause of the emulator observation (needs reproduction, see the last section).

## Protection level (verified in source)

| Branch | `android.permission.DUMP` | `android.permission.BATTERY_STATS` |
|---|---|---|
| main (`frameworks/base/core/res/AndroidManifest.xml`, line 5158 / 6854) | `signature\|privileged\|development` | `signature\|privileged\|development` |
| android15-release (same file, line 4934) | `signature\|privileged\|development` | not checked |
| android11-release (same file, line 3014 / 4169) | `signature\|privileged\|development` | `signature\|privileged\|development` |
| android14-release (line 4509) | `signature\|privileged\|development` | not checked |

Because the base protection is `signature`, the permission goes through the signature branch of the grant/reconcile logic. The development flag is what lets `pm grant` set it.

## How `pm grant` reaches the framework (verified in source)

- `PackageManagerShellCommand.runGrantRevokePermission(grant)` calls `mPermissionManager.grantRuntimePermission(...)` (main, around line 2690).
- Android 11 (`PermissionManagerService.java`, `grantRuntimePermissionInternal`): for `bp.isDevelopment()` it calls `permissionsState.grantInstallPermission(bp)`. The comment says "For now they apply to all users." The grant is stored as an install-level permission state.
- Android 14 legacy (`PermissionManagerServiceImpl.java`, `grantRuntimePermissionInternal`, around line 1460): `if (bp.isDevelopment() || bp.isRole())` calls `uidState.grantPermission(bp)`.
- Android 15 and main (`services/permission/.../access/permission/PermissionService.kt`): `grantRuntimePermission` → `setRuntimePermissionGranted(...)`. In that function `permission.isDevelopment -> {}` skips the runtime-only checks. `PermissionFlags.RUNTIME_GRANTED` is documented as "granted via `PackageManager.grantRuntimePermission`" for development, role, and runtime permissions. `PermissionFlags.isPermissionGranted()` returns true when that bit is set.

## Persistence (verified in source, per branch)

- **Android 11**: `Settings.java` writes `pkg.getPermissionsState().getInstallPermissionStates()` through `writePermissionsLPr` (call sites near lines 2845 and 2929). Development grants are install-level state, so they live in `packages.xml`. Runtime (per-user) state is a separate file, `runtime-permissions.xml` (`RUNTIME_PERMISSIONS_FILE_NAME`, Settings.java line 194). That file is what `CLEAR_RUNTIME_PERMISSIONS_ON_UPGRADE` deletes (Settings.java around line 3136). Development grants are not in that file, so that clear does not affect them. This last inference is not separately verified.
- **Android 14 and earlier-flow code**: the legacy `PermissionManagerServiceImpl` path is still used. The persistence path for Android 13/14 was not fetched.
- **Android 15 and main (access-based state)**: state is written by `AccessPersistence.kt` to `access.abx` (`FILE_NAME = "access.abx"`), in `PermissionApex.systemDataDirectory` for system state and `PermissionApex.getUserDataDirectory(userId)` for user state. Writes are asynchronous: `WRITE_DELAY_TIME_MILLIS = 1000L`, `MAX_WRITE_DELAY_MILLIS = 2000L`. I found no shutdown or flush hook in the access package (only `appop/AppOpService.kt` has a `shutdown()`).

## Boot reconcile (verified in source, main)

- `AppIdPermissionPolicy.evaluatePermissionState` is called at boot and on package changes. In the signature branch (`else if (permission.isSignature || permission.isInternal)`), there is an explicit rule: `if (permission.isDevelopment) newFlags = newFlags or (oldFlags and PermissionFlags.RUNTIME_GRANTED)`. So a development grant is carried forward through reconciliation.
- The same file has `isDevelopment` at line 1063 in `android16-release` (grep only; I did not diff the function).
- Android 11 `grantSignaturePermission` (`PermissionManagerService.java` around line 3592): `if (!allowed && bp.isDevelopment()) allowed = origPermissions.hasInstallPermission(perm);`. A development permission is re-granted on rescan only if it was already granted.

## When it is revoked (verified in source)

| Path | Effect on development grant |
|---|---|
| `pm revoke` / `revokeRuntimePermission` → `setRuntimePermissionGranted(isGranted=false)` | Revoked (explicit) |
| App ID removal → `onAppIdRemoved` drops `appIdPermissionFlags[appId]` for all users | Removed (all grants for that app ID) |
| Uninstall → `onPackageUninstalled` → `resetRuntimePermissions` | Not cleared by that path, because `resetRuntimePermissions` returns early unless `permission.isRuntime`. Removal happens only via the app-ID trim above |
| `pm reset-permissions` → `resetRuntimePermissions` (runtime only) | Not cleared |
| Package update with storage/media revoke (`revokePermissionsOnPackageUpdate`) | Not cleared; only `STORAGE_AND_MEDIA_PERMISSIONS` are touched |
| Permission redefinition (protectionLevel or signer change) → re-evaluate | Re-evaluated, but the development branch keeps `RUNTIME_GRANTED` |
| Boot / reboot | No code path found that clears it |

Not checked: `PermissionService.kt` `setRuntimePermissionGranted` after line 1012 (the exact bit set for development), and the Android 13/14 install-state write path.

## Differences by version

- **11**: install-level state in `packages.xml`, verified.
- **13 / 14**: legacy `PermissionManagerServiceImpl` development path, verified for 14. Persistence location not verified.
- **15 / 16**: access-based state in `access.abx`, verified for 15 (file name) and main (reconcile rules). android16-release only grep-verified.

## User reports (2023–2026)

No report I could fetch explicitly says a `pm grant` of DUMP or BATTERY_STATS survived or did not survive a reboot. The reports below are the closest I found.

- [user report, verified via gh] asksven/BetterBatteryStats #917, "can't grant BATTERY_STATS DUMP PACKAGE_USAGE_STATS", opened 2023-07-01, /e/OS on Android 12. The grant command itself fails. Not a reboot report. https://github.com/asksven/BetterBatteryStats/issues/917
- [user report, verified via gh] asksven/BetterBatteryStats #915, "Android 14 vs BATTERY_STATS", opened 2023-06-10, Pixel 7 Pro, Android 14 beta. Stats unavailable. Follow-ups through 2026-01-05 (Android 14 with Magisk, "won't work"). Not a reboot report. https://github.com/asksven/BetterBatteryStats/issues/915
- [user report, unverified, search snippet only] GrapheneOS forum: a user reports ADB-granted microphone and overlay permissions stop working after every reboot until re-applied. Date and device not confirmed, and not a DUMP or BATTERY_STATS grant. https://discuss.grapheneos.org/d/3746/6 (fetch returned an error page)
- [vendor note, unverified, search snippet only] GSam Battery Monitor 3.33 changelog: "Full fix for 'adb permissions getting lost' bug." Date unknown; the APKMirror page returned 403. https://apkmirror.com/apk/gsam-labs/gsam-battery-monitor/gsam-battery-monitor-3-33-release. This is the most relevant lead: the developer acknowledged ADB grants being lost, without details.
- [anecdotal, out of range] r/tasker thread from 2019-12-27 says an ADB permission persisted through a reboot. Out of the 2023–2026 window and not about DUMP.
- Not usable: Fairphone forum thread (2018) about the ADB connection after reboot, not about grant persistence.

The GSam "permanent" claim could not be confirmed from any GSam documentation I could reach.

## How to reproduce the emulator observation

Hypotheses, none verified:

1. **Write race.** The grant is written after 1–2 s and there is no flush on shutdown. Test: `pm grant`, sleep 5 s, then `adb reboot`.
2. **Reinstall or uninstall.** An uninstall trims the app ID and clears the grant. A harness that reinstalls the APK (without `-r`) would clear it. Test: compare the package's `firstInstallTime` and `lastUpdateTime` in `dumpsys package` before and after reboot.
3. **Different user or app ID.** `pm grant` defaults to user 0. Test: check `dumpsys package` for the user the app runs as.
4. **Emulator data not persisted.** Check whether the access file (`access.abx` under the `com.android.permission` APEX data directory, per `PermissionApex.kt`) keeps its mtime and content across the reboot.
5. **Wrong check.** Compare `adb shell dumpsys package <pkg> | grep -E "DUMP|BATTERY_STATS"` before and after, rather than relying on the app's own result.

Developer options: I found no code path linking the developer-options toggle to permission state. I did not search exhaustively, so treat this as unverified.

## Sources

- Local copies of the AOSP files used are under `/tmp/aosp-pm/` (`main/`, `android11-release/`, `android15-release/`, `acc-services_permission_java_com_android_server_permission_access-main.x/`). They were fetched from android.googlesource.com.
- AOSP paths: `frameworks/base/core/res/AndroidManifest.xml`; `services/core/java/com/android/server/pm/PackageManagerShellCommand.java`; `services/core/java/com/android/server/pm/permission/PermissionManagerServiceImpl.java` (main, android14-release); `services/core/java/com/android/server/pm/permission/PermissionManagerService.java` (android11-release); `services/core/java/com/android/server/pm/Settings.java` (android11-release); `services/permission/java/com/android/server/permission/access/{AccessPersistence.kt, AccessPolicy.kt, permission/AppIdPermissionPolicy.kt, permission/PermissionService.kt, permission/PermissionFlags.kt}`.
- Not found: an official Android doc or issue tracker entry (issuetracker.google.com) about development permissions not persisting. I did not search issuetracker.google.com directly, so treat that as unverified.
