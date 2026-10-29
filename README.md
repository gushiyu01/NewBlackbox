# BlackBox - 虚拟引擎

<p align="center">
  <img src="assets/usage.gif" alt="BlackBox Banner" width="100%"/>
</p>

BlackBox 是一个虚拟引擎，可以在 Android 设备上克隆并运行应用，无需真正安装 APK。项目支持 Android 5.0 至 14.0+，并兼容多种架构（ARM64、ARMv7、x86）。

## 概述

本版本在原项目基础上修复了若干缺陷、提升了稳定性，并针对现代设备补充了 Android 14+ 的兼容性处理。

### 主要特性

*   **应用克隆**：同一应用可同时运行多个实例。
*   **沙箱环境**：进程相互隔离。
*   **无需 Root**：完全以普通应用权限运行。
*   **多架构支持**：同时支持 32 位与 64 位应用。
*   **设备伪装**：可修改虚拟应用看到的设备信息。
*   **虚拟定位**：可伪造 GPS 坐标。

## 环境要求

*   **Android 版本**：Android 5.0（API 21）及以上。
*   **内存**：建议 2GB 以上。
*   **架构**：ARMv7a、ARM64-v8a、x86。

## 构建说明

### 前置条件

*   Android Studio（Arctic Fox 或更新版本）
*   JDK 17
*   Android SDK 34+
*   NDK（版本 29.0.13846066）

### 从源码构建

```bash
# 克隆仓库
git clone https://github.com/your-repo/NewBlackbox.git
cd NewBlackbox

# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease
```

## 集成方式

如需在自己的项目中使用 BlackBox Core，请添加 AAR 依赖：

```gradle
dependencies {
    implementation fileTree(dir: "libs", include: ["*.aar"])
}
```

详细 API 文档见 `Docs.md`。

## 常见问题

*   **应用崩溃**：检查 logcat 中是否存在 UID 不匹配或权限错误。
*   **安装失败**：确认架构是否匹配，以及存储权限是否正常。
*   **Android 15**：请使用最新构建，已针对更严格的安全策略做过适配。

## 更新日志

### 4.2.0

#### 新增：容器内自带 MicroG

以前 GMS 支持只是把**宿主手机已安装**的 Google 服务克隆进容器。宿主没有 GMS 时入口直接禁用；宿主有 GMS 时，克隆进容器的 Play 服务启动即退出。

现在 GMS 管理页会在所选虚拟用户中安装 MicroG，固定版本 `v0.3.17.252432`，下载后校验大小与 SHA-256 才写入并安装。宿主是否安装 Google 服务不再影响容器，容器内的 Google 组件也不读取宿主状态。

- 组件：`com.google.android.gms`（MicroG Core）、`com.android.vending`（FakeStore 占位）
- 任意一步失败会回滚本次新装的包，不留半装状态
- 容器内的包信息与签名以容器内为准，不再被宿主同名包覆盖
- 为 MicroG 报告 Google 证书，作用范围仅限上述两个包

不保证 Play Integrity、Google 账号登录等依赖 Google 后端的认证能力。

#### 移除：GmsProxy

该代理试图获取名为 `gms` 的系统服务，Android 没有这个服务，因此从未安装过；它在认证失败时还会返回伪造的成功结果。两者都已移除。

### 4.1.0

本版本修复了两个导致沙箱应用在现代 Android 上无法使用的容器缺陷，堵上了崩溃日志上报中的一处安全问题，并移除了 VPN 功能。

#### 修复：克隆应用内点击设置页导致闪退

在克隆应用（例如 Muse / `com.facebook.aura`）中点击设置按钮，进程会立即退出，且不产生崩溃日志。

框架抛出的异常是 `SecurityException: Package X does not belong to <host uid>`。应用向 `RoleManager` 查询自己是否持有某个角色时，框架会先校验调用方 UID 是否拥有被查询的包名。虚拟应用运行在宿主 UID 下，却上报虚拟应用的包名，校验因此失败，设置页的初始化被中断。

新增 `IRoleManagerProxy` 在容器内接管 `role` 服务：`isRoleHeld` 与 `isRoleHeldAsUser` 返回 `false`，`isRoleAvailable` 返回 `true`，`getRoleHoldersAsUser` 返回空列表。在 Android 10 及以上注册进 `HookManager`。

#### 修复：所有网页报 net::ERR_CACHE_MISS

克隆应用内打开的网页——Muse、微信，任何应用——都会报 `net::ERR_CACHE_MISS`，但同一应用的 Java 网络请求却完全正常。

Chromium 在建立连接前，会按包名向 **`ActivityManager.checkPermission`** 查询 `INTERNET` 权限。克隆应用没有注册在系统真实的 PackageManager 中，这次查询返回 `DENIED`。Chromium 因此退化为只从缓存加载，而缓存为空就表现为 `ERR_CACHE_MISS`。Java 网络栈不经过这项检查，这正是“应用能联网、网页却全部失败”的原因。

现在由三个文件共同把网络权限报告为已授予，覆盖两条权限查询路径：

| 文件 | 改动 |
|---|---|
| `IActivityManagerProxy` | `checkPermission` 中增加网络权限分支，新增 `checkPermissionForDevice` 钩子 |
| `IPackageManagerProxy` | `checkPermission`、`checkSelfPermission`、`getPackageInfo` 三处 |
| `PackageManagerCompat` | 生成的 `PackageInfo` 中设置 `requestedPermissionsFlags` |

覆盖的权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_NETWORK_STATE`、`CHANGE_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`。

其中起决定作用的是 `ActivityManager` 那一处；只改 PackageManager 没有效果，因为 Chromium 不查询它。

#### 安全：崩溃日志不再经第三方中转

`LogSender` 原本会把完整的 logcat 以及设备指纹上传到 `logs-sender-api.vercel.app`，再由该服务转发到一个由他人控制的 Telegram 群。自动上报功能保留，但改为发送到用户在应用设置页中自行配置的机器人。本仓库是公开的，因此不硬编码任何凭据。

#### 移除：VPN 模式

VPN 开关原本每次启动都会弹出 VPN 连接授权，在本项目中并无实际用途。与之相关的 `ProxyVpnService`、清单中的服务声明、`BIND_VPN_SERVICE` 权限以及调用代码均已删除。

#### 构建

*   仅产出 universal APK，取消了按 ABI 拆分。
*   Release 使用固定 keystore 签名，覆盖安装不再丢失应用数据。CI 每次生成的 debug 密钥都不相同，而 Android 会拒绝安装签名不一致的包。
*   每次构建都会按 `versionName` 创建对应的 GitHub Release。

## 致谢

*   **主要开发者**：ALEX502
*   **原始框架**：VirtualApp、VirtualAPK
*   **Native Hook**：Dobby、xDL
*   **反射**：BlackReflection、FreeReflection

## 许可证

Copyright 2022 BlackBox

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
