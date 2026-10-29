package top.niunaijun.blackbox.core.gms;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * Installs the pinned MicroG artifacts into one virtual user, rolling back the
 * packages this run added if any step fails so the user never ends up with a
 * half-installed Google stack.
 */
public final class MicroGInstaller {

    public interface Host {
        boolean isInstalled(String packageName, int userId);

        InstallResult install(File apk, int userId);

        void uninstall(String packageName, int userId);
    }

    public enum Status {
        INSTALLED,
        SKIPPED,
        FAILED
    }

    public static final class Step {
        public final String packageName;
        public final Status status;
        public final String message;

        Step(String packageName, Status status, String message) {
            this.packageName = packageName;
            this.status = status;
            this.message = message;
        }
    }

    public static final class Outcome {
        public final List<Step> steps;

        Outcome(List<Step> steps) {
            this.steps = Collections.unmodifiableList(steps);
        }

        public boolean success() {
            for (Step step : steps) {
                if (step.status == Status.FAILED) {
                    return false;
                }
            }
            return true;
        }
    }

    private final ArtifactProvider provider;
    private final Host host;

    public MicroGInstaller(ArtifactProvider provider, Host host) {
        this.provider = provider;
        this.host = host;
    }

    public Outcome install(int userId) {
        List<Step> steps = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (MicroGArtifacts.Artifact artifact : MicroGArtifacts.artifacts()) {
            if (host.isInstalled(artifact.packageName, userId)) {
                steps.add(new Step(artifact.packageName, Status.SKIPPED, "already installed"));
                continue;
            }
            try {
                File apk = provider.fetch(artifact);
                InstallResult result = host.install(apk, userId);
                if (result == null || !result.success) {
                    steps.add(new Step(artifact.packageName, Status.FAILED,
                            result == null ? "install returned nothing" : result.msg));
                    rollback(added, userId);
                    return new Outcome(steps);
                }
                added.add(artifact.packageName);
                steps.add(new Step(artifact.packageName, Status.INSTALLED, ""));
            } catch (ArtifactDownloader.DownloadException e) {
                steps.add(new Step(artifact.packageName, Status.FAILED,
                        e.failure + ": " + e.getMessage()));
                rollback(added, userId);
                return new Outcome(steps);
            }
        }
        return new Outcome(steps);
    }

    private void rollback(List<String> added, int userId) {
        for (int i = added.size() - 1; i >= 0; i--) {
            host.uninstall(added.get(i), userId);
        }
    }
}
