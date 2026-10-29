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
