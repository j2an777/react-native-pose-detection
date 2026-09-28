# CLI

For bare React Native, where there's no config plugin.

```bash
npx react-native-pose-detection <command>
```

It runs on Node 22.22.1 or newer, the same floor the package declares in `engines`. It has no
dependencies of its own, so `npx` fetches nothing beyond the package you already installed.

With no command, `help`, `--help` or `-h`, it prints its usage and exits `0`, and `--help`
anywhere on the line wins over the command. Anything a command does not take, an unknown flag,
another command's flag or a stray argument, is an error rather than something quietly ignored.
Errors exit `1` with the reason on stderr.

## `fetch-model <variant>`

```bash
npx react-native-pose-detection fetch-model full
```

Downloads, verifies, and installs into both native projects: the config plugin's model steps,
including removing any previously installed model. It does not declare the camera permission,
which a bare app does itself, see [installation](../installation.md#bare-react-native).

| Flag | Notes |
| --- | --- |
| `--force` | re-download even on a cache hit |
| `--cache-dir <path>` | override the cache location |
| `--ios-only` / `--android-only` | install into one platform |

```text
› model "full" not in cache
› downloading pose_landmarker_full.task (9.0 MB)…
› sha256 ✓
› copied → android/app/src/main/assets/pose_landmarker_full.task
› copied → ios/MyApp/Resources/pose_landmarker_full.task
› registered → ios/MyApp.xcodeproj/project.pbxproj
```

Before any of that it checks the installed Expo SDK against React Native, the check `doctor` opens
with. A mismatch, or no `expo` at all, is a warning that names the fix, and the install goes ahead:

```text
› warning: expo 57 is built for React Native 0.86, found 0.85.3: npm i expo@56
```

A platform with no native project is skipped with a warning. The command exits `0` when at least
one platform received the model and `1` when none did, such as `--ios-only` in a project with no
`ios/*.xcodeproj`.

On Android the file goes into the module React Native builds: `android/app`, or the one
`react-native.config.js` names with `project.android.sourceDir` and `project.android.appName`,
found the way React Native's own CLI finds it. `doctor` checks the same place.

On iOS the file is also added to your app target, so it ends up in the bundle without a trip
through Xcode. Switching variants unregisters the old one in the same pass.

That step needs `expo` to be resolvable, which it is in any project using this package, since
Expo Modules are how the native code is linked. If it somehow isn't, the file is still copied
and you get a one-line instruction instead.

## `doctor`

```bash
npx react-native-pose-detection doctor
```

Checks the things that actually break:

```text
› ✓ Expo SDK for React Native   expo 57 with react-native 0.86.2
› ✓ model installed             android/app/src/main/assets/pose_landmarker_full.task
› ✓ SHA-256 matches manifest    pose_landmarker_full.task
› ✓ model installed             ios/MyApp/Resources/pose_landmarker_full.task
› ✓ SHA-256 matches manifest    pose_landmarker_full.task
› ✓ model in the app target     pose_landmarker_full.task is a build resource
› – minSdkVersion 24            resolved by the Expo Gradle plugin, not readable from the project
› ✓ iOS deployment target 16.4  found 16.4
› ✓ android.permission.CAMERA   AndroidManifest.xml
› ✗ NSCameraUsageDescription    missing from Info.plist
› 1 of 10 checks failed
```

| Mark | Meaning |
| --- | --- |
| `✓` | checked and correct |
| `✗` | checked and wrong. Exits `1` |
| `–` | could not be checked. **Not** a failure |

The summary line appears only when something failed. A clean run prints the checks and exits `0`.

`Expo SDK for React Native` comes first. Each Expo SDK is built against one React Native, which
its `expo` package names, and npm installs any pairing, since `expo` accepts any React Native:
`expo@57` on React Native 0.85 installs cleanly and then fails to compile for Android. The check
compares minor versions. A mismatch is a `✗` naming the SDK to install, `npm i expo@56` for
React Native 0.85 and `npm i expo@57` for 0.86, and so is a missing `expo`, since it is what links
this package. So is a version below what this package needs, React Native 0.85 and Expo SDK 56,
which yarn, pnpm and `--legacy-peer-deps` install over with only a warning. With no
`react-native` installed, or an `expo` that names no React Native, there is nothing to compare,
and that is a `–`.

The `–` state matters. An Expo prebuild resolves `minSdkVersion` inside a Gradle plugin, so no
file in the project holds the number. Reporting that as a failure would train you to ignore the
output, which is worse than not checking it. Bare React Native writes the value into
`android/build.gradle` and `expo-build-properties` into `android/gradle.properties`, and it gets
checked in either.

`android.permission.CAMERA` never fails. This package declares the permission in its own
manifest and the Android manifest merger adds it to your app, so an app manifest without it is
still correct; `doctor` says where the permission came from rather than calling a working project
broken. `NSCameraUsageDescription` does fail, because iOS has no equivalent merge.

Two models in one directory is a `✗`. That's the failure this command exists to catch, because
which one wins at load time is not something your app gets to decide. So is a model this package
does not install, such as a renamed `pose_landmarker_custom.task`: the runtime loads any
`pose_landmarker_*.task`, and there is no checksum to hold it to. On iOS, a model beside the
sources in `ios/<App>/`, left by an older install or copied by hand, is bundled too, and gets a
`✗` of its own, `one model on iOS`.

`model in the app target` is the second one worth knowing about. A `.task` sitting in
`ios/<App>/Resources` that no target builds is never copied into the bundle, and the app fails at
runtime with `MODEL_NOT_FOUND` even though the file is plainly there. `fetch-model` produces
exactly that state on purpose when `expo/config-plugins` cannot be resolved, so it has to be
checked rather than assumed.

A project with only `android/` or only `ios/` is checked for that platform, and the other is a
`–`: an app built for one platform has nothing to fix on the other. With neither directory there
is nothing to check, and that is a `✗`, as is an `ios/` with no `.xcodeproj` in it.

`doctor` takes no flags and no arguments, and says so rather than ignoring one. `doctor
--cache-dir /tmp/x` reads as a request the tool honors, and it never was one.

Run it first when reporting a setup bug.

## `clear-cache`

```bash
npx react-native-pose-detection clear-cache
```

Takes `--cache-dir` too. The next `fetch-model` or prebuild downloads again.

It clears the whole cache, every variant, plus the `.part` and `.lock` sidecars the downloader
leaves. A lock another process is currently holding is left alone, because deleting it would let
a second download start into the same path. It takes no variant argument: `clear-cache full`
reads as clearing one model and it never did that, so it is an error rather than a surprise.
