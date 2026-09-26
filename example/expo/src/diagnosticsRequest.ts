import { Paths } from 'expo-file-system';
import { Linking, Platform, Settings } from 'react-native';

/** The files the `files` scenario reads. Pushed to the device before the launch, see below. */
export type DiagnosticsMedia = {
  readonly photo?: string;
  /** The same photo stored a quarter turn round, with EXIF orientation 6 to put it back. */
  readonly rotatedPhoto?: string;
  /** A short clip stored sideways with a rotation transform, the way a phone records portrait. */
  readonly clip?: string;
};

export type DiagnosticsRequest = {
  readonly scenarios: readonly string[] | 'all';
  readonly media: DiagnosticsMedia;
  /** Runs the sweep on one delegate, to compare the two on a device. Absent is `auto`. */
  readonly delegate?: 'gpu' | 'cpu';
};

/**
 * Which diagnostics a launch asked for, so a whole sweep can run on a device with nobody tapping.
 * `scripts/device-diagnostics.sh` builds these launches for both platforms.
 *
 * iOS reads launch arguments, which the system turns into user defaults. The `--` keeps devicectl
 * from reading them as its own options:
 *
 *   xcrun devicectl device process launch --device <id> com.posedetection.example \
 *     -- -poseDiagnostics all -poseDiagnosticsPhoto pose-photo.jpg
 *
 * Android reads the launching intent's data, which needs no intent filter when the activity is
 * named explicitly. The action has to be `VIEW`: React Native hands back no initial URL for any
 * other, so without it the app opens as if launched normally:
 *
 *   adb shell am start -a android.intent.action.VIEW -n com.posedetection.example/.MainActivity \
 *     -d "'posediag://run?scenarios=all&photo=file:///sdcard/...'"
 *
 * Scenarios are `all` or a comma-separated list of ids. A media value with a scheme is used as it
 * is; a bare file name is looked for in the app's documents directory, which is where
 * `xcrun devicectl device copy to` puts a file on iOS. `delegate` is `gpu` or `cpu` to hold the
 * sweep to one of them.
 */
export async function diagnosticsRequest(): Promise<DiagnosticsRequest | null> {
  const read = Platform.OS === 'ios' ? readLaunchArguments() : await readIntent();
  const scenarios = read('scenarios');
  if (!scenarios) return null;
  return {
    scenarios:
      scenarios === 'all'
        ? 'all'
        : scenarios
            .split(',')
            .map((item) => item.trim())
            .filter((item) => item.length > 0),
    media: {
      photo: resolveMedia(read('photo')),
      rotatedPhoto: resolveMedia(read('rotatedPhoto')),
      clip: resolveMedia(read('clip')),
    },
    delegate: delegateOf(read('delegate')),
  };
}

function delegateOf(value: string | undefined): 'gpu' | 'cpu' | undefined {
  return value === 'gpu' || value === 'cpu' ? value : undefined;
}

type Reader = (name: string) => string | undefined;

/** `-poseDiagnostics` for the scenarios, `-poseDiagnosticsPhoto` and so on for the rest. */
function readLaunchArguments(): Reader {
  return (name) => {
    const suffix = name === 'scenarios' ? '' : `${name.charAt(0).toUpperCase()}${name.slice(1)}`;
    const value: unknown = Settings.get(`poseDiagnostics${suffix}`);
    return typeof value === 'string' && value.length > 0 ? value : undefined;
  };
}

async function readIntent(): Promise<Reader> {
  const url = (await Linking.getInitialURL()) ?? '';
  return (name) => {
    const match = new RegExp(`[?&]${name}=([^&]+)`).exec(url);
    return match?.[1] ? decodeURIComponent(match[1]) : undefined;
  };
}

function resolveMedia(value: string | undefined): string | undefined {
  if (!value) return undefined;
  if (value.includes('://')) return value;
  return `${Paths.document.uri.replace(/\/$/, '')}/${value}`;
}
