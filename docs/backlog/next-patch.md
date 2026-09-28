# Next patch

Nothing here changes a native binary, so none of it needs a phone. What is merged and not released
yet is under `## Unreleased` in [the changelog](../../packages/core/CHANGELOG.md).

## Repository

Nothing here changes a published file, so it can land at any time.

- [ ] **CI-1 · P2 · A cancelled run turns the README's CI badge red.**
  - **Today:** `cancel-in-progress: true` in [ci.yml](../../.github/workflows/ci.yml). When a push
    lands within about ten minutes of the previous one, it cancels the previous run. The badge
    shows the newest finished run on `main`, draws a cancelled run as failing, and stays that way
    until the next run finishes.
  - **Fix:** `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`. Every push to `main`
    then runs to completion, and a pull request still drops its superseded runs.
  - **Check:** push twice in quick succession to a fork's `main`, and both runs finish.
- [ ] **CI-3 · P2 · Assert 16 KB page alignment in the APK.**
  - **Why:** Google Play requires 16 KB page support from apps that target Android 15 or newer.
    The native libraries this package brings come from MediaPipe. In 0.10.35,
    `libmediapipe_tasks_jni.so` is aligned on `arm64-v8a` and `x86_64` (checked 2026-09-28), and a
    MediaPipe upgrade (UP-1 in [native.md](./native.md)) must not lose that silently.
  - **Fix:** add a step next to the ABI assertion in the `android-expo` and `android-bare` jobs that
    fails when a 64-bit library in the APK has a `LOAD` segment aligned below 16 KB. Android's
    `check_elf_alignment.sh` shows how to read the segments.
  - **Check:** the step passes on today's APK.
- [ ] **CI-4 · P2 · Link-check `docs/development-plan.md`.**
  - **Today:** `exclude_path` in [lychee.toml](../../lychee.toml) lists `"docs/development"` to skip
    the private working folder. lychee reads that entry as a regular expression, so it also skips
    `docs/development-plan.md`, locally and in CI.
  - **Fix:** `"docs/development/"`.
  - **Check:** `npm run lint:links` now checks the development plan. All 22 of its links passed on
    2026-09-28.
- [ ] **EX-8 · P2 · Remove `newArchEnabled` from the Expo example.**
  - `"newArchEnabled": true` in [app.json:7](../../example/expo/app.json#L7). SDK 57 runs only the
    new architecture, and `npx expo-doctor` reports the key as obsolete.
  - **Check:** CI's two Expo cells build as before.
- [ ] **EX-9 · P3 · Review the monorepo Metro settings in the Expo example.**
  - `watchFolders`, `nodeModulesPaths` and `disableHierarchicalLookup` in
    [example/expo/metro.config.js](../../example/expo/metro.config.js). `npx expo-doctor` flags
    `disableHierarchicalLookup`, and Expo's docs say `expo/metro-config` has set up monorepos on its
    own since SDK 52.
  - The bare example keeps its copy, because it builds on `@react-native/metro-config`, which does
    no monorepo setup.
  - **Fix:** remove the three settings if Metro still resolves `react-native-pose-detection` from
    the workspace without them.
  - **Check:** start Metro in `example/expo`, load the app on a simulator, then run CI.
