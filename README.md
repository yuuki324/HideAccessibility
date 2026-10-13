# HideAccessibility

让选定的应用以为本机没有开启无障碍服务。

模块在 `system_server` 里拦截无障碍状态的读取路径，按 **Binder 调用方**判断该不该撒谎：
系统自己的无障碍功能、以及未被选中的应用完全不受影响。

**简体中文** | [English](README.en.md)

[![Build](https://github.com/yuuki324/HideAccessibility/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/yuuki324/HideAccessibility/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

> **这不是上游的官方版本，而是它的衍生作品（Derivative Work）。**
> 本仓库是 [Nitsuya/DoNotTryAccessibility](https://github.com/Nitsuya/DoNotTryAccessibility)
> 的 libxposed API 102 移植版：包名、应用名、版本线都独立，框架层从 legacy Xposed API +
> YukiHookAPI 换成了 libxposed API 102，UI 与业务逻辑做了大量重构与重写。
>
> **作者与版权**：原作者 **Nitsuya**。本项目在查看原项目代码、采用其部分架构设计的基础上
> 完成，依据上游的 **GPL-3.0** 许可，本项目同样以 GPL-3.0 发布。上游版权与衍生范围见
> [`NOTICE`](NOTICE)，源文件头部也保留了双版权声明。
>
> **当前维护者**：[@yuuki324](https://github.com/yuuki324)。
>
> **为什么 Contributors 列表里没有原作者**：本仓库采用全新初始化的 Git 仓库，而非从上游
> fork，因此提交历史中不包含原作者的提交，GitHub 的 Contributors 只统计本仓库的提交者。
> 这是历史记录形式上的差异，**不代表来源声明缺失**——原作者的版权已在源文件头部、
> `NOTICE` 与本段中明确声明。特别感谢原作者的贡献。

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

### 签名

**仓库里不含任何签名密钥。** Android 的 debug key 是公开的（密码就是 `android`），
谁拿到都能签出一个"能覆盖安装到你机器上"的更新，所以不适合用来签发布版。

签名配置是**可选**的：在 `~/.gradle/gradle.properties`（不是在项目里）写入下面三行，
`assembleRelease` 就会产出正式签名的 APK：

```properties
hideaccessibility.storeFile=E:/path/to/your/hideaccessibility-release.jks
hideaccessibility.storePassword=<你的密码>
hideaccessibility.keyAlias=hideaccessibility
```

没配置时，release 构建**不签名**——任务仍然成功（便于 CI 只做编译验证），但产出的 APK
无法安装。这是刻意的：测试产物不该被误当成发布版。

生成自己的密钥（只需一次，之后请妥善备份；**丢失后无法再发布覆盖安装的更新**）：

```bash
keytool -genkeypair -v \
  -keystore hideaccessibility-release.jks \
  -alias hideaccessibility \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=yuuki324, OU=HideAccessibility, O=yuuki324, C=CN"
```

CI 在每次 push 时构建（见 [`.github/workflows/build.yml`](.github/workflows/build.yml)）。
CI 上没有密钥，因此它只验证**能否构建**，不产出可分发的 APK。

推送一个 tag（如 `v1.0.0`）会触发 [`.github/workflows/release.yml`](.github/workflows/release.yml)：
它用仓库 Secrets 里的密钥签名，并把 APK 附到 GitHub Release 上。需要在仓库
Settings → Secrets and variables → Actions 里配置两项：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | 密钥文件的 base64：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("your.jks"))` |
| `KEYSTORE_PASSWORD` | 密钥库密码 |

没配置这两项时，该 workflow **会主动失败**——发布一个未签名或 debug 签名的版本，
比不发布更糟。

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
