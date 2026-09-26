#!/bin/sh
# Runs the example app's diagnostics sweep on a device with nobody tapping, and collects the
# results.
#
#   scripts/device-diagnostics.sh android [scenarios]   # an emulator, or a phone over adb
#   scripts/device-diagnostics.sh ios [scenarios]       # an iPhone paired with this Mac
#
# `scenarios` is `all` (the default, every scenario except the ten-minute soak) or a
# comma-separated list of ids, such as `files` or `soak`. The example app must already be
# installed. APP_ID picks which one: com.posedetection.example (the default) or
# com.posedetection.bare. On iOS, DEVICE picks the phone when more than one is paired.
#
# The `files` scenario needs a photo and a clip on the device. This script makes them from
# ss/export-frame.png with scripts/diagnostics-media.swift, which needs macOS, and copies them
# into the app's documents directory. Android can only write there on an emulator, where adb runs
# as root, or with a debug build, through run-as.
#
# Prints every scenario's line as it runs, and saves the full report as
# diagnostics-<platform>.json in the current directory.

set -eu

platform=${1:-}
scenarios=${2:-all}
app=${APP_ID:-com.posedetection.example}
root_dir=$(cd "$(dirname "$0")/.." && pwd)
media=$(mktemp -d)
trap 'rm -rf "$media"' EXIT

# Prints each POSE_DIAG line of a growing log once, until the sweep's DONE line.
follow() {
  printed=0
  while :; do
    count=$(grep -c 'POSE_DIAG' "$1" 2>/dev/null || true)
    if [ "${count:-0}" -gt "$printed" ]; then
      grep 'POSE_DIAG' "$1" | tail -n "+$((printed + 1))" | sed 's/^.*POSE_DIAG //'
      printed=$count
    fi
    grep -q 'POSE_DIAG DONE' "$1" && return 0
    sleep 2
  done
}

make_media() {
  if [ "$(uname)" != "Darwin" ]; then
    echo "Not macOS: the files scenario will be skipped." >&2
    return 1
  fi
  # The interpreter's compiler warnings are noise here; they are shown only if the media fails.
  if ! xcrun swift "$root_dir/scripts/diagnostics-media.swift" "$root_dir/ss/export-frame.png" "$media" \
    >"$media/media.log" 2>&1; then
    cat "$media/media.log" >&2
    return 1
  fi
  rm "$media/media.log"
}

run_android() {
  if ! command -v adb >/dev/null 2>&1; then
    PATH="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platform-tools:$PATH"
  fi
  adb get-state >/dev/null
  have_media=0
  if make_media; then
    # Root on an emulator writes straight into the app's files; a debug build copies through
    # run-as. A release build on a real phone can do neither, and the files scenario skips.
    if adb root 2>/dev/null | grep -q -e 'restarting' -e 'already running'; then
      adb wait-for-device
      owner=$(adb shell stat -c %u "/data/data/$app")
      for file in "$media"/*; do
        adb push "$file" "/data/data/$app/files/" >/dev/null
      done
      adb shell "chown -R $owner:$owner /data/data/$app/files && restorecon -R /data/data/$app/files" >/dev/null 2>&1
      have_media=1
    elif adb shell run-as "$app" true 2>/dev/null; then
      for file in "$media"/*; do
        name=$(basename "$file")
        adb push "$file" "/data/local/tmp/$name" >/dev/null
        adb shell run-as "$app" cp "/data/local/tmp/$name" "files/$name"
      done
      have_media=1
    else
      echo "Cannot write into $app's files: the files scenario will be skipped." >&2
    fi
  fi

  query="scenarios=$scenarios"
  if [ "$have_media" -eq 1 ]; then
    query="$query&photo=pose-photo.jpg&rotatedPhoto=pose-photo-exif6.jpg&clip=pose-clip.mp4"
  fi

  adb shell am force-stop "$app"
  adb logcat -c
  # Captured to a file and stopped by hand: a filtered logcat piped into something that has
  # finished only notices at its next write, which may never come.
  log="$media/logcat.txt"
  adb logcat -v raw -s ReactNativeJS:V >"$log" 2>/dev/null &
  logcat=$!
  trap 'kill "$logcat" 2>/dev/null || true; rm -rf "$media"' EXIT
  # VIEW, or React Native hands the app no initial URL and it opens as if launched normally.
  adb shell am start -W -a android.intent.action.VIEW -n "$app/.MainActivity" -d "'posediag://run?$query'" >/dev/null
  follow "$log"
  kill "$logcat" 2>/dev/null || true

  if adb shell run-as "$app" true 2>/dev/null; then
    adb shell run-as "$app" cat files/diagnostics.json >diagnostics-android.json
  else
    adb shell cat "/data/data/$app/files/diagnostics.json" >diagnostics-android.json 2>/dev/null || true
  fi
  echo "Report: $(pwd)/diagnostics-android.json"
}

run_ios() {
  if [ -z "${DEVELOPER_DIR:-}" ] && [ -d /Applications/Xcode.app ]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
  fi
  device=${DEVICE:-$(xcrun devicectl list devices 2>/dev/null | awk '/iPhone/ && /available/ { print $3; exit }')}
  if [ -z "$device" ]; then
    echo "No paired, available iPhone. Connect one and unlock it, or set DEVICE." >&2
    exit 1
  fi
  container="--device $device --domain-type appDataContainer --domain-identifier $app"

  # An empty report first, so the one read back below is this run's rather than the last one's.
  printf '{}' >"$media/diagnostics.json"
  # shellcheck disable=SC2086
  xcrun devicectl device copy to $container --source "$media/diagnostics.json" --destination Documents/diagnostics.json >/dev/null
  rm "$media/diagnostics.json"

  set -- -poseDiagnostics "$scenarios"
  if make_media; then
    for file in "$media"/*; do
      # shellcheck disable=SC2086
      xcrun devicectl device copy to $container --source "$file" --destination "Documents/$(basename "$file")" >/dev/null
    done
    set -- "$@" -poseDiagnosticsPhoto pose-photo.jpg -poseDiagnosticsRotatedPhoto pose-photo-exif6.jpg \
      -poseDiagnosticsClip pose-clip.mp4
  fi

  xcrun devicectl device process launch --device "$device" --terminate-existing "$app" "$@" >/dev/null
  echo "Running on $device. The report is read back when the sweep finishes."
  while :; do
    sleep 10
    # shellcheck disable=SC2086
    xcrun devicectl device copy from $container --source Documents/diagnostics.json \
      --destination diagnostics-ios.json >/dev/null 2>&1 || continue
    grep -q '"reports"' diagnostics-ios.json && break
  done
  node -e '
    const report = require(process.argv[1]);
    for (const r of report.reports) {
      const verdict = r.skipped ? "SKIP" : r.passed ? "PASS" : "FAIL";
      console.log(`${verdict} ${r.id} ${Math.round(r.elapsedMs)} ms · ${r.detail}`);
    }
    const failed = report.reports.filter((r) => !r.passed).length;
    console.log(`DONE ${report.reports.length} scenarios, ${failed} failed`);
  ' "$(pwd)/diagnostics-ios.json"
  echo "Report: $(pwd)/diagnostics-ios.json"
}

case "$platform" in
  android) run_android ;;
  ios) run_ios ;;
  *)
    echo "usage: scripts/device-diagnostics.sh android|ios [scenarios]" >&2
    exit 2
    ;;
esac
