import { readFile, stat } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { join, relative } from 'node:path';
import { pathToFileURL } from 'node:url';

import { DEFAULT_CACHE_DIR, clearCache, ensureModel, sha256OfFile } from './download';
import {
  DEFAULT_ANDROID_PROJECT,
  androidAssetsDir,
  directoryExists,
  findInstalledModels,
  findIosProjectName,
  findXcodeProjectPath,
  installModelFile,
  iosResourcesDir,
  removeInstalledModels,
} from './install';
import type { AndroidProject } from './install';
import * as log from './log';
import type { ModelEntry } from './manifest';
import type * as Pbxproj from './pbxproj';
import { KNOWN_MODEL_FILE_PATTERN, MODEL_VARIANTS, resolveModel } from './manifest';

const USAGE = `
react-native-pose-detection <command>

  fetch-model <lite|full|heavy>   download, verify, and install into both native projects
  doctor                          check the things that actually break
  clear-cache                     delete the model cache

Flags for fetch-model:
  --force                         re-download even on a cache hit
  --cache-dir <path>              override the cache location
  --ios-only, --android-only      install into one platform

Flags for clear-cache:
  --cache-dir <path>              override the cache location
`.trim();

type Flags = {
  force: boolean;
  cacheDir: string;
  android: boolean;
  ios: boolean;
  positionals: string[];
};

/** An inapplicable flag is an error: `doctor --cache-dir` reads as a request that is ignored. */
function parseFlags(command: string, argv: readonly string[], allowed: readonly string[]): Flags {
  const flags: Flags = {
    force: false,
    cacheDir: DEFAULT_CACHE_DIR,
    android: true,
    ios: true,
    positionals: [],
  };

  const accept = (flag: string): void => {
    if (!allowed.includes(flag)) {
      throw new Error(`${command} does not take ${flag}.\n\n${USAGE}`);
    }
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === undefined) continue;

    switch (arg) {
      case '--force':
        accept(arg);
        flags.force = true;
        break;
      case '--ios-only':
        accept(arg);
        flags.android = false;
        break;
      case '--android-only':
        accept(arg);
        flags.ios = false;
        break;
      case '--cache-dir': {
        accept(arg);
        const value = argv[index + 1];
        if (value === undefined || value.startsWith('--')) {
          throw new Error('--cache-dir needs a path.');
        }
        flags.cacheDir = value;
        index += 1;
        break;
      }
      default:
        if (arg.startsWith('--')) throw new Error(`Unknown flag ${arg}.\n\n${USAGE}`);
        flags.positionals.push(arg);
    }
  }

  if (!flags.android && !flags.ios) {
    throw new Error('--ios-only and --android-only cannot both be set.');
  }
  return flags;
}

/** On demand, so `--android-only` and `doctor` work where `expo` cannot be resolved. */
async function loadXcodeSupport(): Promise<typeof Pbxproj | null> {
  try {
    return await import('./pbxproj.js');
  } catch {
    return null;
  }
}

const RN_CONFIG_NAMES = [
  'react-native.config.js',
  'react-native.config.cjs',
  'react-native.config.mjs',
];

/**
 * Found as React Native's CLI finds it, from react-native.config.js: a model copied into
 * `android/app` of an app with a renamed module is never packaged.
 */
async function readAndroidProject(projectRoot: string): Promise<AndroidProject> {
  let android: { sourceDir?: unknown; appName?: unknown } | undefined;

  for (const name of RN_CONFIG_NAMES) {
    const path = join(projectRoot, name);
    try {
      await stat(path);
    } catch {
      continue;
    }
    try {
      const loaded = (await import(pathToFileURL(path).href)) as {
        default?: { project?: { android?: typeof android } };
      };
      android = loaded.default?.project?.android;
    } catch (error) {
      log.warn(`could not load ${name}, assuming android/app: ${(error as Error).message}`);
    }
    break;
  }

  const sourceDir =
    typeof android?.sourceDir === 'string' && android.sourceDir !== ''
      ? android.sourceDir
      : DEFAULT_ANDROID_PROJECT.sourceDir;
  const appName =
    typeof android?.appName === 'string' &&
    android.appName !== '' &&
    (await directoryExists(join(projectRoot, sourceDir, android.appName)))
      ? android.appName
      : DEFAULT_ANDROID_PROJECT.appName;
  return { sourceDir, appName };
}

/** Returns whether anything was installed, which is what decides the exit code. */
async function installIos(
  projectRoot: string,
  cachePath: string,
  model: ModelEntry,
): Promise<boolean> {
  const projectName = await findIosProjectName(projectRoot);
  if (!projectName) {
    log.warn('no ios/*.xcodeproj found, skipping the iOS install.');
    return false;
  }

  // A hand-copied file next to the sources ends up in the bundle too, so clear that first.
  await removeInstalledModels(join(projectRoot, 'ios', projectName));
  const installed = await installModelFile(
    cachePath,
    iosResourcesDir(projectRoot, projectName),
    model,
  );
  log.line(`copied → ${relative(projectRoot, installed)}`);

  const xcode = await loadXcodeSupport();
  if (!xcode) {
    log.warn(
      'could not load expo/config-plugins, so the Xcode project was not updated. Add ' +
        `${relative(projectRoot, installed)} to your app target in Xcode once.`,
    );
    return true;
  }

  const project = xcode.loadProject(projectRoot);
  const { removed } = xcode.syncModelReference(project, projectName, model.fileName);
  const filepath = await xcode.saveProject(project);

  for (const stale of removed) log.line(`unregistered ${stale}`);
  log.line(`registered → ${relative(projectRoot, filepath)}`);
  return true;
}

async function fetchModelCommand(flags: Flags): Promise<number> {
  const variant = flags.positionals[0];
  if (variant === undefined) {
    throw new Error(`fetch-model needs a variant: ${MODEL_VARIANTS.join(', ')}.`);
  }

  const model = resolveModel(variant);
  const projectRoot = process.cwd();

  const pairing = await checkExpoMatchesReactNative(projectRoot);
  if (pairing.status === 'fail') log.warn(pairing.detail);

  const cachePath = await ensureModel(model.variant, {
    cacheDir: flags.cacheDir,
    force: flags.force,
  });
  if (cachePath === null) throw new Error(`${model.fileName} could not be resolved.`);

  let installed = 0;

  if (flags.android) {
    const android = await readAndroidProject(projectRoot);
    // Here, not in installModelFile: prebuild rightly creates android/, but run from the wrong
    // directory the CLI would fabricate a four-level tree.
    if (await directoryExists(join(projectRoot, android.sourceDir))) {
      const target = await installModelFile(
        cachePath,
        androidAssetsDir(projectRoot, android),
        model,
      );
      log.line(`copied → ${relative(projectRoot, target)}`);
      installed += 1;
    } else {
      log.warn(`no ${android.sourceDir}/ directory here, skipping the Android install.`);
    }
  }

  if (flags.ios && (await installIos(projectRoot, cachePath, model))) installed += 1;

  if (installed === 0) {
    log.warn(
      `the model is in the cache, but ${projectRoot} holds no native project to install it ` +
        `into. Run this from the app root, after prebuild.`,
    );
    return 1;
  }
  return 0;
}

/** `skip` matters: reporting an unknowable value as a failure trains people to ignore doctor. */
type Check = { status: 'pass' | 'fail' | 'skip'; label: string; detail: string };

const pass = (label: string, detail: string): Check => ({ status: 'pass', label, detail });
const fail = (label: string, detail: string): Check => ({ status: 'fail', label, detail });
const skip = (label: string, detail: string): Check => ({ status: 'skip', label, detail });

const SYMBOL = { pass: '✓', fail: '✗', skip: '–' } as const;

/** The SDK each supported React Native pairs with, to name the fix; newer pairs fall back to prose. */
const EXPO_SDK_FOR_REACT_NATIVE: Readonly<Record<string, string>> = { '0.85': '56', '0.86': '57' };

async function readInstalledPackage(
  projectRoot: string,
  name: string,
): Promise<{ dir: string; version: string } | null> {
  try {
    const manifest = createRequire(join(projectRoot, 'package.json')).resolve(
      `${name}/package.json`,
    );
    const { version } = JSON.parse(await readFile(manifest, 'utf8')) as { version: string };
    return { dir: join(manifest, '..'), version };
  } catch {
    return null;
  }
}

const minorOf = (version: string): string | undefined =>
  /(\d+)\.(\d+)/.exec(version)?.slice(1, 3).join('.');

/** Expo's peer range accepts any React Native, so a mismatched SDK installs cleanly and fails natively. */
async function checkExpoMatchesReactNative(projectRoot: string): Promise<Check> {
  const label = 'Expo SDK for React Native';
  const reactNative = await readInstalledPackage(projectRoot, 'react-native');
  const rn = reactNative === null ? undefined : minorOf(reactNative.version);
  const fix =
    rn === undefined
      ? 'npm i expo'
      : `npm i expo@${EXPO_SDK_FOR_REACT_NATIVE[rn] ?? `<the SDK for React Native ${rn}>`}`;

  const expo = await readInstalledPackage(projectRoot, 'expo');
  if (expo === null) return fail(label, `expo is not installed, and it links this module: ${fix}`);
  if (reactNative === null || rn === undefined) return skip(label, 'react-native is not installed');

  const bundled = await readIfPresent(join(expo.dir, 'bundledNativeModules.json'));
  const expected =
    bundled === null ? undefined : (JSON.parse(bundled) as Record<string, string>)['react-native'];
  const built = expected === undefined ? undefined : minorOf(expected);
  const sdk = expo.version.split('.')[0];
  if (built === undefined) return skip(label, `expo ${sdk} names no React Native version`);

  return built === rn
    ? pass(label, `expo ${sdk} with react-native ${reactNative.version}`)
    : fail(
        label,
        `expo ${sdk} is built for React Native ${built}, found ${reactNative.version}: ${fix}`,
      );
}

async function readIfPresent(filePath: string): Promise<string | null> {
  try {
    return await readFile(filePath, 'utf8');
  } catch {
    return null;
  }
}

async function checkInstalledModel(dir: string, shortDir: string): Promise<Check[]> {
  const present = await findInstalledModels(dir);

  if (present.length === 0) return [fail('model installed', `${shortDir} has no model`)];
  if (present.length > 1) {
    return [fail('model installed', `${shortDir} has ${present.length}: ${present.join(', ')}`)];
  }

  const fileName = present[0] as string;
  if (!KNOWN_MODEL_FILE_PATTERN.test(fileName)) {
    // Native loads any pose_landmarker_*.task, so this one runs with no hash to check it against.
    return [
      fail(
        'model installed',
        `${shortDir}/${fileName} is not a model this package installs, and the runtime loads it`,
      ),
    ];
  }

  const variant = fileName.replace(/^pose_landmarker_/, '').replace(/\.task$/, '');
  const actual = await sha256OfFile(join(dir, fileName));
  const expected = resolveModel(variant).sha256;

  return [
    pass('model installed', `${shortDir}/${fileName}`),
    actual === expected
      ? pass('SHA-256 matches manifest', fileName)
      : fail('SHA-256 matches manifest', `${fileName} hashes to ${actual}`),
  ];
}

/**
 * Bare RN writes it to `android/build.gradle` and `expo-build-properties` to
 * `gradle.properties`. A plain prebuild writes neither, so the value is unknowable here.
 */
async function checkMinSdk(projectRoot: string, android: AndroidProject): Promise<Check> {
  const label = 'minSdkVersion 24';
  const sources = await Promise.all([
    readIfPresent(join(projectRoot, android.sourceDir, 'build.gradle')),
    readIfPresent(join(projectRoot, android.sourceDir, 'gradle.properties')),
  ]);

  if (sources.every((source) => source === null)) {
    return skip(label, `no build.gradle or gradle.properties in ${android.sourceDir}/`);
  }

  const text = sources.join('\n');
  const found = /(?:minSdkVersion\s*=\s*|android\.minSdkVersion\s*=\s*)(\d+)/.exec(text)?.[1];

  if (found === undefined) {
    return skip(label, 'resolved by the Expo Gradle plugin, not readable from the project');
  }
  return Number(found) >= 24
    ? pass(label, `found ${found}`)
    : fail(label, `found ${found}, this package needs 24`);
}

const PBX_UUID = '[0-9A-Fa-f]{12,32}';

function pbxSection(pbxproj: string, name: string): string {
  const start = pbxproj.indexOf(`/* Begin ${name} section */`);
  const end = pbxproj.indexOf(`/* End ${name} section */`);
  return start === -1 || end === -1 || end < start ? '' : pbxproj.slice(start, end);
}

/** Text read, not a parser, so `doctor` survives the misconfigured projects it exists for. */
function pbxObject(section: string, uuid: string): string | null {
  const definition = new RegExp(`\\b${uuid}\\b\\s*(?:/\\*[^*]*\\*/\\s*)?=\\s*\\{`).exec(section);
  if (!definition) return null;

  const open = definition.index + definition[0].length - 1;
  let depth = 0;

  for (let index = open; index < section.length; index += 1) {
    const char = section[index];
    if (char === '{') depth += 1;
    else if (char === '}') {
      depth -= 1;
      if (depth === 0) return section.slice(open + 1, index);
    }
  }
  return null;
}

function pbxReferences(body: string): string[] {
  return [...body.matchAll(new RegExp(`(${PBX_UUID}) /\\*`, 'g'))].map(
    (match) => match[1] as string,
  );
}

/** The app target, which is not always the first one: a widget or a test target can precede it. */
function applicationTarget(pbxproj: string): string | null {
  const targets = pbxSection(pbxproj, 'PBXNativeTarget');

  for (const uuid of pbxReferences(targets)) {
    const body = pbxObject(targets, uuid);
    if (body === null || !body.includes('isa = PBXNativeTarget')) continue;
    if (/productType = "?com\.apple\.product-type\.application"?/.test(body)) return uuid;
  }
  return null;
}

function buildConfigurationList(body: string | null): string | undefined {
  return new RegExp(`buildConfigurationList = (${PBX_UUID})`).exec(body ?? '')?.[1];
}

function deploymentTargetsOf(pbxproj: string, listUuid: string | undefined): number[] {
  if (listUuid === undefined) return [];

  const listBody = pbxObject(pbxSection(pbxproj, 'XCConfigurationList'), listUuid);
  if (listBody === null) return [];

  const configurations = pbxSection(pbxproj, 'XCBuildConfiguration');
  const values: number[] = [];

  for (const uuid of pbxReferences(listBody)) {
    const body = pbxObject(configurations, uuid);
    const found = /IPHONEOS_DEPLOYMENT_TARGET = "?([\d.]+)"?/.exec(body ?? '')?.[1];
    if (found !== undefined) values.push(parseFloat(found));
  }
  return values;
}

/** Only the app target counts: an extension pinned lower is no reason to fail a correct app. */
function checkDeploymentTarget(pbxproj: string | null): Check {
  const label = 'iOS deployment target 16.4';
  if (pbxproj === null) return skip(label, 'no Xcode project, run prebuild first');

  const appUuid = applicationTarget(pbxproj);
  const targetBody =
    appUuid === null ? null : pbxObject(pbxSection(pbxproj, 'PBXNativeTarget'), appUuid);
  const values = deploymentTargetsOf(pbxproj, buildConfigurationList(targetBody));

  // A target that sets nothing inherits the project-level value.
  const found =
    values.length > 0
      ? values
      : deploymentTargetsOf(pbxproj, buildConfigurationList(pbxSection(pbxproj, 'PBXProject')));

  if (found.length === 0) {
    return skip(label, 'no IPHONEOS_DEPLOYMENT_TARGET on the app target');
  }

  const lowest = Math.min(...found);
  return lowest >= 16.4
    ? pass(label, `found ${lowest}`)
    : fail(label, `found ${lowest}, Expo SDK 56 and later need 16.4`);
}

/**
 * A .task no target builds is never bundled and fails at runtime with MODEL_NOT_FOUND. The CLI
 * produces exactly that state when `expo/config-plugins` cannot be resolved.
 */
async function checkXcodeRegistration(
  pbxproj: string | null,
  resourcesDir: string,
): Promise<Check> {
  const label = 'model in the app target';
  if (pbxproj === null) return skip(label, 'no Xcode project, run prebuild first');

  const fileName = (await findInstalledModels(resourcesDir))[0];
  if (fileName === undefined) return skip(label, 'no model installed to look for');

  const appUuid = applicationTarget(pbxproj);
  const body = appUuid === null ? null : pbxObject(pbxSection(pbxproj, 'PBXNativeTarget'), appUuid);
  if (body === null) return skip(label, 'no application target in the Xcode project');

  const phases = pbxSection(pbxproj, 'PBXResourcesBuildPhase');
  for (const uuid of pbxReferences(body)) {
    const phase = pbxObject(phases, uuid);
    if (phase === null) continue;

    if (phase.includes(fileName)) return pass(label, `${fileName} is a build resource`);

    // Entries without name comments (some writers strip them) cannot be read: skip, not fail.
    const entries = phase.match(new RegExp(PBX_UUID, 'g')) ?? [];
    if (entries.length > 0 && !phase.includes('/*')) {
      return skip(label, 'the build phase lists no file names to read');
    }

    return fail(
      label,
      `${fileName} is on disk but not built into the app target, so it will not be in the bundle`,
    );
  }
  return skip(label, 'the application target has no Resources build phase');
}

/** Best-effort reads that can say "could not determine" beat a parser that throws. */
async function doctorCommand(): Promise<number> {
  const projectRoot = process.cwd();
  const android = await readAndroidProject(projectRoot);
  const hasAndroid = await directoryExists(join(projectRoot, android.sourceDir));
  const hasIos = await directoryExists(join(projectRoot, 'ios'));

  // One platform missing is normal; both missing is the wrong directory, which is not a pass.
  if (!hasAndroid && !hasIos) {
    return report([
      fail(
        'native project',
        `no ${android.sourceDir}/ or ios/ here, run this from the app root after prebuild`,
      ),
    ]);
  }

  const checks: Check[] = [await checkExpoMatchesReactNative(projectRoot)];

  checks.push(
    ...(hasAndroid
      ? await checkInstalledModel(
          androidAssetsDir(projectRoot, android),
          `${android.sourceDir}/${android.appName}/src/main/assets`,
        )
      : [skip('android project', `no ${android.sourceDir}/ directory`)]),
  );

  const projectName = hasIos ? await findIosProjectName(projectRoot) : null;
  let pbxproj: string | null = null;

  if (!hasIos) {
    checks.push(skip('ios project', 'no ios/ directory'));
  } else if (projectName === null) {
    checks.push(fail('ios project', 'no ios/*.xcodeproj found'));
  } else {
    const xcodeproj = await findXcodeProjectPath(projectRoot);
    pbxproj = xcodeproj === null ? null : await readIfPresent(join(xcodeproj, 'project.pbxproj'));

    const resourcesDir = iosResourcesDir(projectRoot, projectName);
    checks.push(...(await checkInstalledModel(resourcesDir, `ios/${projectName}/Resources`)));
    checks.push(await checkXcodeRegistration(pbxproj, resourcesDir));

    const stray = await findInstalledModels(join(projectRoot, 'ios', projectName));
    if (stray.length > 0) {
      checks.push(fail('one model on iOS', `also found ${stray.join(', ')} beside the sources`));
    }
  }

  if (hasAndroid) checks.push(await checkMinSdk(projectRoot, android));

  if (projectName !== null) {
    checks.push(checkDeploymentTarget(pbxproj));
  }

  if (hasAndroid) {
    const manifest = await readIfPresent(
      join(projectRoot, android.sourceDir, android.appName, 'src', 'main', 'AndroidManifest.xml'),
    );
    // Absent is not a failure: this package's own manifest declares it, and the merger adds it.
    checks.push(
      manifest === null
        ? skip('android.permission.CAMERA', 'no AndroidManifest.xml, run prebuild first')
        : manifest.includes('android.permission.CAMERA')
        ? pass('android.permission.CAMERA', 'AndroidManifest.xml')
        : pass('android.permission.CAMERA', 'merged in from this package, not in the app manifest'),
    );
  }

  if (projectName !== null) {
    const plist = await readIfPresent(join(projectRoot, 'ios', projectName, 'Info.plist'));
    checks.push(
      plist === null
        ? skip('NSCameraUsageDescription', 'no Info.plist, run prebuild first')
        : plist.includes('NSCameraUsageDescription')
        ? pass('NSCameraUsageDescription', 'Info.plist')
        : fail('NSCameraUsageDescription', 'missing from Info.plist'),
    );
  }

  return report(checks);
}

function report(checks: readonly Check[]): number {
  for (const check of checks) {
    log.line(`${SYMBOL[check.status]} ${check.label.padEnd(27)} ${check.detail}`);
  }

  const failures = checks.filter((check) => check.status === 'fail').length;
  if (failures > 0) log.line(`${failures} of ${checks.length} checks failed`);

  return failures > 0 ? 1 : 0;
}

export async function run(argv: readonly string[]): Promise<number> {
  const [command, ...rest] = argv;

  // Checked before the dispatch, because `<command> --help` is what people type.
  if (
    command === undefined ||
    command === 'help' ||
    argv.includes('--help') ||
    argv.includes('-h')
  ) {
    log.line(USAGE);
    return 0;
  }

  switch (command) {
    case 'fetch-model': {
      const flags = parseFlags(command, rest, [
        '--force',
        '--cache-dir',
        '--ios-only',
        '--android-only',
      ]);
      if (flags.positionals.length > 1) {
        throw new Error(
          `fetch-model takes one variant, got: ${flags.positionals.join(' ')}.\n\n${USAGE}`,
        );
      }
      return fetchModelCommand(flags);
    }

    case 'doctor': {
      const flags = parseFlags(command, rest, []);
      if (flags.positionals.length > 0) {
        throw new Error(`doctor takes no arguments, got: ${flags.positionals.join(' ')}.`);
      }
      return doctorCommand();
    }

    case 'clear-cache': {
      const flags = parseFlags(command, rest, ['--cache-dir']);
      if (flags.positionals.length > 0) {
        // `clear-cache full` reads as clearing one variant, which it does not do.
        throw new Error(
          `clear-cache takes no arguments, got: ${flags.positionals.join(' ')}. It clears the ` +
            `whole cache.`,
        );
      }

      const removed = await clearCache(flags.cacheDir);
      log.line(
        removed.length === 0
          ? `nothing to clear in ${flags.cacheDir}`
          : `cleared ${removed.join(', ')} from ${flags.cacheDir}`,
      );
      return 0;
    }

    default:
      throw new Error(`Unknown command "${command}".\n\n${USAGE}`);
  }
}
