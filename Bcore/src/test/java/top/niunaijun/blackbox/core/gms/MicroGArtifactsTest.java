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
