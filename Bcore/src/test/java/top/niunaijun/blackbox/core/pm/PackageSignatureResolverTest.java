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
