package top.niunaijun.blackbox.core.gms;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Google's own apps share one Linux uid on a real device, so a caller holding
 * that uid reports the whole Google family from getPackagesForUid -- that is how
 * Chromium inside WebView can ask Play services for a broker while claiming
 * com.google.android.gms. Every guest here shares the host uid instead, so the
 * same question answers with the guest's own package and MicroG's service broker
 * rejects the call with "UID is not related to packageName".
 *
 * Reporting the container's Google components alongside the caller emulates the
 * shared uid. The sandbox already gives every guest the same uid, so this reveals
 * nothing a guest could not infer, and it stays inside the container's
 * PackageManager.
 */
public final class GoogleUidCompat {

    public static String[] withGoogleComponents(String[] resolved, List<String> installedGoogle) {
        if (resolved == null || resolved.length == 0 || installedGoogle == null
                || installedGoogle.isEmpty()) {
            return resolved;
        }
        Set<String> merged = new LinkedHashSet<>();
        for (String packageName : resolved) {
            merged.add(packageName);
        }
        merged.addAll(installedGoogle);
        if (merged.size() == resolved.length) {
            return resolved;
        }
        return new ArrayList<>(merged).toArray(new String[0]);
    }

    private GoogleUidCompat() {
    }
}
