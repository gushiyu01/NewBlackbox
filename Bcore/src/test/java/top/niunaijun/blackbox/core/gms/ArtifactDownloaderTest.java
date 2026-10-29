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
        ArtifactDownloader d = new ArtifactDownloader(
                url -> new ByteArrayInputStream(BODY), tmp.newFolder("cache"));
        File out = d.fetch(a);
        assertTrue(out.isFile());
        assertFalse(new File(out.getParentFile(), "gms.apk.part").exists());
    }

    @Test
    public void deletesFileOnHashMismatch() throws Exception {
        MicroGArtifacts.Artifact a = artifact(
                "0000000000000000000000000000000000000000000000000000000000000000");
        File cache = tmp.newFolder("cache");
        ArtifactDownloader d = new ArtifactDownloader(
                url -> new ByteArrayInputStream(BODY), cache);
        try {
            d.fetch(a);
            throw new AssertionError("expected failure");
        } catch (ArtifactDownloader.DownloadException e) {
            assertEquals(ArtifactDownloader.Failure.VERIFY, e.failure);
        }
        assertFalse(new File(cache, "gms.apk").exists());
        assertFalse(new File(cache, "gms.apk.part").exists());
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
        ArtifactDownloader d = new ArtifactDownloader(
                url -> new ByteArrayInputStream(BODY), cache);
        File result = d.fetch(artifact(null));
        assertEquals(BODY.length, result.length());
    }
}
