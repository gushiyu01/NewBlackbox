# BlackBox 容器内集成 MicroG — 设计文档

日期：2026-10-29
状态：待评审

## 1. 背景

BlackBox 目前的 GMS 支持只会克隆**宿主手机已安装**的 Google 服务：

- `GmsCore.installPackages()` 用宿主 `PackageManager.getApplicationInfo()` 探测每个包，`NameNotFoundException` 时静默 `continue`（[GmsCore.java](../../../Bcore/src/main/java/top/niunaijun/blackbox/core/GmsCore.java)）。
- 设置页在 `isSupportGms()` 为 false 时直接禁用 GMS 入口（[SettingFragment.kt](../../../app/src/main/java/top/niunaijun/blackboxa/view/setting/SettingFragment.kt)）。

因此宿主没有 GMS 的用户完全无法使用，而有 GMS 的用户把 Play 服务克隆进容器后，进程启动几秒即退出。

目标是把 MicroG 作为**容器自带组件**安装到虚拟用户，不再依赖宿主环境。

## 2. 目标

1. 用户可在 BlackBox 内为指定虚拟用户安装 MicroG，**无需宿主预装 Google 服务**。
2. 已克隆的应用（Chrome、Gmail、依赖 GMS 的第三方应用）能在容器内调用 MicroG 服务。
3. 安装来源可审计：固定官方版本、校验 SHA-256、失败可回滚。
4. 云端 CI 提供**可复现的自动化验证**，且不把构建成功谎报为功能可用。

## 3. 非目标

- 不内置 Google 原版 GMS/Play 服务（分发授权问题）。
- 不提供 Play 商店应用界面，首期只有 FakeStore 占位包。
- 不保证 Play Integrity、Google 账号登录、DroidGuard 等依赖 Google 后端认证的能力。
- 不修改宿主系统或要求宿主 ROM 支持签名伪装。

## 4. 组件与分发

### 4.1 首期纳入的组件

| 包名 | 用途 | 来源 |
|---|---|---|
| `com.google.android.gms` | MicroG Core，GMS 服务实现 | microg/GmsCore release |
| `com.android.vending` | FakeStore 占位包，让查询 Play 商店的应用得到合理答复 | 同上 |

固定版本：`v0.3.17.252432`（2026-09-29 发布，含安全修复）。

已核实的资产哈希：

```
com.google.android.gms-252432034.apk
  size   108113833
  sha256 e5e6cba078bdec812b5450599350e5c1a12c8b7438da5bf77123af170f8c1a35

com.android.vending-84022634.apk
  size   4210169
  sha256 9b8616011cc8f026bcbbe094df850794abb9d6d9f23214d7a8f8c4d1033ea012
```

首期**不纳入**：

- `org.microg.gms-*.apk`：包名为 `org.microg.gms`，用于应用商店分发；克隆场景需要 `com.google.android.gms` 包名，故不采用。
- `*-hw.apk`：华为变体，与目标设备无关。
- `GsfProxy.apk`：仅供旧式 C2DM/GCM 客户端使用，独立 APK，首期缺省；若后续需要，其 release 未提供 digest，需自行固化校验值。

### 4.2 分发方式

用户在 GMS 管理页点击安装时，从固定的官方 release URL 下载，校验大小与 SHA-256 后写入应用私有缓存，再交给 `BPackageManagerService` 安装。校验失败则不安装、不缓存。

不把约 112 MB 的二进制提交进仓库，也不打进 BlackBox 主 APK。

### 4.3 下载清单

清单以代码常量维护（而非可远程篡改的配置文件）：

```java
final class MicroGPackages {
    static final String RELEASE = "v0.3.17.252432";

    // 哈希值必须写完整 64 位十六进制，禁止截断或用前缀匹配。
    static final List<Artifact> ARTIFACTS = List.of(
        new Artifact("com.google.android.gms-252432034.apk", 108113833L,
                "e5e6cba078bdec812b5450599350e5c1a12c8b7438da5bf77123af170f8c1a35"),
        new Artifact("com.android.vending-84022634.apk", 4210169L,
                "9b8616011cc8f026bcbbe094df850794abb9d6d9f23214d7a8f8c4d1033ea012"));
}
```

## 5. 安装流程

```
用户点击「安装 MicroG」 for userId
  → 校验宿主网络可用
  → 对每个 artifact：
      已安装在 userId 且版本一致 → 跳过
      下载到 cacheDir/microg/<name>.part
      校验 size + sha256
      校验通过 → rename 为 .apk
      调用 BlackBoxCore.installPackageAsUser(file, userId)
      失败 → 删除该 APK 并中止，已装组件回滚（卸载本次新装的包）
  → 返回逐包结果（成功/跳过/失败原因）
```

关键点：

- 每个包的安装结果独立记录，界面按包展示，不再只显示一个总成败。
- 中止时回滚本次新安装的包，避免半装状态。
- 安装完成后重新扫描组件（与现有 `installPackageAsUser` 路径一致）。

## 6. 签名兼容

MicroG 使用 Google 的签名证书（其 `fake-signature` 模块内的 `fake_signature` 字符串即为 Google 平台证书的 DER）。依赖 GMS 的应用常会校验 `com.google.android.gms` 的签名是否为 Google 签名。

容器内的等价做法：**让虚拟 PackageManager 对被克隆应用报告 GMS 包的 Google 签名**，而不是要求宿主 ROM 支持全局签名伪装。

设计约束：

- 仅在虚拟 PackageManager 内部生效，作用域限 `com.google.android.gms` 及明确列入清单的 Google 组件包。
- 绝不修改宿主 `PackageManager`，不影响容器外的任何应用。
- **容器内优先**：容器内已安装该包时，包信息与签名只取自容器内，宿主同名包不得覆盖。
  当前 `PackageManagerCompat` 的 `base != null` 分支正好相反，必须先修正。

需要覆盖的查询路径（实施前需逐项确认存在与签名）：

- `getPackageInfo(..., GET_SIGNATURES)`
- `getPackageInfo(..., GET_SIGNING_CERTIFICATES)`（Android 9+）
- `checkSignatures` / `checkSignaturesAsUser`
- `hasSigningCertificate`（如被使用）

当前 [PackageManagerCompat.java](../../../Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/PackageManagerCompat.java) 在这些路径上返回包自身实际签名，`IPackageManagerProxy` 目前没有任何签名相关 hook。这是本设计的主要改动面。

## 7. GmsProxy 的处理

现有 [GmsProxy.java](../../../Bcore/src/main/java/top/niunaijun/blackbox/fake/service/GmsProxy.java) 在构造时取名为 `"gms"` 的系统服务：

```java
super(BRServiceManager.get().getService("gms"));
```

Android 没有该服务，`getWho()` 返回 null，`ClassInvocationStub.injectHook()` 直接返回，代理从未安装。实测日志已确认。

处理方式：

- 删除对不存在服务的假设，或将其改为可选组件并明确记录"未生效"。
- 不伪造 `authenticate` / `getToken` 的成功结果——现有代码在异常时返回 mock token，会让调用方误判登录成功，属于错误行为，应移除。
- MicroG 的服务发现走 `chirp`/`gmscore` 等实际 Binder 名称，实施时以 MicroG 源码为准，不凭猜测添加。

## 8. 错误处理

| 情况 | 行为 |
|---|---|
| 无网络 | 提示需联网，不进入下载 |
| HTTP 非 200 | 中止，展示状态码 |
| 大小不符 | 丢弃文件，报"资产校验失败" |
| SHA-256 不符 | 丢弃文件，报"资产校验失败（可能被篡改）" |
| 安装返回失败 | 回滚本次新装包，展示 `InstallResult.msg` |
| 部分包已装 | 跳过，界面标注"已安装" |
| 空间不足 | 安装前检查可用空间，不足则中止 |

不吞异常：所有失败都要能在界面看到原因，不再静默 `continue`。

## 9. 测试策略

明确区分三层，报告中不得混淆。

### 9.1 云端必须通过（阻断发布）

- `:app:assembleDebug` / `:app:assembleRelease`、`:Bcore:assemble*` 编译。
- JVM 单测（`src/test`，无需设备）：
  - 下载清单常量完整性（URL、大小、哈希非空且格式合法）。
  - SHA-256 校验函数：正确哈希通过、篡改后拒绝、大小不符拒绝。
  - 安装状态机：已装跳过、失败回滚、部分成功记录。
  - 签名伪装作用域：仅对白名单包生效，其他包返回真实签名。
- CI 在测试失败时不发布 Release。

### 9.2 可选设备级自动化

AOSP 模拟器（x86_64）中启动容器、安装 MicroG、检查组件列表与日志。受限于 Bcore 只构建 `arm64-v8a`/`armeabi-v7a`，常规模拟器可能无法加载原生库；若无法稳定运行，此项标记为跳过，不记为通过。

### 9.3 只能实机验收（CI 不宣称）

- MicroG 自检（Self-Check）结果。
- Google 账号登录。
- 依赖 GMS 的应用实际功能（如 Chrome 同步、Gmail 收信）。

CI 输出应明确写明"未验证真实 Google 功能"，避免把构建成功当作功能可用。

## 10. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| MicroG 版本升级破坏行为 | 应用突然失效 | 版本与哈希固定，升级需显式改常量并跑全量测试 |
| 签名伪装范围过大 | 影响其他应用或宿主 | 白名单 + 仅虚拟 PM 内生效 |
| 依赖 GMS 的应用仍检测失败（Play Integrity 等） | 部分应用不可用 | 文档明确不保证，界面提示 |
| 112 MB 下载失败率高 | 用户安装失败 | 断点重置 + 明确错误提示，不做静默重试 |
| 容器内包被宿主同名包覆盖 | 签名不符，GMS 校验失败 | 改为容器内优先（见第 6 节） |

## 11. 已定决策

1. **宿主 GMS 与容器内 MicroG 互不干扰。** 容器内的包一律以容器内为准，不回落到宿主。
   现有 `PackageManagerCompat` 在宿主存在同名包时用宿主签名覆盖容器内的签名
   （`base != null` 分支），实施时必须改为：容器内已安装该包时，包信息与签名
   只来自容器内，宿主仅作为容器内没有该包时的兜底。
2. **与宿主零关联。** 容器内的 Google 组件不读取、不依赖宿主已安装的 GMS/GSF/Play 商店，
   也不向宿主暴露任何容器内状态。
3. 下载失败在列表项内联显示错误，并提供重试按钮。
4. 首期不纳入 `GsfProxy`，按实际应用反馈再定。

## 12. 后续步骤

1. 依据本文档编写实施计划（writing-plans）。
2. 按计划实施：容器内优先的包解析修正 → 下载与校验 → 安装编排 → 签名兼容 → GMS 页改造 → 测试。
