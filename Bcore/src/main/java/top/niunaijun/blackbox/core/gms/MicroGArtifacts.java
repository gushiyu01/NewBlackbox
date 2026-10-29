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
