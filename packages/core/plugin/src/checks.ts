import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { join } from 'node:path';

/** `skip` matters: reporting an unknowable value as a failure trains people to ignore doctor. */
export type Check = { status: 'pass' | 'fail' | 'skip'; label: string; detail: string };

export const pass = (label: string, detail: string): Check => ({ status: 'pass', label, detail });
export const fail = (label: string, detail: string): Check => ({ status: 'fail', label, detail });
export const skip = (label: string, detail: string): Check => ({ status: 'skip', label, detail });

/** The SDK each supported React Native pairs with, to name the fix; a newer one gets the newest pair. */
const EXPO_SDK_FOR_REACT_NATIVE: Readonly<Record<string, string>> = { '0.85': '56', '0.86': '57' };

// The floor is the SDK whose NativeArrayBuffer the native code returns frames through.
const MIN_REACT_NATIVE = '0.85';
const MIN_EXPO_SDK = 56;

const [NEWEST_REACT_NATIVE, NEWEST_EXPO_SDK] = Object.entries(EXPO_SDK_FOR_REACT_NATIVE).at(-1) ?? [
  MIN_REACT_NATIVE,
  String(MIN_EXPO_SDK),
];

/** Expo skips some React Native versions, so one newer than the table may have no SDK at all. */
function expoFix(rn: string | undefined): string {
  if (rn === undefined) return 'npm i expo';
  const sdk = EXPO_SDK_FOR_REACT_NATIVE[rn];
  if (sdk !== undefined) return `npm i expo@${sdk}`;
  return (
    `install the Expo SDK built for React Native ${rn} if there is one, or use React Native ` +
    `${NEWEST_REACT_NATIVE} with npm i expo@${NEWEST_EXPO_SDK}`
  );
}

async function readInstalledPackage(
  projectRoot: string,
  name: string,
): Promise<{ dir: string; version: string } | null> {
  try {
    const manifest = createRequire(join(projectRoot, 'package.json')).resolve(
      `${name}/package.json`,
    );
    const { version } = JSON.parse(await readFile(manifest, 'utf8')) as { version?: unknown };
    return typeof version === 'string' ? { dir: join(manifest, '..'), version } : null;
  } catch {
    return null;
  }
}

/** The React Native an Expo SDK names, or undefined when its list is missing or unreadable. */
function bundledReactNative(json: string | null): string | undefined {
  if (json === null) return undefined;
  try {
    const version = (JSON.parse(json) as Record<string, unknown>)['react-native'];
    return typeof version === 'string' ? version : undefined;
  } catch {
    return undefined;
  }
}

const minorOf = (version: string): string | undefined =>
  /(\d+)\.(\d+)/.exec(version)?.slice(1, 3).join('.');

function isBefore(minor: string, floor: string): boolean {
  const [major = 0, rest = 0] = minor.split('.').map(Number);
  const [floorMajor = 0, floorRest = 0] = floor.split('.').map(Number);
  return major < floorMajor || (major === floorMajor && rest < floorRest);
}

/**
 * Expo's peer range accepts any React Native, so npm installs a mismatched SDK without complaint,
 * and yarn, pnpm and `--legacy-peer-deps` install this package below its floor with a warning.
 */
export async function checkExpoMatchesReactNative(projectRoot: string): Promise<Check> {
  const label = 'Expo SDK for React Native';
  const reactNative = await readInstalledPackage(projectRoot, 'react-native');
  const rn = reactNative === null ? undefined : minorOf(reactNative.version);
  if (reactNative !== null && rn !== undefined && isBefore(rn, MIN_REACT_NATIVE)) {
    return fail(
      label,
      `react-native ${reactNative.version} is older than this package supports, which needs ` +
        `React Native ${MIN_REACT_NATIVE} and Expo SDK ${MIN_EXPO_SDK} or newer`,
    );
  }

  const fix = expoFix(rn);

  const expo = await readInstalledPackage(projectRoot, 'expo');
  if (expo === null) return fail(label, `expo is not installed, and it links this module: ${fix}`);
  if (reactNative === null || rn === undefined) return skip(label, 'react-native is not installed');

  const sdk = expo.version.split('.')[0];
  if (Number(sdk) < MIN_EXPO_SDK) {
    return fail(
      label,
      `expo ${sdk} is older than this package supports, which needs SDK ${MIN_EXPO_SDK} or newer: ${fix}`,
    );
  }

  const expected = bundledReactNative(
    await readIfPresent(join(expo.dir, 'bundledNativeModules.json')),
  );
  const built = expected === undefined ? undefined : minorOf(expected);
  if (built === undefined) return skip(label, `expo ${sdk} names no React Native version`);

  return built === rn
    ? pass(label, `expo ${sdk} with react-native ${reactNative.version}`)
    : fail(
        label,
        `expo ${sdk} is built for React Native ${built}, found ${reactNative.version}: ${fix}`,
      );
}

export async function readIfPresent(filePath: string): Promise<string | null> {
  try {
    return await readFile(filePath, 'utf8');
  } catch {
    return null;
  }
}
