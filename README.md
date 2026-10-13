# HideAccessibility

让选定的应用以为本机没有开启无障碍服务。

模块在 `system_server` 里拦截无障碍状态的读取路径，按 **Binder 调用方**判断该不该撒谎：
系统自己的无障碍功能、以及未被选中的应用完全不受影响。

**简体中文** | [English](README.en.md)

> **这不是上游的官方版本，而是它的一个移植分支。** 本仓库是
> [Nitsuya/DoNotTryAccessibility](https://github.com/Nitsuya/DoNotTryAccessibility)（GPL-3.0）
> 的 libxposed API 102 移植版：包名、应用名、版本线都独立，框架层从 legacy Xposed API +
> YukiHookAPI 换成了 libxposed API 102。
>
> **上游作者与版权**：原作者为 **Nitsuya**。本项目作为衍生作品，沿用上游的 **GPL-3.0**
> 许可，上游版权与衍生范围见 [`NOTICE`](NOTICE)。
>
> **当前维护者**：[@yuuki324](https://github.com/yuuki324)。
>
> **为什么 Contributors 列表里没有原作者**：本仓库并非从上游 fork 而来，git 提交历史
> 中不包含上游的提交，因此 GitHub 的 Contributors 只统计本仓库的提交者。这是历史记录
> 形式上的差异，**不代表来源声明缺失**——上游项目、作者和衍生范围已在 `NOTICE`
> 与本段中明确声明。

## 功能

- **按应用隐藏无障碍状态**：勾选的应用调用 `AccessibilityManager.isEnabled()`、
  `getEnabledAccessibilityServiceList()`，或读取 `Settings.Secure.ACCESSIBILITY_ENABLED` /
  `ENABLED_ACCESSIBILITY_SERVICES` 时，拿到的是"无障碍没开"。
- **只骗调用方**：判断依据是 `Binder.getCallingUid()`，不是全局开关。
- **设备状态自检**：主界面直接显示两条被拦截通道的实测值，勾选自己就能验证 hook 是否生效。
- **多语言**：简体中文 / 繁體中文 / English，Android 13+ 可在应用内切换。

## 环境要求

| 项目 | 版本 |
|---|---|
| Android | 8.1（API 27）及以上 |
| 框架 | 支持 **libxposed API 102** 的框架，如 LSPosed 2.x |
| 构建 | JDK 17、Android SDK（compileSdk 34） |

## 构建

标准 Gradle 工程，推荐直接用 Android Studio 打开，或命令行：

```bash
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/`。

Release 构建使用仓库内附的 debug keystore（`app/debug.keystore`）签名，方便直接安装测试。
**正式分发前请替换为你自己的 keystore。** CI 会在每次 push 时构建并上传 APK
（见 [`.github/workflows/build.yml`](.github/workflows/build.yml)）。

## 安装与使用

1. 安装 APK，在框架（LSPosed）中启用本模块。
2. **作用域勾选模块推荐的那一项**（`scope.list` 里是 `system`）。
   不同框架/ROM 上它的显示名不一样，常见为「系统」，也可能叫「系统框架」或直接显示 `system`。
   漏了它模块会**静默失效**：界面照样显示已激活，但什么都没拦住。
   首次启用后**需要重启**。
3. 打开模块 →「管理被隐藏的应用」→ 勾选目标应用。
4. 启动（或重启）目标应用，它会认为无障碍未开启。

> **验证是否生效**：主界面「当前设备实际状态」区显示的就是被拦截的两条通道。
> 把模块**自己**的包名勾上，该区域会显示"未启用 / 列表为空"；取消勾选则显示设备真值。
> 两次读数能随勾选变化，说明 hook 正常工作。

## 语言

主界面「语言」一行可切换：`跟随系统 / Follow system`、`简体中文`、`繁體中文`、`English`。
走 Android 13+ 的 per-app language（`LocaleManager`），只影响本应用。
系统低于 Android 13 时该入口置灰，不做假开关。

每个选项在任何界面语言下都保留一个能认出来的词（`Language` / `Follow system` 或语言名本身），
这样即使界面落到看不懂的语言，也能找到出口切回来。

## 调试

加载与命中日志走 logcat，TAG 为 `HideAccessibility`：

```bash
adb logcat -s HideAccessibility:V HideAccessibility.Diagnostics:V HideAccessibility.Hooks:V
```

- `HideAccessibility.Diagnostics` —— hook 安装结果、配置通道状态、当前生效的隐藏列表；
- `HideAccessibility.Hooks` —— 首次命中时记录调用方包名；
- `HideAccessibility.PackageNames` —— UID→包名解析路径（解析失败时不拦截，只告警）。

**读 `system_server` 的日志需要 root**，非 root 的 `adb shell` 拿不到。

## 这个模块能做什么、不能做什么

**能**：让选定应用读不到无障碍状态。

**不能**：

- 不隐藏无障碍服务进程本身；
- 不阻止应用用**其他信道**判断无障碍是否开启（例如带 root 的应用可以挂载 `debugfs`
  并检查挂载点的 SELinux label，这条路径与本模块无关）；
- 不影响系统自己的无障碍功能。

> ⚠️ **能力是双用的。** 本模块无法区分下面两种用途：不允许应用窥探你开启了哪些无障碍服务
> （正当的隐私保护），以及把**恶意应用自己注册的无障碍服务**从检测里藏起来（滥用）。
> 勾选名单由使用者自己维护，**勾错等于给对应应用开了口子**。
> 分发时请如实描述它是什么，不要包装成无害小工具。

## 许可

**GPL-3.0** —— 见 [`LICENSE`](LICENSE)（上游项目的许可证，原样保留）与
[`NOTICE`](NOTICE)（上游版权人与衍生范围声明）。

本项目是上游 GPL-3.0 项目的**衍生作品**，因此同样以 GPL-3.0 发布。这意味着它是
**传染性许可（copyleft）**：再分发（无论是否修改）都必须沿用同一许可，并提供对应源码。

运行期依赖为 Apache-2.0（`io.github.libxposed:api` / `service`、Kotlin 标准库）。
Apache-2.0 与 GPL-3.0 兼容——组合后可整体按 GPL-3.0 分发，各依赖自身的 Apache-2.0 声明
保留在它自己的 `LICENSE` 中。
