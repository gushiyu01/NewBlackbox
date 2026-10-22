# BlackBox - Virtual Engine

<p align="center">
  <img src="assets/usage.gif" alt="BlackBox Banner" width="100%"/>
</p>

BlackBox is a virtual engine that allows you to clone and run virtual applications on Android devices without installing APKs. This project works on Android 5.0 to 14.0+ and supports multiple architectures (ARM64, ARMv7, x86).

## Overview

This enhanced edition includes bug fixes, stability improvements, and Android 14+ compatibility tailored for modern devices.

### Key Features

*   **Virtual App Cloning**: Run multiple instances of applications.
*   **Sandboxed Environment**: Isolated process execution.
*   **No Root Required**: Runs entirely in userspace.
*   **Multi-Architecture**: Support for 32-bit and 64-bit apps.
*   **Device Spoofing**: Modify device information for virtual apps.
*   **Fake Location**: Spoof GPS coordinates.

## Requirements

*   **Android Version**: Android 5.0 (API 21) or higher.
*   **RAM**: 2GB minimum recommended.
*   **Architecture**: ARMv7a, ARM64-v8a, x86.

## Build Instructions

### Prerequisites
*   Android Studio (Arctic Fox or newer)
*   JDK 17
*   Android SDK 34+
*   NDK (Version 29.0.13846066)

### Building from Source

```bash
# Clone the repository
git clone https://github.com/your-repo/NewBlackbox.git
cd NewBlackbox

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

## Integration

To use BlackBox Core in your own project, add the AAR dependency:

```gradle
dependencies {
    implementation fileTree(dir: "libs", include: ["*.aar"])
}
```

Refer to `Docs.md` for detailed API documentation.

## Troubleshooting

*   **App Crashes**: Check logcat for UID mismatches or permission errors.
*   **Installation Failures**: Verify potential architecture mismatches or storage permissions.
*   **Android 15**: Ensure you are using the latest build which handles stricter security policies.

## Changelog

### 4.1.0

This release fixes two container bugs that made sandboxed apps unusable on modern Android, removes a security hole in the crash reporter, and drops the VPN feature.

#### Fixed: settings screen crashed inside cloned apps

Tapping the settings button inside a cloned app (for example Muse / `com.facebook.aura`) killed the process immediately, with no crash log.

The framework threw `SecurityException: Package X does not belong to <host uid>`. When the app asked `RoleManager` whether it holds a role, the framework verified that the calling UID owns the queried package. Guest apps run under the host UID but report the guest package name, so the check threw and aborted settings initialisation.

Added `IRoleManagerProxy` to answer the `role` service inside the container: `isRoleHeld` and `isRoleHeldAsUser` return `false`, `isRoleAvailable` returns `true`, and `getRoleHoldersAsUser` returns an empty list. Registered in `HookManager` on Android 10+.

#### Fixed: every WebView page failed with net::ERR_CACHE_MISS

Pages opened inside cloned apps — in Muse, WeChat, anywhere — failed with `net::ERR_CACHE_MISS`, while the same apps fetched over HTTP from Java without trouble.

Chromium asks **`ActivityManager.checkPermission`** for `INTERNET`, by package name, before it will open a socket. The cloned package is not registered in the real system PackageManager, so the lookup answered `DENIED`. Chromium then fell back to cache-only loading, and an empty cache produces `ERR_CACHE_MISS`. Java networking never consults that check, which is why an app could reach the network while every WebView page failed.

Three files now report the network permissions as granted, covering both permission paths:

| File | Change |
|---|---|
| `IActivityManagerProxy` | network branch in `checkPermission`, new `checkPermissionForDevice` hook |
| `IPackageManagerProxy` | `checkPermission`, `checkSelfPermission`, `getPackageInfo` |
| `PackageManagerCompat` | `requestedPermissionsFlags` in generated `PackageInfo` |

Permissions covered: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`.

The `ActivityManager` hook is the one that matters; changing only PackageManager had no effect, because Chromium does not query it.

#### Security: crash logs no longer leave through a third party

`LogSender` used to upload the full logcat plus a device fingerprint to `logs-sender-api.vercel.app`, which relayed them to a Telegram group controlled by someone else. Automatic reporting is still available, but it now posts to a bot the user configures on the app's settings screen. No credentials are hardcoded, since this repository is public.

#### Removed: VPN mode

The VPN toggle prompted for a VPN connection on every launch and served no purpose here. `ProxyVpnService`, its manifest declaration, the `BIND_VPN_SERVICE` permission and the calling code are gone.

#### Build

*   Universal APK only — per-ABI splits dropped.
*   Releases are signed with a pinned keystore, so upgrading in place no longer loses app data. A CI-generated debug key changes on every runner, and Android refuses to install over a mismatched signature.
*   Each build publishes a GitHub Release tagged by `versionName`.

## Credits

*   **Main Developer**: ALEX502
*   **Original Framework**: VirtualApp, VirtualAPK
*   **Native Hooks**: Dobby, xDL
*   **Reflection**: BlackReflection, FreeReflection

## License

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
