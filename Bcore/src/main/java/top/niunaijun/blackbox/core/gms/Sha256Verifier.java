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
