# HideAccessibility

Make selected apps believe accessibility is turned off on this device.

The module intercepts the accessibility-state read paths inside `system_server` and decides
whether to lie based on the **Binder caller**: the system's own accessibility features and
any app you did not select are unaffected.

**English** | [简体中文](README.md)

> **This is not the upstream project — it is a derivative work of it.** It is a libxposed
> API 102 port of [Nitsuya/DoNotTryAccessibility](https://github.com/Nitsuya/DoNotTryAccessibility):
> the package name, app name and version line are independent, the framework layer was moved
> from the legacy Xposed API + YukiHookAPI to libxposed API 102, and the UI and business logic
> were largely restructured.
>
> **Authorship and copyright**: the original author is **Nitsuya**. This project was written
> after reading the upstream source and adopts parts of its architecture, so under the
> upstream **GPL-3.0** license this project is released under GPL-3.0 as well. The upstream
> copyright and the scope of derivation are recorded in [`NOTICE`](NOTICE), and every source
> file keeps a dual copyright header.
>
> **Maintainer**: [@yuuki324](https://github.com/yuuki324).
>
> **Why the upstream author is not in the Contributors list**: this repository was created
> fresh rather than forked from upstream, so its git history contains none of the upstream
> commits and GitHub counts only this repository's committers. That is a difference in how
> history is recorded — it does **not** mean attribution is missing: the upstream copyright is
> stated in the source headers, `NOTICE` and above. Thanks to the original author for their work.

## Features

- **Per-app accessibility hiding.** When a selected app calls `AccessibilityManager.isEnabled()`
  or `getEnabledAccessibilityServiceList()`, or reads `Settings.Secure.ACCESSIBILITY_ENABLED` /
  `ENABLED_ACCESSIBILITY_SERVICES`, it gets "accessibility is off".
- **Only the caller is fooled.** The decision is based on `Binder.getCallingUid()`, not a global switch.
- **On-device self-check.** The main screen shows live values from both intercepted paths, so
  selecting the module itself is enough to verify the hooks work.
- **Localised UI.** Simplified Chinese, Traditional Chinese and English, switchable in-app on Android 13+.

## Requirements

| | |
|---|---|
| Android | 8.1 (API 27) or newer |
| Framework | Any framework supporting **libxposed API 102**, e.g. LSPosed 2.x |
| Build | JDK 17, Android SDK (compileSdk 34) |

## Building

This is a standard Gradle project. Open it in Android Studio, or from the command line:

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

The release build is signed with the debug keystore bundled in the repository
(`app/debug.keystore`) so the artifact is installable out of the box.
**Replace it with your own keystore before distributing widely.**
CI builds the APK on every push — see [`.github/workflows/build.yml`](.github/workflows/build.yml).

## Install and use

1. Install the APK and enable the module in your framework (LSPosed).
2. **Tick the scope the module recommends** (`scope.list` contains `system`).
   Its display name differs between frameworks and ROMs — commonly "System", sometimes
   "System Framework", or the literal `system`. Missing it makes the module fail
   **silently**: the UI still reports "active" while nothing is intercepted.
   **A reboot is required** after enabling it for the first time.
3. Open the module → "Manage hidden apps" → select the target apps.
4. Start (or restart) the target app. It will believe accessibility is off.

> **Verify it works:** the "Current device state" section on the main screen shows the two
> intercepted paths. Tick the module's **own** package name and that section will report
> "not enabled / empty list"; untick it and you get the real device values.
> If both readings follow the tick, the hooks are working.

## Language

The "Language" row switches between `跟随系统 / Follow system`, `简体中文`, `繁體中文` and
`English`. It uses Android 13+ per-app language (`LocaleManager`) and affects this app only.
On Android 12 and below the entry is greyed out rather than offering a switch that would not work.

Every option keeps a recognisable word in any UI language (`Language` / `Follow system`, or the
language's own name), so a user who lands in a language they cannot read can still find the way out.

## Debugging

Loading and hit logs go to logcat under the tag `HideAccessibility`:

```bash
adb logcat -s HideAccessibility:V HideAccessibility.Diagnostics:V HideAccessibility.Hooks:V
```

- `HideAccessibility.Diagnostics` — hook install result, config channel state, the active hidden list
- `HideAccessibility.Hooks` — the calling package, logged on the first hit
- `HideAccessibility.PackageNames` — which path resolved UID → package name (a failed resolution
  means "do not intercept", with a warning)

**Reading `system_server` logs requires root**; a non-root `adb shell` cannot see them.

## What this module can and cannot do

**Can:** make selected apps unable to read the accessibility state.

**Cannot:**

- hide the accessibility service process itself;
- stop an app from using **other channels** to detect accessibility — for example an app with
  root can mount `debugfs` and inspect the mount's SELinux label. That path is unrelated to
  this module;
- affect the system's own accessibility features.

> ⚠️ **The capability is dual-use.** This module cannot tell these two cases apart: stopping an
> app from prying into which accessibility services you have enabled (legitimate privacy), and
> hiding a **malicious app's own accessibility service** from detection (abuse).
> The hidden list is maintained by the user, so **ticking the wrong entry opens a hole for that app**.
> When distributing this, describe it honestly — do not market it as a harmless utility.

## License

**GPL-3.0** — see [`LICENSE`](LICENSE) (the upstream project's license, kept as-is) and
[`NOTICE`](NOTICE) (upstream copyright holder and the scope of derivation).

This project is a **derived work** of a GPL-3.0 project and is therefore released under
GPL-3.0 as well. That makes it **copyleft**: redistributing it, with or without changes,
requires passing on the same license and making the corresponding source available.

Runtime dependencies are Apache-2.0 (`io.github.libxposed:api` / `service`, Kotlin standard
library). Apache-2.0 is compatible with GPL-3.0 — the combination may be distributed under
GPL-3.0, and the Apache-2.0 notices are preserved in each dependency's own `LICENSE`.
