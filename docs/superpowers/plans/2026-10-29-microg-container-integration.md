# MicroG 容器内集成 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 MicroG 作为容器自带组件安装到指定虚拟用户，容器内的 Google 组件与宿主完全隔离，并补上可阻断发布的云端测试。

**Architecture:** 纯 Java 的分层实现（清单 → 校验 → 下载 → 安装编排）放在 `Bcore` 的 `core/gms` 包，全部可在 JVM 单测中覆盖；Android 相关部分（HTTP、真实安装、签名构造）通过接口注入。容器内包解析改为"容器内优先"，签名伪装只作用于 GMS 包名白名单。

**Tech Stack:** Java 21 / Gradle / JUnit 4.13.2 / `HttpURLConnection`（不引入新依赖）/ Kotlin（UI 层）

**Spec:** `docs/superpowers/specs/2026-10-29-microg-container-integration-design.md`

## Global Constraints

- MicroG 固定版本：`v0.3.17.252432`
- GMS Core：`com.google.android.gms-252432034.apk`，`108113833` 字节，sha256 `e5e6cba078bdec812b5450599350e5c1a12c8b7438da5bf77123af170f8c1a35`
- FakeStore：`com.android.vending-84022634.apk`，`4210169` 字节，sha256 `9b8616011cc8f026bcbbe094df850794abb9d6d9f23214d7a8f8c4d1033ea012`
- 不把 APK 二进制提交进仓库，不打进主 APK
- 容器内包一律以容器内为准，宿主仅作兜底
- 签名伪装仅对 `com.google.android.gms`、`com.android.vending` 生效，且只在虚拟 PackageManager 内
- 不伪造认证成功（移除 `GmsProxy` 里返回 mock token / 空 Bundle 的行为）
- `minSdk 21`、`targetSdk 28`、`compileSdk 35`、Java 21

## Review Focus

- 下载被中断时留下的 `.part` 文件是否会污染下一次安装 → 必须覆盖写并可安全删除
- 宿主装了同名 GMS 时，容器内签名是否仍取自容器 → 必须优先容器
- 安装中途失败时是否会留下半装状态 → 必须回滚本轮新装包
- 校验失败是否被当成成功（大小/哈希任一不符都要失败）→ 必须显式拒绝
- 签名伪装是否越界作用到其他应用 → 必须白名单

---

### Task 1: 容器内包信息优先

**Files:**
- Create: `Bcore/src/main/java/top/niunaijun/blackbox/core/pm/PackageSignatureResolver.java`
- Modify: `Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/PackageManagerCompat.java:197-219`
- Test: `Bcore/src/test/java/top/niunaijun/blackbox/core/pm/PackageSignatureResolverTest.java`
- Modify: `Bcore/build.gradle`（新增测试依赖）

**Interfaces:**
- Produces: `static android.content.pm.Signature[] PackageSignatureResolver.preferContainer(Signature[] container, Signature[] host)`

- [ ] **Step 1: 加测试依赖**

`Bcore/build.gradle` 的 `dependencies { ... }` 块内追加：

```groovy
    testImplementation 'junit:junit:4.13.2'
```

- [ ] **Step 2: 写失败测试**

`Bcore/src/test/java/top/niunaijun/blackbox/core/pm/PackageSignatureResolverTest.java`：

```java
package top.niunaijun.blackbox.core.pm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

import android.content.pm.Signature;

import org.junit.Test;

public class PackageSignatureResolverTest {

    private static Signature sig(byte b) {
        return new Signature(new byte[]{b});
    }

    @Test
    public void containerSignaturesWinOverHost() {
        Signature[] container = {sig((byte) 1)};
        Signature[] host = {sig((byte) 2)};
        assertSame(container, PackageSignatureResolver.preferContainer(container, host));
    }

    @Test
    public void hostIsUsedOnlyWhenContainerHasNone() {
        Signature[] host = {sig((byte) 2)};
        assertSame(host, PackageSignatureResolver.preferContainer(null, host));
        assertSame(host, PackageSignatureResolver.preferContainer(new Signature[0], host));
    }

    @Test
    public void bothNullStaysNull() {
        assertArrayEquals(null, PackageSignatureResolver.preferContainer(null, null));
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew :Bcore:testDebugUnitTest --tests "*PackageSignatureResolverTest*"`
Expected: 编译失败，`PackageSignatureResolver` 不存在

- [ ] **Step 4: 写实现**

`Bcore/src/main/java/top/niunaijun/blackbox/core/pm/PackageSignatureResolver.java`：

```java
package top.niunaijun.blackbox.core.pm;

import android.content.pm.Signature;

/**
 * Decides which signature set a container package reports.
 *
 * A clone is a different package from any same-named package installed on the
 * host, so the host's certificate is not evidence about the clone. Reporting the
 * host's signature would make a container-side MicroG advertise the device's
 * Google certificate instead of its own.
 */
public final class PackageSignatureResolver {

    public static Signature[] preferContainer(Signature[] container, Signature[] host) {
        if (container != null && container.length > 0) {
            return container;
        }
        return host;
    }

    private PackageSignatureResolver() {
    }
}
```

- [ ] **Step 5: 接入 PackageManagerCompat**

把 `PackageManagerCompat.java` 中 `if ((flags & PackageManager.GET_SIGNATURES) != 0) { ... }` 与 `signingInfo` 两段改为：

```java
        if ((flags & PackageManager.GET_SIGNATURES) != 0) {
            pi.signatures = PackageSignatureResolver.preferContainer(
                    p.mSignatures,
                    base == null ? null : base.signatures);
        }
        if (BuildCompat.isPie()) {
            if ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
                if (base == null || p.mSigningDetails != null) {
                    PackageParser.SigningDetails signingDetails = PackageParser.SigningDetails.UNKNOWN;
                    BRPackageParserSigningDetails.get(signingDetails)._set_signatures(p.mSigningDetails.signatures);
                    pi.signingInfo = BRSigningInfo.get()._new(signingDetails);
                } else {
                    pi.signingInfo = base.signingInfo;
                }
            }
        }
```

并在文件头部补 import：

```java
import top.niunaijun.blackbox.core.pm.PackageSignatureResolver;
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :Bcore:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，3 个测试通过

- [ ] **Step 7: 提交**

```bash
git add Bcore/build.gradle Bcore/src/main/java/top/niunaijun/blackbox/core/pm/PackageSignatureResolver.java Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/PackageManagerCompat.java Bcore/src/test/java/top/niunaijun/blackbox/core/pm/PackageSignatureResolverTest.java
git commit -m "fix: report container signatures instead of the host's"
```

---

### Task 2: 固定版本清单与哈希校验

**Files:**
- Create: `Bcore/src/main/java/top/niunaijun/blackbox/core/gms/MicroGArtifacts.java`
- Create: `Bcore/src/main/java/top/niunaijun/blackbox/core/gms/Sha256Verifier.java`
- Test: `Bcore/src/test/java/top/niunaijun/blackbox/core/gms/MicroGArtifactsTest.java`
- Test: `Bcore/src/test/java/top/niunaijun/blackbox/core/gms/Sha256VerifierTest.java`

**Interfaces:**
- Produces:
  - `MicroGArtifacts.RELEASE : String`
  - `MicroGArtifacts.artifacts() : List<Artifact>`
  - `MicroGArtifacts.forPackage(String) : Artifact`
  - `Artifact.packageName / fileName / size / sha256 / url()`
  - `Sha256Verifier.verify(File, long, String) : Sha256Verifier.Result`

- [ ] **Step 1: 写失败测试**

`MicroGArtifactsTest.java`：

```java
package top.niunaijun.blackbox.core.gms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MicroGArtifactsTest {

    @Test
    public void twoArtifactsWithPinnedMetadata() {
        assertEquals(2, MicroGArtifacts.artifacts().size());
        for (MicroGArtifacts.Artifact a : MicroGArtifacts.artifacts()) {
            assertEquals(64, a.sha256.length());
            assertTrue(a.sha256.matches("[0-9a-f]{64}"));
            assertTrue(a.size > 0);
            assertEquals(MicroGArtifacts.BASE_URL + a.fileName, a.url());
        }
    }

    @Test
    public void lookupByPackage() {
        assertEquals("com.google.android.gms",
                MicroGArtifacts.forPackage("com.google.android.gms").packageName);
        assertNull(MicroGArtifacts.forPackage("com.example.unknown"));
    }

    @Test
    public void releaseIsPinned() {
        assertEquals("v0.3.17.252432", MicroGArtifacts.RELEASE);
    }
}
```

`Sha256VerifierTest.java`：

```java
package top.niunaijun.blackbox.core.gms;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class Sha256VerifierTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File file(String content) throws Exception {
        File f = tmp.newFile();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    @Test
    public void acceptsMatchingFile() throws Exception {
        File f = file("microg");
        assertEquals(Sha256Verifier.Result.OK,
                Sha256Verifier.verify(f, 6L, Sha256Verifier.sha256(f)));
    }

    @Test
    public void rejectsWrongSize() throws Exception {
        File f = file("microg");
        assertEquals(Sha256Verifier.Result.SIZE_MISMATCH,
                Sha256Verifier.verify(f, 7L, Sha256Verifier.sha256(f)));
    }

    @Test
    public void rejectsWrongHash() throws Exception {
        File f = file("microg");
        assertEquals(Sha256Verifier.Result.HASH_MISMATCH,
                Sha256Verifier.verify(f, 6L,
                        "0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    public void rejectsMissingFile() throws Exception {
        assertEquals(Sha256Verifier.Result.IO_ERROR,
                Sha256Verifier.verify(new File(tmp.getRoot(), "absent"), 6L, "x"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :Bcore:testDebugUnitTest --tests "*MicroGArtifactsTest*" --tests "*Sha256VerifierTest*"`
Expected: 编译失败，类不存在

- [ ] **Step 3: 写 MicroGArtifacts**

```java
package top.niunaijun.blackbox.core.gms;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pinned MicroG release. Changing these values is a deliberate upgrade. */
public final class MicroGArtifacts {

    public static final String RELEASE = "v0.3.17.252432";
    public static final String BASE_URL =
            "https://github.com/microg/GmsCore/releases/download/" + RELEASE + "/";

    public static final String GMS_PACKAGE = "com.google.android.gms";
    public static final String VENDING_PACKAGE = "com.android.vending";

    public static final class Artifact {
        public final String packageName;
        public final String fileName;
        public final long size;
        public final String sha256;

        Artifact(String packageName, String fileName, long size, String sha256) {
            this.packageName = packageName;
            this.fileName = fileName;
            this.size = size;
            this.sha256 = sha256;
        }

        public String url() {
            return BASE_URL + fileName;
        }
    }

    private static final List<Artifact> ARTIFACTS = Collections.unmodifiableList(Arrays.asList(
            new Artifact(GMS_PACKAGE, "com.google.android.gms-252432034.apk", 108113833L,
                    "e5e6cba078bdec812b5450599350e5c1a12c8b7438da5bf77123af170f8c1a35"),
            new Artifact(VENDING_PACKAGE, "com.android.vending-84022634.apk", 4210169L,
                    "9b8616011cc8f026bcbbe094df850794abb9d6d9f23214d7a8f8c4d1033ea012")));

    public static List<Artifact> artifacts() {
        return ARTIFACTS;
    }

    public static Artifact forPackage(String packageName) {
        for (Artifact artifact : ARTIFACTS) {
            if (artifact.packageName.equals(packageName)) {
                return artifact;
            }
        }
        return null;
    }

    private MicroGArtifacts() {
    }
}
```

- [ ] **Step 4: 写 Sha256Verifier**

```java
package top.niunaijun.blackbox.core.gms;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

public final class Sha256Verifier {

    public enum Result {
        OK,
        SIZE_MISMATCH,
        HASH_MISMATCH,
        IO_ERROR
    }

    public static Result verify(File file, long expectedSize, String expectedSha256) {
        if (file == null || !file.isFile()) {
            return Result.IO_ERROR;
        }
        if (file.length() != expectedSize) {
            return Result.SIZE_MISMATCH;
        }
        String actual = sha256(file);
        if (actual == null) {
            return Result.IO_ERROR;
        }
        return actual.equalsIgnoreCase(expectedSha256) ? Result.OK : Result.HASH_MISMATCH;
    }

    static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private Sha256Verifier() {
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :Bcore:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，Task 1 与 Task 2 全部通过

- [ ] **Step 6: 提交**

```bash
git add Bcore/src/main/java/top/niunaijun/blackbox/core/gms Bcore/src/test/java/top/niunaijun/blackbox/core/gms
git commit -m "feat: pin the MicroG release and verify its artifacts"
```

---

### Task 3: 带校验的下载

**Files:**
- Create: `Bcore/src/main/java/top/niunaijun/blackbox/core/gms/ArtifactProvider.java`
- Create: `Bcore/src/main/java/top/niunaijun/blackbox/core/gms/ArtifactDownloader.java`
- Test: `Bcore/src/test/java/top/niunaijun/blackbox/core/gms/ArtifactDownloaderTest.java`

**Interfaces:**
- Consumes: `MicroGArtifacts.Artifact`、`Sha256Verifier`
- Produces:
  - `interface ArtifactProvider { File fetch(MicroGArtifacts.Artifact) throws ArtifactDownloader.DownloadException; }`
  - `ArtifactDownloader(ArtifactDownloader.Transport, File)`，其中 `Transport.open(String) : InputStream`
  - `DownloadException.failure : Failure`（`NETWORK` / `VERIFY` / `IO`）

- [ ] **Step 1: 写失败测试**

```java
package top.niunaijun.blackbox.core.gms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ArtifactDownloaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final byte[] BODY = "microg".getBytes(StandardCharsets.UTF_8);

    private MicroGArtifacts.Artifact artifact(String sha) throws Exception {
        File f = tmp.newFile("payload");
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        out.write(BODY);
        out.close();
        return new MicroGArtifacts.Artifact(
                MicroGArtifacts.GMS_PACKAGE, "gms.apk", BODY.length,
                sha == null ? Sha256Verifier.sha256(f) : sha);
    }

    @Test
    public void writesVerifiedFile() throws Exception {
        MicroGArtifacts.Artifact a = artifact(null);
        ArtifactDownloader d = new ArtifactDownloader(url -> new ByteArrayInputStream(BODY),
                tmp.newFolder("cache"));
        File out = d.fetch(a);
        assertTrue(out.isFile());
        assertFalse(new File(out.getParentFile(), "gms.apk.part").exists());
    }

    @Test
    public void deletesFileOnHashMismatch() throws Exception {
        MicroGArtifacts.Artifact a = artifact(
                "0000000000000000000000000000000000000000000000000000000000000000");
        ArtifactDownloader d = new ArtifactDownloader(url -> new ByteArrayInputStream(BODY),
                tmp.newFolder("cache"));
        try {
            d.fetch(a);
            throw new AssertionError("expected failure");
        } catch (ArtifactDownloader.DownloadException e) {
            assertEquals(ArtifactDownloader.Failure.VERIFY, e.failure);
        }
        assertFalse(new File(tmp.getRoot(), "cache/gms.apk").exists());
        assertFalse(new File(tmp.getRoot(), "cache/gms.apk.part").exists());
    }

    @Test
    public void reportsNetworkFailure() throws Exception {
        ArtifactDownloader d = new ArtifactDownloader(url -> {
            throw new IOException("offline");
        }, tmp.newFolder("cache"));
        try {
            d.fetch(artifact(null));
            throw new AssertionError("expected failure");
        } catch (ArtifactDownloader.DownloadException e) {
            assertEquals(ArtifactDownloader.Failure.NETWORK, e.failure);
        }
    }

    @Test
    public void overwritesStalePartialFile() throws Exception {
        File cache = tmp.newFolder("cache");
        File part = new File(cache, "gms.apk.part");
        java.io.FileOutputStream out = new java.io.FileOutputStream(part);
        out.write("stale".getBytes(StandardCharsets.UTF_8));
        out.close();
        ArtifactDownloader d = new ArtifactDownloader(url -> new ByteArrayInputStream(BODY), cache);
        File result = d.fetch(artifact(null));
        assertEquals(BODY.length, result.length());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :Bcore:testDebugUnitTest --tests "*ArtifactDownloaderTest*"`
Expected: 编译失败，类不存在

- [ ] **Step 3: 写 ArtifactProvider**

```java
package top.niunaijun.blackbox.core.gms;

import java.io.File;

/** Supplies a verified APK for an artifact. Implemented by {@link ArtifactDownloader}. */
public interface ArtifactProvider {

    File fetch(MicroGArtifacts.Artifact artifact) throws ArtifactDownloader.DownloadException;
}
```

- [ ] **Step 4: 写 ArtifactDownloader**

```java
package top.niunaijun.blackbox.core.gms;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads one pinned artifact into the cache directory and only publishes it
 * after the size and SHA-256 match the manifest. A failed download never leaves
 * a file the installer could pick up later.
 */
public final class ArtifactDownloader implements ArtifactProvider {

    public interface Transport {
        InputStream open(String url) throws IOException;
    }

    public enum Failure {
        NETWORK,
        VERIFY,
        IO
    }

    public static final class DownloadException extends Exception {
        public final Failure failure;

        DownloadException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }
    }

    static final Transport HTTP = url -> {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        return connection.getInputStream();
    };

    private final Transport transport;
    private final File cacheDir;

    public ArtifactDownloader(Transport transport, File cacheDir) {
        this.transport = transport;
        this.cacheDir = cacheDir;
    }

    @Override
    public File fetch(MicroGArtifacts.Artifact artifact) throws DownloadException {
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new DownloadException(Failure.IO, "cannot create cache dir", null);
        }
        File target = new File(cacheDir, artifact.fileName);
        File part = new File(cacheDir, artifact.fileName + ".part");
        if (part.exists() && !part.delete()) {
            throw new DownloadException(Failure.IO, "cannot clear stale partial file", null);
        }
        try (InputStream in = transport.open(artifact.url());
             OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            part.delete();
            throw new DownloadException(Failure.NETWORK, "download failed: " + e.getMessage(), e);
        }

        Sha256Verifier.Result result = Sha256Verifier.verify(part, artifact.size, artifact.sha256);
        if (result != Sha256Verifier.Result.OK) {
            part.delete();
            throw new DownloadException(Failure.VERIFY, "artifact check failed: " + result, null);
        }
        if (target.exists() && !target.delete()) {
            part.delete();
            throw new DownloadException(Failure.IO, "cannot replace cached artifact", null);
        }
        if (!part.renameTo(target)) {
            part.delete();
            throw new DownloadException(Failure.IO, "cannot move artifact into place", null);
        }
        return target;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :Bcore:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add Bcore/src/main/java/top/niunaijun/blackbox/core/gms Bcore/src/test/java/top/niunaijun/blackbox/core/gms
git commit -m "feat: download MicroG artifacts and verify before publishing"
```

---

### Task 4: 按虚拟用户安装并回滚

**Files:**
- Create: `Bcore/src/main/java/top/niunaijun/blackbox/core/gms/MicroGInstaller.java`
- Create: `Bcore/src/main/java/top/niunaijun/blackbox/core/gms/BlackBoxMicroGInstaller.java`
- Test: `Bcore/src/test/java/top/niunaijun/blackbox/core/gms/MicroGInstallerTest.java`

**Interfaces:**
- Consumes: `MicroGArtifacts`、`ArtifactProvider`
- Produces:
  - `MicroGInstaller.Host`（`isInstalled(String,int)`、`install(File,int) : InstallResult`、`uninstall(String,int)`）
  - `MicroGInstaller.Outcome`（`success() : boolean`、`steps : List<Step>`）
  - `Step.packageName / status / message`，`Status` = `INSTALLED | SKIPPED | FAILED`
  - `MicroGInstaller(ArtifactProvider, Host).install(int userId) : Outcome`
  - `BlackBoxMicroGInstaller.create(Context) : MicroGInstaller`

- [ ] **Step 1: 写失败测试**

```java
package top.niunaijun.blackbox.core.gms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.entity.pm.InstallResult;

public class MicroGInstallerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final class FakeHost implements MicroGInstaller.Host {
        final Set<String> installed = new HashSet<>();
        final List<String> uninstalled = new ArrayList<>();
        String failOn = null;

        public boolean isInstalled(String packageName, int userId) {
            return installed.contains(packageName);
        }

        public InstallResult install(File apk, int userId) {
            InstallResult result = new InstallResult();
            if (failOn != null && apk.getName().startsWith(failOn)) {
                return result.installError("boom");
            }
            return result;
        }

        public void uninstall(String packageName, int userId) {
            uninstalled.add(packageName);
            installed.remove(packageName);
        }
    }

    private MicroGInstaller installer(FakeHost host) throws Exception {
        File cache = tmp.newFolder("cache");
        ArtifactProvider provider = artifact -> {
            File f = new File(cache, artifact.fileName);
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(1);
            out.close();
            return f;
        };
        return new MicroGInstaller(provider, host);
    }

    @Test
    public void installsEveryArtifactForUser() throws Exception {
        FakeHost host = new FakeHost();
        MicroGInstaller.Outcome outcome = installer(host).install(7);
        assertTrue(outcome.success());
        assertEquals(2, outcome.steps.size());
        assertEquals(MicroGInstaller.Status.INSTALLED, outcome.steps.get(0).status);
    }

    @Test
    public void skipsAlreadyInstalled() throws Exception {
        FakeHost host = new FakeHost();
        host.installed.add(MicroGArtifacts.GMS_PACKAGE);
        MicroGInstaller.Outcome outcome = installer(host).install(7);
        assertTrue(outcome.success());
        assertEquals(MicroGInstaller.Status.SKIPPED, outcome.steps.get(0).status);
    }

    @Test
    public void rollsBackNewlyInstalledOnFailure() throws Exception {
        FakeHost host = new FakeHost();
        host.failOn = "com.android.vending";
        MicroGInstaller.Outcome outcome = installer(host).install(7);
        assertFalse(outcome.success());
        assertEquals(1, host.uninstalled.size());
        assertEquals(MicroGArtifacts.GMS_PACKAGE, host.uninstalled.get(0));
    }
}
```

注意：`failOn` 与 `artifact.fileName` 前缀匹配，`com.android.vending` 正是 FakeStore 的 `fileName` 前缀。

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :Bcore:testDebugUnitTest --tests "*MicroGInstallerTest*"`
Expected: 编译失败，类不存在

- [ ] **Step 3: 写 MicroGInstaller**

```java
package top.niunaijun.blackbox.core.gms;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * Installs the pinned MicroG artifacts into one virtual user, rolling back the
 * packages this run added if any step fails so the user never ends up with a
 * half-installed Google stack.
 */
public final class MicroGInstaller {

    public interface Host {
        boolean isInstalled(String packageName, int userId);

        InstallResult install(File apk, int userId);

        void uninstall(String packageName, int userId);
    }

    public enum Status {
        INSTALLED,
        SKIPPED,
        FAILED
    }

    public static final class Step {
        public final String packageName;
        public final Status status;
        public final String message;

        Step(String packageName, Status status, String message) {
            this.packageName = packageName;
            this.status = status;
            this.message = message;
        }
    }

    public static final class Outcome {
        public final List<Step> steps;

        Outcome(List<Step> steps) {
            this.steps = Collections.unmodifiableList(steps);
        }

        public boolean success() {
            for (Step step : steps) {
                if (step.status == Status.FAILED) {
                    return false;
                }
            }
            return true;
        }
    }

    private final ArtifactProvider provider;
    private final Host host;

    public MicroGInstaller(ArtifactProvider provider, Host host) {
        this.provider = provider;
        this.host = host;
    }

    public Outcome install(int userId) {
        List<Step> steps = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (MicroGArtifacts.Artifact artifact : MicroGArtifacts.artifacts()) {
            if (host.isInstalled(artifact.packageName, userId)) {
                steps.add(new Step(artifact.packageName, Status.SKIPPED, "already installed"));
                continue;
            }
            try {
                File apk = provider.fetch(artifact);
                InstallResult result = host.install(apk, userId);
                if (result == null || !result.success) {
                    steps.add(new Step(artifact.packageName, Status.FAILED,
                            result == null ? "install returned nothing" : result.msg));
                    rollback(added, userId);
                    return new Outcome(steps);
                }
                added.add(artifact.packageName);
                steps.add(new Step(artifact.packageName, Status.INSTALLED, ""));
            } catch (ArtifactDownloader.DownloadException e) {
                steps.add(new Step(artifact.packageName, Status.FAILED,
                        e.failure + ": " + e.getMessage()));
                rollback(added, userId);
                return new Outcome(steps);
            }
        }
        return new Outcome(steps);
    }

    private void rollback(List<String> added, int userId) {
        for (int i = added.size() - 1; i >= 0; i--) {
            host.uninstall(added.get(i), userId);
        }
    }
}
```

- [ ] **Step 4: 写 Android 侧装配**

`BlackBoxMicroGInstaller.java`：

```java
package top.niunaijun.blackbox.core.gms;

import android.content.Context;

import java.io.File;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

public final class BlackBoxMicroGInstaller {

    public static MicroGInstaller create(Context context) {
        File cache = new File(context.getCacheDir(), "microg");
        ArtifactDownloader downloader = new ArtifactDownloader(ArtifactDownloader.HTTP, cache);
        return new MicroGInstaller(downloader, new MicroGInstaller.Host() {
            @Override
            public boolean isInstalled(String packageName, int userId) {
                return BlackBoxCore.get().isInstalled(packageName, userId);
            }

            @Override
            public InstallResult install(File apk, int userId) {
                return BlackBoxCore.get().installPackageAsUser(apk, userId);
            }

            @Override
            public void uninstall(String packageName, int userId) {
                BlackBoxCore.get().uninstallPackageAsUser(packageName, userId);
            }
        });
    }

    private BlackBoxMicroGInstaller() {
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :Bcore:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add Bcore/src/main/java/top/niunaijun/blackbox/core/gms Bcore/src/test/java/top/niunaijun/blackbox/core/gms
git commit -m "feat: install MicroG per virtual user with rollback"
```

---

### Task 5: GMS 包签名伪装

**Files:**
- Create: `Bcore/src/main/java/top/niunaijun/blackbox/core/gms/GmsSignatureCompat.java`
- Modify: `Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/PackageManagerCompat.java`（在 Task 1 的两段之后追加）
- Test: `Bcore/src/test/java/top/niunaijun/blackbox/core/gms/GmsSignatureCompatTest.java`

**Interfaces:**
- Consumes: `MicroGArtifacts.GMS_PACKAGE`、`MicroGArtifacts.VENDING_PACKAGE`
- Produces:
  - `static boolean GmsSignatureCompat.shouldSpoof(String packageName)`
  - `static android.content.pm.Signature[] GmsSignatureCompat.googleSignatures()`
  - `static String GmsSignatureCompat.GOOGLE_CERT_BASE64`

- [ ] **Step 1: 写失败测试**

```java
package top.niunaijun.blackbox.core.gms;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GmsSignatureCompatTest {

    @Test
    public void spoofsOnlyGoogleComponents() {
        assertTrue(GmsSignatureCompat.shouldSpoof("com.google.android.gms"));
        assertTrue(GmsSignatureCompat.shouldSpoof("com.android.vending"));
        assertFalse(GmsSignatureCompat.shouldSpoof("com.facebook.aura"));
        assertFalse(GmsSignatureCompat.shouldSpoof(null));
    }

    @Test
    public void googleCertificateIsEmbedded() {
        assertTrue(GmsSignatureCompat.GOOGLE_CERT_BASE64.length() > 100);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :Bcore:testDebugUnitTest --tests "*GmsSignatureCompatTest*"`
Expected: 编译失败

- [ ] **Step 3: 写 GmsSignatureCompat**

`GOOGLE_CERT_BASE64` 取自 MicroG `fake-signature` 模块的 `fake_signature` 字符串（Google 平台证书，DER 的 Base64）。逐字复制下面这一整行，不要换行或截断：

```java
package top.niunaijun.blackbox.core.gms;

import android.content.pm.Signature;
import android.util.Base64;

/**
 * MicroG is signed with its own key, but apps that talk to Play services check
 * that the provider carries Google's certificate. Inside the container the
 * virtual PackageManager can answer that question directly, so no device-wide
 * signature spoofing is needed. Scope is deliberately limited to the two
 * packages MicroG stands in for.
 */
public final class GmsSignatureCompat {

    static final String GOOGLE_CERT_BASE64 =
            "308204433082032ba003020102020900c2e08746644a308d300d06092a864886f70d01010405003074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964301e170d3038303832313233313333345a170d3336303130373233313333345a3074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f696430820120300d06092a864886f70d01010105000382010d00308201080282010100ab562e00d83ba208ae0a966f124e29da11f2ab56d08f58e2cca91303e9b754d372f640a71b1dcb130967624e4656a7776a92193db2e5bfb724a91e77188b0e6a47a43b33d9609b77183145ccdf7b2e586674c9e1565b1f4c6a5955bff251a63dabf9c55c27222252e875e4f8154a645f897168c0b1bfc612eabf785769bb34aa7984dc7e2ea2764cae8307d8c17154d7ee5f64a51a44a602c249054157dc02cd5f5c0e55fbef8519fbe327f0b1511692c5a06f19d18385f5c4dbc2d6b93f68cc2979c70e18ab93866b3bd5db8999552a0e3b4c99df58fb918bedc182ba35e003c1b4b10dd244a8ee24fffd333872ab5221985edab0fc0d0b145b6aa192858e79020103a381d93081d6301d0603551d0e04160414c77d8cc2211756259a7fd382df6be398e4d786a53081a60603551d2304819e30819b8014c77d8cc2211756259a7fd382df6be398e4d786a5a178a4763074310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964820900c2e08746644a308d300c0603551d13040530030101ff300d06092a864886f70d010104050003820101006dd252ceef85302c360aaace939bcff2cca904bb5d7a1661f8ae46b2994204d0ff4a68c7ed1a531ec4595a623ce60763b167297a7ae35712c407f208f0cb109429124d7b106219c084ca3eb3f9ad5fb871ef92269a8be28bf16d44c8d9a08e6cb2f005bb3fe2cb96447e868e731076ad45b33f6009ea19c161e62641aa99271dfd5228c5c587875ddb7f452758d661f6cc0cccb7352e424cc4365c523532f7325137593c4ae341f4db41edda0d0b1071a7c440f0fe9ea01cb627ca674369d084bd2fd911ff06cdbf2cfa10dc0f893ae35762919048c7efc64c7144178342f70581c9de573af55b390dd7fdb9418631895d5f759f30112687ff621410c069308a";

    public static boolean shouldSpoof(String packageName) {
        return MicroGArtifacts.GMS_PACKAGE.equals(packageName)
                || MicroGArtifacts.VENDING_PACKAGE.equals(packageName);
    }

    public static Signature[] googleSignatures() {
        return new Signature[]{new Signature(Base64.decode(GOOGLE_CERT_BASE64, Base64.DEFAULT))};
    }

    private GmsSignatureCompat() {
    }
}
```

- [ ] **Step 4: 接入 PackageManagerCompat**

在 `PackageManagerCompat` 的 `GET_SIGNATURES` 分支之后追加：

```java
        if (GmsSignatureCompat.shouldSpoof(p.packageName)) {
            pi.signatures = GmsSignatureCompat.googleSignatures();
        }
```

（放在 `GET_SIGNATURES` 与 `GET_SIGNING_CERTIFICATES` 两段之后，确保覆盖两种情况。）

并在文件头部补 import：

```java
import top.niunaijun.blackbox.core.gms.GmsSignatureCompat;
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :Bcore:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add Bcore/src/main/java/top/niunaijun/blackbox/core/gms Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/PackageManagerCompat.java Bcore/src/test/java/top/niunaijun/blackbox/core/gms
git commit -m "feat: report Google's certificate for the container's MicroG"
```

---

### Task 6: 移除从未生效且会欺骗调用方的 GmsProxy

**Files:**
- Delete: `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/GmsProxy.java`
- Modify: `Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/HookManager.java:55,141`

**Interfaces:**
- 无新增接口

- [ ] **Step 1: 确认没有其他引用**

Run: `grep -rn "GmsProxy" Bcore/src app/src`
Expected: 仅 `HookManager` 的 import 与 `addInjector`

- [ ] **Step 2: 删除文件与注册**

```bash
git rm Bcore/src/main/java/top/niunaijun/blackbox/fake/service/GmsProxy.java
```

从 `HookManager.java` 删除 import 行 `import top.niunaijun.blackbox.fake.service.GmsProxy;` 与 `addInjector(new GmsProxy());`。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :Bcore:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add -A Bcore/src/main/java/top/niunaijun/blackbox/fake
git commit -m "refactor: drop GmsProxy, which never installed and faked auth success"
```

---

### Task 7: GMS 页面改用内置 MicroG

**Files:**
- Modify: `app/src/main/java/top/niunaijun/blackboxa/data/GmsRepository.kt`
- Modify: `app/src/main/java/top/niunaijun/blackboxa/view/setting/SettingFragment.kt:73-86`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

**Interfaces:**
- Consumes: `BlackBoxMicroGInstaller.create(Context)`、`MicroGInstaller.Outcome`、`MicroGInstaller.Step`
- Produces: 无（内部装配）

- [ ] **Step 1: 改写 GmsRepository**

```kotlin
package top.niunaijun.blackboxa.data

import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.gms.BlackBoxMicroGInstaller
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.bean.GmsInstallBean
import top.niunaijun.blackboxa.util.getString


class GmsRepository {

    fun getGmsInstalledList(mInstalledLiveData: MutableLiveData<List<GmsBean>>) {
        val userList = arrayListOf<GmsBean>()

        BlackBoxCore.get().users.forEach {
            val userId = it.id
            val userName =
                AppManager.mRemarkSharedPreferences.getString("Remark$userId", "User $userId") ?: ""
            val isInstalled = BlackBoxCore.get().isInstallGms(userId)
            val bean = GmsBean(userId, userName, isInstalled)
            userList.add(bean)
        }

        mInstalledLiveData.postValue(userList)
    }

    fun installGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        val outcome = try {
            BlackBoxMicroGInstaller.create(BlackBoxCore.getContext()).install(userID)
        } catch (e: Throwable) {
            val bean = GmsInstallBean(
                userID, false,
                getString(R.string.install_fail, e.message ?: e.javaClass.simpleName)
            )
            mUpdateInstalledLiveData.postValue(bean)
            return
        }

        val detail = outcome.steps.joinToString("\n") { step ->
            "${step.packageName}: ${step.status.name.lowercase()}" +
                    if (step.message.isNullOrEmpty()) "" else " (${step.message})"
        }
        val result = if (outcome.success()) {
            getString(R.string.install_success)
        } else {
            getString(R.string.install_fail, detail)
        }

        val bean = GmsInstallBean(userID, outcome.success(), result)
        mUpdateInstalledLiveData.postValue(bean)
    }

    fun uninstallGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        var isSuccess = false
        if (BlackBoxCore.get().isInstallGms(userID)) {
            isSuccess = BlackBoxCore.get().uninstallGms(userID)
        }

        val result = if (isSuccess) {
            getString(R.string.uninstall_success)
        } else {
            getString(R.string.uninstall_fail)
        }

        val bean = GmsInstallBean(userID, isSuccess, result)

        mUpdateInstalledLiveData.postValue(bean)
    }
}
```

- [ ] **Step 2: 设置页不再按宿主是否有 GMS 禁用入口**

把 `SettingFragment.initGms()` 改为：

```kotlin
    private fun initGms() {
        val gmsManagerPreference: Preference = (findPreference("gms_manager")!!)
        gmsManagerPreference.summary = getString(R.string.gms_manager_summary)
        gmsManagerPreference.setOnPreferenceClickListener {
            GmsManagerActivity.start(requireContext())
            true
        }
    }
```

- [ ] **Step 3: 新增字符串资源**

`values/strings.xml` 追加：

```xml
    <string name="gms_manager_summary">Install MicroG inside this box. No Google services are needed on the phone.</string>
```

`values-zh-rCN/strings.xml` 追加：

```xml
    <string name="gms_manager_summary">在当前 Box 内安装 MicroG，手机无需安装 Google 服务。</string>
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/top/niunaijun/blackboxa/data/GmsRepository.kt app/src/main/java/top/niunaijun/blackboxa/view/setting/SettingFragment.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml
git commit -m "feat: install MicroG from inside the box instead of cloning the host"
```

---

### Task 8: 云端测试门禁

**Files:**
- Modify: `.github/workflows/build_and_telegram.yml`

**Interfaces:**
- 无

- [ ] **Step 1: 在构建之前插入测试步骤**

在 `- name: Build APKs and AARs` 之前插入：

```yaml
      - name: Run unit tests
        run: ./gradlew testDebugUnitTest :Bcore:testDebugUnitTest
```

- [ ] **Step 2: 确认失败会阻断发布**

测试步骤与后续发布在同一个 job 内，步骤失败即中止 job，Release 不会发布。无需额外改动。

- [ ] **Step 3: 本地验证测试任务可跑**

Run: `./gradlew testDebugUnitTest :Bcore:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交并推送**

```bash
git add .github/workflows/build_and_telegram.yml
git commit -m "ci: run unit tests before building and publishing"
git push origin main
```

---

### Task 9: 版本与文档

**Files:**
- Modify: `build.gradle:13-14`
- Modify: `README.md`（更新日志新增 4.2.0）

**Interfaces:**
- 无

- [ ] **Step 1: 升级版本号**

```groovy
    versionCode = 420
    versionName = "4.2.0"
```

- [ ] **Step 2: README 更新日志新增小节**

在 `## 更新日志` 下、`### 4.1.0` 之前插入：

```markdown
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
```

- [ ] **Step 3: 提交并推送**

```bash
git add build.gradle README.md
git commit -m "build: 4.2.0"
git push origin main
```

- [ ] **Step 4: 确认 CI 通过并发布**

在 GitHub Actions 页面确认 `Run unit tests` 与构建步骤均通过，Release `v4.2.0` 生成。

---

## 验收（实机，CI 不宣称）

安装 `v4.2.0` 后：

1. 设置 → Google 服务 → 为 User 0 安装，等待完成，应显示逐包结果。
2. 打开容器内的 MicroG，运行自检。
3. 打开容器内的 Chrome / Gmail，确认不再秒退。
4. 若失败，用「Send Logs Now」导出日志，重点看 `MicroGInstaller` 与 `PackageManagerCompat` 相关行。
