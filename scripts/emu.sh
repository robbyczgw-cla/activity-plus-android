#!/usr/bin/env bash
# Emulator helpers for screenshots and smoke tests.
#   scripts/emu.sh start            boot the AVD (EMU_AVD, default DroidplusQA) on port EMU_PORT (default 5560)
#   scripts/emu.sh install [apk]    install (default: release build) and grant usage access + notifications
#   scripts/emu.sh shot NAME [TAB]  open a tab (OVERVIEW, APPS, BATTERY, HISTORY, HARDWARE, DIAGNOSIS, PRO, SETTINGS) and save shots/NAME.png
#   scripts/emu.sh smoke            open every tab and settings; fail if the app crashed
#   scripts/emu.sh widgets          debug build: show all widgets and save shots/widgets.png
#   scripts/emu.sh tap TEXT         tap the first on-screen element whose text is TEXT
#   scripts/emu.sh stop
# Local settings (not in the repo) go in ${XDG_CONFIG_HOME:-~/.config}/droidplus.env, for example
# EMU_DOCKER_IMAGE=<image with the emulator's libraries> when your user cannot open /dev/kvm:
# the emulator then runs in that container with /dev/kvm passed through.
set -euo pipefail
cd "$(dirname "$0")/.."
CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/droidplus.env"
[ -f "$CONFIG" ] && . "$CONFIG"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
AVD="${EMU_AVD:-DroidplusQA}"
PORT="${EMU_PORT:-5560}"
ADB="$SDK/platform-tools/adb -s emulator-$PORT"
EMU_ARGS=(-avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save)
PKG=xyz.activityplus.android

case "${1:-}" in
start)
  if [ -n "${EMU_DOCKER_IMAGE:-}" ]; then
    docker rm -f droidplus-emu >/dev/null 2>&1 || true
    docker run -d --name droidplus-emu --network host --device /dev/kvm --group-add "$(getent group kvm | cut -d: -f3)" \
      -u "$(id -u):$(id -g)" -e HOME="$HOME" -e ANDROID_SDK_ROOT="$SDK" \
      -v "$HOME/.android:$HOME/.android" -v "$SDK:$SDK:ro" \
      --entrypoint "$SDK/emulator/emulator" "$EMU_DOCKER_IMAGE" "${EMU_ARGS[@]}" >/dev/null
  else
    nohup "$SDK/emulator/emulator" "${EMU_ARGS[@]}" >/tmp/droidplus-emulator.log 2>&1 &
  fi
  for _ in $(seq 1 60); do
    [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && echo booted && exit 0
    sleep 5
  done
  echo "emulator did not boot" >&2; exit 1 ;;
install)
  apk="${2:-app/build/outputs/apk/release/app-release.apk}"
  $ADB install -r "$apk" >/dev/null
  $ADB shell appops set $PKG GET_USAGE_STATS allow
  $ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
  # A fresh install opens on the onboarding; get past it so the tabs are what gets tested.
  $ADB shell am start -n $PKG/.MainActivity >/dev/null; sleep 4
  if $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null && $ADB shell cat /sdcard/ui.xml | grep -q "Usage access\|Nutzungszugriff"; then
    $ADB shell input swipe 540 1900 540 500 300; sleep 1
    "$0" tap "Start" >/dev/null 2>&1 || "$0" tap "Los geht's" >/dev/null 2>&1 || true
  fi
  echo installed ;;
shot)
  name="$2"; tab="${3:-OVERVIEW}"; mkdir -p shots
  if [ "$tab" = SETTINGS ]; then extra=(--ez settings true); else extra=(-e tab "$tab"); fi
  $ADB shell am force-stop $PKG
  $ADB shell am start -n $PKG/.MainActivity "${extra[@]}" >/dev/null
  sleep "${WAIT:-4}"
  $ADB exec-out screencap -p > "shots/$name.png"
  echo "shots/$name.png" ;;
smoke)
  $ADB logcat -c -b crash
  for tab in OVERVIEW APPS BATTERY HISTORY HARDWARE DIAGNOSIS PRO SETTINGS; do
    WAIT=5 "$0" shot "smoke-$tab" "$tab" >/dev/null
    # Lazy lists build cards only when they scroll into view, so scroll to the end.
    for _ in 1 2 3 4 5 6; do $ADB shell input swipe 540 1900 540 500 250; sleep 1; done
    $ADB exec-out screencap -p > "shots/smoke-$tab-end.png"
    if $ADB logcat -d -b crash | grep -q "$PKG"; then
      echo "CRASH on $tab:" >&2
      $ADB logcat -d -b crash | grep -E "Exception|Error" | head -3 >&2
      exit 1
    fi
    echo "$tab ok"
  done ;;
widgets)
  $ADB install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
  $ADB shell appops set $PKG GET_USAGE_STATS allow
  $ADB shell cmd appwidget grantbind --package $PKG
  $ADB shell am start -n $PKG/.WidgetPreviewActivity >/dev/null
  sleep "${WAIT:-6}"
  mkdir -p shots; $ADB exec-out screencap -p > shots/widgets.png
  echo shots/widgets.png ;;
tap)
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null
  bounds=$($ADB shell cat /sdcard/ui.xml | grep -o "text=\"$2\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -n "$bounds" ] || { echo "not found: $2" >&2; exit 1; }
  set -- $bounds
  $ADB shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  echo "tapped" ;;
stop)
  if [ -n "${EMU_DOCKER_IMAGE:-}" ]; then docker rm -f droidplus-emu >/dev/null; else $ADB emu kill; fi ;;
*)
  sed -n '2,13p' "$0"; exit 1 ;;
esac
