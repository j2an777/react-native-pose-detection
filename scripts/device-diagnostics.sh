#!/bin/sh
# Runs the example app's diagnostics sweep on a device, hands-off, and collects the report.
#
#   scripts/device-diagnostics.sh android [scenarios]   # an emulator, or a phone over adb
#   scripts/device-diagnostics.sh ios [scenarios]       # an iPhone paired with this Mac
#
# scenarios: `all` (default, everything but the ten-minute soak) or ids like `files,soak`.
# The app must be installed. APP_ID: com.posedetection.example (default) or com.posedetection.bare.
# ANDROID_SERIAL / DEVICE: which phone, when several are attached. DELEGATE: gpu or cpu only.
# An iPhone asks for the camera on the first run. The `files` scenario needs macOS to make its
# media, and on Android an emulator or a debug build to copy it in.
# Writes diagnostics-<platform>.json to the current directory.

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
  # The interpreter's warnings are noise, shown only if the media fails.
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
    # Root on an emulator, or run-as on a debug build; a release build on a phone can do neither.
    # An app that has never run has no files directory yet.
    if adb root 2>/dev/null | grep -q -e 'restarting' -e 'already running'; then
      adb wait-for-device
      owner=$(adb shell stat -c %u "/data/data/$app")
      adb shell mkdir -p "/data/data/$app/files"
      for file in "$media"/*; do
        adb push "$file" "/data/data/$app/files/$(basename "$file")" >/dev/null
      done
      adb shell "chown -R $owner:$owner /data/data/$app/files && restorecon -R /data/data/$app/files" >/dev/null 2>&1
      have_media=1
    elif adb shell run-as "$app" true 2>/dev/null; then
      adb shell run-as "$app" mkdir -p files
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
  if [ -n "${DELEGATE:-}" ]; then
    query="$query&delegate=$DELEGATE"
  fi

  adb shell am force-stop "$app"
  # MIUI refuses this until "USB debugging (Security settings)" is on; the app then asks on screen.
  adb shell pm grant "$app" android.permission.CAMERA 2>/dev/null ||
    echo "Could not grant the camera over adb: allow it on the phone when the app asks." >&2
  adb logcat -c
  # To a file and killed by hand: a filtered logcat piped onward only exits at its next write.
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
  device=${DEVICE:-}
  if [ -z "$device" ]; then
    # JSON, not the table, as names hold spaces. In reach is any tunnelState but "unavailable".
    xcrun devicectl list devices --quiet --json-output "$media/devices.json" >/dev/null 2>&1 || true
    device=$(node -e '
      const devices = require(process.argv[1]).result?.devices ?? [];
      const phone = devices.find(
        (d) =>
          d.hardwareProperties?.platform === "iOS" &&
          d.hardwareProperties?.reality === "physical" &&
          d.connectionProperties?.pairingState === "paired" &&
          d.connectionProperties?.tunnelState !== "unavailable",
      );
      if (phone) console.log(phone.identifier);
    ' "$media/devices.json" 2>/dev/null || true)
    rm -f "$media/devices.json"
  fi
  if [ -z "$device" ]; then
    echo "No paired iPhone or iPad in reach. Connect one and unlock it, or set DEVICE." >&2
    exit 1
  fi
  container="--device $device --domain-type appDataContainer --domain-identifier $app"

  # An empty report first, so the one read back is this run's, not the last one's.
  printf '{}' >"$media/diagnostics.json"
  # shellcheck disable=SC2086
  xcrun devicectl device copy to $container --source "$media/diagnostics.json" --destination Documents/diagnostics.json >/dev/null
  rm "$media/diagnostics.json"

  set -- -poseDiagnostics "$scenarios"
  if [ -n "${DELEGATE:-}" ]; then
    set -- "$@" -poseDiagnosticsDelegate "$DELEGATE"
  fi
  if make_media; then
    for file in "$media"/*; do
      # shellcheck disable=SC2086
      xcrun devicectl device copy to $container --source "$file" --destination "Documents/$(basename "$file")" >/dev/null
    done
    set -- "$@" -poseDiagnosticsPhoto pose-photo.jpg -poseDiagnosticsRotatedPhoto pose-photo-exif6.jpg \
      -poseDiagnosticsClip pose-clip.mp4
  fi

  # `--`, or devicectl reads `-poseDiagnostics` as a cluster of its own short options.
  xcrun devicectl device process launch --device "$device" --terminate-existing "$app" -- "$@" >/dev/null
  echo "Running on $device. The report is read back when the sweep finishes."
  # The sweep with the soak takes under 20 minutes; no report in an hour means it is not coming.
  waited=0
  while :; do
    sleep 10
    waited=$((waited + 10))
    if [ "$waited" -gt 3600 ]; then
      echo "No report after an hour. Is the app still open on the phone?" >&2
      exit 1
    fi
    # shellcheck disable=SC2086
    xcrun devicectl device copy from $container --source Documents/diagnostics.json \
      --destination diagnostics-ios.json >/dev/null 2>&1 || continue
    grep -q '"reports"' diagnostics-ios.json && break
  done
  node -e '
    const report = require(process.argv[1]);
    if (report.device) console.log(`device ${report.device.summary}`);
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
