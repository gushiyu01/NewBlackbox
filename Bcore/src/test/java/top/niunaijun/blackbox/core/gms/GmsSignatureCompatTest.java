package top.niunaijun.blackbox.core.gms;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import android.content.pm.Signature;

import java.security.MessageDigest;

public class GmsSignatureCompatTest {

    @Test
    public void spoofsOnlyGoogleComponents() {
        assertTrue(GmsSignatureCompat.shouldSpoof("com.google.android.gms"));
        assertTrue(GmsSignatureCompat.shouldSpoof("com.android.vending"));
        assertFalse(GmsSignatureCompat.shouldSpoof("com.facebook.aura"));
        assertFalse(GmsSignatureCompat.shouldSpoof(null));
    }

    @Test
    public void certificateIsGooglePlatformCert() {
        assertEquals(1095, GmsSignatureCompat.hexToBytes(GmsSignatureCompat.GOOGLE_CERT_HEX).length);
    }

    @Test
    public void decodesHexPairsBigEndian() {
        assertArrayEquals(new byte[]{0x30, (byte) 0x82, 0x04, 0x43},
                GmsSignatureCompat.hexToBytes("30820443"));
    }

    @Test
    public void certificateDigestMatchesUpstream() throws Exception {
        byte[] der = GmsSignatureCompat.hexToBytes(GmsSignatureCompat.GOOGLE_CERT_HEX);
        assertEquals(0x30, der[0] & 0xFF);
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(der)) {
            hex.append(String.format("%02x", b));
        }
        assertEquals("f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83",
                hex.toString());
    }

    @Test
    public void resolveGivesGoogleCertsToGoogleComponents() {
        Signature[] container = {new Signature(new byte[]{1})};
        Signature[] resolved = GmsSignatureCompat.resolve("com.google.android.gms", container, null);
        assertEquals(1, resolved.length);
        assertNotSame(container, resolved);
    }

    @Test
    public void resolveKeepsContainerCertsForOtherPackages() {
        Signature[] container = {new Signature(new byte[]{1})};
        Signature[] host = {new Signature(new byte[]{2})};
        assertArrayEquals(container,
                GmsSignatureCompat.resolve("com.facebook.aura", container, host));
    }
}
