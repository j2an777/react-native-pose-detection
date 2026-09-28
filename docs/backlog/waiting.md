# Waiting on others

Items blocked on another project. Each says what to watch and what to do once it moves.

- [ ] **COMPAT-1 · P1 · Expo SDK 58 and React Native 0.88.**
  - **Now:** SDK 58 is in preview (`expo@next` is 58.0.0-preview.8 as of 2026-09-28) and pairs with
    React Native 0.88, which is still a release candidate. Expo skipped React Native 0.87.
  - `doctor` already accepts a correct pairing, because it reads the expected React Native from the
    installed `expo`'s own `bundledNativeModules.json`.
  - **When SDK 58 is stable:**
    1. Check the Expo APIs the native code calls on Expo's `sdk-58` branch. `NativeArrayBuffer`
       matters most, since it set the SDK 56 floor.
    2. Build the package into fresh SDK 58 apps, from the default template and from `bare-minimum`,
       on both platforms.
    3. Add `'0.88': '58'` to `EXPO_SDK_FOR_REACT_NATIVE` in
       [checks.ts](../../packages/core/plugin/src/checks.ts), with a test.
    4. Update the supported versions in the README, `guides/installation.md` and `SECURITY.md`.
  - Moving the example apps to SDK 58 is a separate step, and it needs a device run.
- [ ] **CI-2 · P2 · GitHub's `ubuntu-latest` moves to Ubuntu 26 from 2026-10-19.**
  - Every run carries the notice
    ([actions/runner-images#14748](https://github.com/actions/runner-images/issues/14748)). Watch
    the first runs after that date. If a Linux job breaks, pin `ubuntu-24.04` until it is fixed.
- [ ] **SEC-1 · P3 · Advisories in tooling that never ships.**
  - `npm audit` and GitHub's dependency alerts report advisories in development dependencies. None
    of them reaches the published package, and none can be fixed from this repository:

    | Dependency | Pulled in by | First fixed in |
    | --- | --- | --- |
    | `image-size` 1.2.1 | metro 0.84.4 | 2.0.3 |
    | `fast-xml-parser` 4.5.7 | `@react-native-community/cli` 20.1.0 | 5.7.0 |
    | `uuid` 7.0.3 | `xcode` 3.0.1, through `@expo/config-plugins` | 11.1.1 |
    | `concurrent-ruby` and `activesupport` | `example/bare/Gemfile` | 1.3.7 and 7.2.3.1 |

  - `example/bare/Gemfile` is a copy of React Native's template. The template pins
    `concurrent-ruby < 1.3.4` and allows `activesupport` from 6.1.7.5, still does so on its `main`
    branch, and CI never runs bundler.
  - **Once it moves:** check again whenever the examples change their Expo (UP-3 in
    [native.md](./native.md)) or React Native version.
    - A lockfile refresh that stays inside the current version ranges is safe.
    - Forcing a new major version under metro or Expo is not.
- [ ] **UPSTREAM-1 · P3 · Heading links on React Native Directory.**
  - The directory's README renderer counts each heading twice. A link to `#upgrading-from-0-1-0`
    points to `#upgrading-from-0-1-0-2` and goes nowhere.
  - The changelog works around it by naming its upgrade section in bold instead of linking to it.
  - The fix belongs in `components/Package/MarkdownContentBox/utils.ts` in
    react-native-community/directory, and it would fix every package on the site.
