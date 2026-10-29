package top.niunaijun.blackbox.core.gms;

import android.content.Context;

import java.io.File;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

public final class BlackBoxMicroGInstaller {

    public static MicroGInstaller create(Context context) {
        File cache = new File(context.getCacheDir(), "microg");
        ArtifactDownloader downloader = new ArtifactDownloader(ArtifactDownloader.HTTP, cache);
        return new MicroGInstaller(downloader, new MicroGInstaller.Host() {
            @Override
            public boolean isInstalled(String packageName, int userId) {
                return BlackBoxCore.get().isInstalled(packageName, userId);
            }

            @Override
            public InstallResult install(File apk, int userId) {
                return BlackBoxCore.get().installPackageAsUser(apk, userId);
            }

            @Override
            public void uninstall(String packageName, int userId) {
                BlackBoxCore.get().uninstallPackageAsUser(packageName, userId);
            }
        });
    }

    private BlackBoxMicroGInstaller() {
    }
}
