# Installation

Published to npm as [`react-native-pose-detection`](https://www.npmjs.com/package/react-native-pose-detection).
Both platforms are complete.

## Requirements

| | |
| --- | --- |
| React Native | 0.85+ |
| Expo SDK | 56+ (dev client or EAS Build) |
| iOS | 16.4+, which Expo's `ExpoModulesCore` requires from SDK 56 |
| Android | API 24+ |
| Architecture | new. React Native 0.82 removed the legacy one, so there is nothing to choose |

**Expo Go is not supported** and never will be. This package contains native code.

## Expo

```bash
npm i react-native-pose-detection
```

```json
{
  "expo": {
    "plugins": [
      ["react-native-pose-detection", {
        "model": "full",
        "cameraPermissionText": "We use the camera to analyze your movement."
      }]
    ]
  }
}
```

```bash
npx expo prebuild
npx expo run:ios       # or run:android
```

Full plugin options: [config plugin reference](./reference/config-plugin.md).

## Bare React Native

```bash
npm i react-native-pose-detection expo@56   # expo@57 on React Native 0.86
npx react-native-pose-detection fetch-model full
cd ios && pod install                       # after the wiring below
```

`expo` is not a typo and it does not turn your app into an Expo app. This package is built with
the Expo Modules API, and that API's autolinking is what finds the native module. You need the
`expo` package for autolinking. You do not need the config plugin, `app.json`, or prebuild.

Each Expo SDK is built against one React Native, so take the one that matches yours: `expo@56`
for React Native 0.85 and `expo@57` for 0.86. npm cannot catch a mismatch, since `expo` accepts
any React Native, and a mismatch shows up later: `expo@57` on React Native 0.85 fails to compile
for Android. So `fetch-model` warns about one and installs anyway, and `doctor` fails on it, both
naming the version to install.

### Wiring Expo modules into an existing app

The documented tool for this is `npx install-expo-modules@latest`, and on a recent React Native
it will not run: version 0.16.0 knows Expo SDK 53 and React Native 0.78 at the newest, and stops
with `Unable to find compatible Expo SDK version`. Until it catches up, the edits are below.
A working copy of all of them is [`example/bare`](../example/bare), which CI builds on every push.

`android/settings.gradle`, above `include ':app'`, merged into the `pluginManagement`, `plugins`
and `extensions.configure` blocks the template already has rather than added beside them:

```groovy
pluginManagement {
  def expoPluginsPath = new File(
    providers.exec {
      workingDir(rootDir)
      commandLine("node", "--print", "require.resolve('expo-modules-autolinking/package.json', { paths: [require.resolve('expo/package.json')] })")
    }.standardOutput.asText.get().trim(),
    "../android/expo-gradle-plugin"
  ).absolutePath
  includeBuild(expoPluginsPath)
}

plugins { id("expo-autolinking-settings") }

extensions.configure(com.facebook.react.ReactSettingsExtension) { ex ->
  ex.autolinkLibrariesFromCommand(expoAutolinking.rnConfigCommand)
}
expoAutolinking.useExpoModules()
expoAutolinking.useExpoVersionCatalog()
```

`android/build.gradle`, next to the React Native line:

```groovy
apply plugin: "expo-root-project"
```

`MainApplication.kt`, so Expo modules are registered with the host:

```kotlin
import expo.modules.ExpoReactHostFactory

override val reactHost: ReactHost by lazy {
  ExpoReactHostFactory.getDefaultReactHost(applicationContext, PackageList(this).packages)
}
```

`MainActivity.kt`, so Expo modules see the activity callbacks:

```kotlin
import expo.modules.ReactActivityDelegateWrapper

override fun createReactActivityDelegate(): ReactActivityDelegate =
    ReactActivityDelegateWrapper(
        this,
        BuildConfig.IS_NEW_ARCHITECTURE_ENABLED,
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled),
    )
```

### iOS

`ios/<App>/Info.plist`. There is no manifest merging on iOS, so this key has to be yours:

```xml
<key>NSCameraUsageDescription</key>
<string>We use the camera to analyze your movement.</string>
```

Deployment target 16.4 or higher, because that is what `ExpoModulesCore` requires from Expo SDK
56, and in two places, since React Native's template sets both to 15.1. In `ios/Podfile`, where a
lower target makes Expo's autolinking silently skip every one of its pods, which surfaces as
CocoaPods failing to find `ExpoModulesCore`:

```ruby
platform :ios, '16.4'
```

And on the app target in Xcode, **General → Minimum Deployments**, which is
`IPHONEOS_DEPLOYMENT_TARGET` in the project. Left at 15.1, the app stops compiling at
`compiling for iOS 15.1, but module 'Expo' has a minimum deployment target of iOS 16.4`.
`doctor` checks this one.

A bare app also needs Expo's autolinking in its `Podfile`, the counterpart of
`expo-autolinking-settings` in `settings.gradle`. `install-expo-modules` writes it for you on
React Native 0.78 and below; above that, add it by hand:

```ruby
require File.join(
  File.dirname(`node --print "require.resolve('expo/package.json', { paths: [process.cwd()] })"`),
  "scripts/autolinking"
)

target 'YourApp' do
  use_expo_modules!
  # ...
end
```

And `AppDelegate.swift`, so the modules that autolinking found are registered when React Native
starts, the counterpart of `ExpoReactHostFactory` on Android. With React Native's own factory the
app builds and links, then fails at launch with `Cannot find native module`:

```swift
internal import Expo
import React
import ReactAppDependencyProvider

@main
class AppDelegate: ExpoAppDelegate {
  var window: UIWindow?
  var reactNativeDelegate: ExpoReactNativeFactoryDelegate?
  var reactNativeFactory: RCTReactNativeFactory?

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    let delegate = ReactNativeDelegate()
    let factory = ExpoReactNativeFactory(delegate: delegate)
    delegate.dependencyProvider = RCTAppDependencyProvider()
    reactNativeDelegate = delegate
    reactNativeFactory = factory

    window = UIWindow(frame: UIScreen.main.bounds)
    factory.startReactNative(withModuleName: "YourApp", in: window, launchOptions: launchOptions)
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}

class ReactNativeDelegate: ExpoReactNativeFactoryDelegate {
  override func sourceURL(for bridge: RCTBridge) -> URL? {
    bridge.bundleURL ?? bundleURL()
  }

  override func bundleURL() -> URL? {
#if DEBUG
    RCTBundleURLProvider.sharedSettings().jsBundleURL(forBundleRoot: "index")
#else
    Bundle.main.url(forResource: "main", withExtension: "jsbundle")
#endif
  }
}
```

`internal import` matches how Expo's generated module provider imports it; a plain `import Expo`
fails to compile against it. [`example/bare`](../example/bare/ios/PoseExampleBare/AppDelegate.swift)
has the full file.

### Android

`android/build.gradle`:

```groovy
minSdkVersion = 24
```

The camera permission is **already declared** in this package's manifest and the merger adds it
to your app. Declaring it again is fine, and worth doing so your own manifest tells the truth
about what the app uses:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

Granting it at runtime is still yours to do. The native view reports `PERMISSION_DENIED` and
stops rather than prompting, because when to ask is a product decision.

## Verify

```bash
npx react-native-pose-detection doctor
```

## EAS Build

Works with no extra configuration, prebuild runs in the build container and the plugin fetches
the model there. Cache `~/.cache/react-native-pose-detection` to skip the download between builds.

## Android release builds

**Ship an AAB.** MediaPipe ships four ABI slices and a universal APK carries all of them:
10.1 MB for `arm64-v8a`, 7.1 MB for `armeabi-v7a`, 14.3 MB for `x86` and 12.5 MB for `x86_64`,
44.0 MB of native library against the 10.1 MB a phone actually loads. Measured from an
assembled APK on the pinned MediaPipe 0.10.35, see
[ADR 0007](../docs/adr/0007-pin-mediapipe-0-10-35.md).

If you must ship an APK, filter it in your **release** build only:

```groovy
android {
  defaultConfig {
    ndk { abiFilters "arm64-v8a" }
  }
}
```

Leave debug builds alone. Dropping `x86_64` there is what breaks the standard Android Studio
emulator on an Intel, Windows or Linux host.
