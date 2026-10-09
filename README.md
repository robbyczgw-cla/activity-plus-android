# Activity+ for Android

**[Download the APK](https://activityplus.xyz/#android)** · **[Setup guide](docs/SETUP.md)** ([Deutsch](docs/SETUP.de.md)) · [Releases](https://github.com/robbyczgw-cla/activity-plus-android/releases) · [activityplus.xyz](https://activityplus.xyz)

The Android sibling of [Activity+ for Mac](https://github.com/robbyczgw-cla/activity-plus): which app is
responsible, and what to do about it. Native Kotlin and Jetpack Compose, Android 10 or later, no root.

- **Overview** with a verdict that names one app and the next step, and live cards in the Mac colors.
- **Apps**: battery (measured while on screen), screen time, data with the background share, and storage,
  one row per app; "unusual for this app"; app info one tap away.
- **Battery**: current next to voltage, Android's health and the measured capacity as two numbers,
  charging detail and why it is not charging, battery by app, drain per hour with the screen off.
- **History**: 30 days on the phone, one point per minute; tap a point to see the app that was on screen.
- **Why slow?**: plain-language findings, each with a button into the Android screen that fixes it.
  Activity+ never closes apps or deletes files.
- **Status bar**: an ongoing notification whose status bar icon is the live value you pick, up to four
  values in the notification, a Quick Settings tile, and alerts that name the cause.
- **Privacy**: no `INTERNET` permission, no account, no analytics. English and German.

What Android does not let an app see without root or Shizuku: CPU and memory of other apps, wakelocks,
and background battery use per app. Activity+ says so where it matters. Research behind these choices:
`docs/research/`.

## Build and test

Needs JDK 17+ and the Android SDK (platform 36). Put `sdk.dir=/path/to/sdk` in `local.properties`.

```bash
./gradlew :app:testDebugUnitTest      # unit tests: formatting, fuel gauge quirks, drain split, diagnosis, dumpsys parsers
./gradlew :app:assembleRelease        # unsigned unless the release signing properties below are set
python3 -I scripts/l10n-check.py      # every translation against values/strings.xml
scripts/emu.sh start && scripts/emu.sh install && scripts/emu.sh smoke   # emulator smoke test
```

Release signing reads `activityplus.storeFile`, `activityplus.storePassword`, `activityplus.keyAlias` and
`activityplus.keyPassword` from `~/.gradle/gradle.properties`, so no key or password lives in the repo.

## License

MIT, see [LICENSE](LICENSE). The Bricolage Grotesque font is under the SIL Open Font License, see
[FONT-LICENSE-OFL.txt](FONT-LICENSE-OFL.txt).
