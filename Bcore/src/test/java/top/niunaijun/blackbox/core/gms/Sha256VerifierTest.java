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
