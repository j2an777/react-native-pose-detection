import { Linking, Platform, Settings } from 'react-native';

export type DiagnosticsRequest = readonly string[] | 'all';

/**
 * Which diagnostics a launch asked for, so a whole sweep can run on a device with nobody tapping.
 *
 * iOS reads a launch argument, which the system turns into a user default:
 *
 *   xcrun devicectl device process launch --device <id> com.posedetection.example -poseDiagnostics all
 *
 * Android reads the launching intent's data, which needs no intent filter when the activity is
 * named explicitly:
 *
 *   adb shell am start -n com.posedetection.example/.MainActivity -d 'posediag://run?scenarios=all'
 *
 * Either takes `all` or a comma-separated list of scenario ids.
 */
export async function diagnosticsRequest(): Promise<DiagnosticsRequest | null> {
  let value: unknown = null;
  if (Platform.OS === 'ios') {
    value = Settings.get('poseDiagnostics');
  } else {
    const url = await Linking.getInitialURL();
    const match = url ? /[?&]scenarios=([^&]+)/.exec(url) : null;
    value = match?.[1] ? decodeURIComponent(match[1]) : null;
  }
  if (typeof value !== 'string' || value.length === 0) return null;
  if (value === 'all') return 'all';
  return value
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}
