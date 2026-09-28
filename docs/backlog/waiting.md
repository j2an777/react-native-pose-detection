# Waiting on others

Items blocked on another project. Each says what to watch and what to do once it moves. Last
checked 2026-09-28.

- [ ] **COMPAT-1 · P1 · Expo SDK 58 and React Native 0.88.**
  - **Now:** SDK 58 is in preview (`expo@next` is 58.0.0-preview.8) and pairs with React Native
    0.88, still a release candidate (0.88.0-rc.3). Expo skipped React Native 0.87.
  - **Already in place:**
    - `doctor` accepts a correct pairing, because it reads the expected React Native from the
      installed `expo`'s own `bundledNativeModules.json`.
    - `ExpoModulesCore` 58 still needs iOS 16.4, so the podspec's floor holds.
  - **Can start now:** Expo's `sdk-58` branch exists. Check the Expo APIs the native code calls
    against it, `NativeArrayBuffer` above all, since that API set the SDK 56 floor.
  - **When SDK 58 is stable:**
    1. Build the package into fresh SDK 58 apps, from the default template and from `bare-minimum`,
       on both platforms.
    2. Add `'0.88': '58'` to `EXPO_SDK_FOR_REACT_NATIVE` in
       [checks.ts](../../packages/core/plugin/src/checks.ts), with a test.
    3. Update the supported versions in the README, `guides/installation.md` and `SECURITY.md`.
  - Moving the example apps to SDK 58 is a separate step, and it needs a device run. It also
    clears the `image-size` advisories (SEC-1).
- [ ] **CI-2 · P2 · GitHub's `ubuntu-latest` moves to Ubuntu 26.04 from 2026-10-19.**
  - The rollout takes several weeks and completes by 2026-11-19
    ([actions/runner-images#14748](https://github.com/actions/runner-images/issues/14748)). Watch
    the Linux jobs in that window. If one breaks, pin `ubuntu-24.04` until it is fixed.
- [ ] **SEC-1 · P3 · Advisories in tooling that never ships.**
  - `npm audit` and GitHub's dependency alerts report advisories in development dependencies. None
    of them reaches the published package:

    | Dependency | Pulled in by | Clears when |
    | --- | --- | --- |
    | `image-size` 1.2.1 | metro 0.84 | the examples move to SDK 58: its React Native 0.88 uses metro 0.87, which dropped it (COMPAT-1) |
    | `fast-xml-parser` 4.5.7 | React Native CLI 20.1.0 | the bare example moves to CLI 20.2.0 (EX-10 in [next-patch.md](./next-patch.md)) |
    | `uuid` 7.0.3 | `xcode` 3.0.1, through `@expo/config-plugins` | `xcode` moves past `uuid` 7; SDK 58's config plugins still use it |
    | `concurrent-ruby` and `activesupport` | `example/bare/Gemfile` | React Native's template changes its pins |

  - `example/bare/Gemfile` is a copy of React Native's template. The template pins
    `concurrent-ruby < 1.3.4` and allows `activesupport` from 6.1.7.5, and it still does so on its
    `main` branch. CI never runs bundler.
  - **Rule:** a lockfile refresh that stays inside the current version ranges is safe. Forcing a
    new major version under metro or Expo is not.
- [ ] **UPSTREAM-1 · P3 · Heading links on React Native Directory.**
  - The directory's README renderer counts each heading twice. A link to `#upgrading-from-0-1-0`
    points to `#upgrading-from-0-1-0-2` and goes nowhere.
  - The changelog works around it with absolute links to GitHub release pages.
  - The fix belongs in `createSlugger` in `components/Package/MarkdownContentBox/utils.ts` in
    react-native-community/directory. It would fix every package on the site, and no issue was
    open for it as of 2026-09-28.
