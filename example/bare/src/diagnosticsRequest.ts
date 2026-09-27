import { Paths } from 'expo-file-system';
import { Linking, Platform, Settings } from 'react-native';

/** The files the `files` scenario reads, pushed to the device before the launch. */
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
 * The sweep a launch asked for, so it runs with nobody tapping. `scripts/device-diagnostics.sh`
 * builds the launches; a bare media file name is looked for in the documents directory.
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

/** Launch arguments arrive as user defaults: `-poseDiagnostics all -poseDiagnosticsPhoto x`. */
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
