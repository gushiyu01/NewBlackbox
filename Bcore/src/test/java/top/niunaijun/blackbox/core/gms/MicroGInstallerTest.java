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

        @Override
        public boolean isInstalled(String packageName, int userId) {
            return installed.contains(packageName);
        }

        @Override
        public InstallResult install(File apk, int userId) {
            InstallResult result = new InstallResult();
            if (failOn != null && apk.getName().startsWith(failOn)) {
                return result.installError("boom");
            }
            return result;
        }

        @Override
        public void uninstall(String packageName, int userId) {
            uninstalled.add(packageName);
            installed.remove(packageName);
        }
    }

    private MicroGInstaller installer(FakeHost host) throws Exception {
        final File cache = tmp.newFolder("cache");
        ArtifactProvider provider = artifact -> {
            File f = new File(cache, artifact.fileName);
            try {
                java.nio.file.Files.write(f.toPath(), new byte[]{1});
            } catch (java.io.IOException e) {
                throw new ArtifactDownloader.DownloadException(
                        ArtifactDownloader.Failure.IO, e.getMessage(), e);
            }
            return f;
        };
        return new MicroGInstaller(provider, host);
    }

    @Test
    public void installsEveryArtifactForUser() throws Exception {
        FakeHost host = new FakeHost();
        MicroGInstaller.Outcome outcome = installer(host).install(7);
        assertTrue(outcome.success());
        assertEquals(MicroGArtifacts.artifacts().size(), outcome.steps.size());
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
