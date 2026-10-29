package top.niunaijun.blackbox.core.gms;

import java.io.File;

/** Supplies a verified APK for an artifact. Implemented by {@link ArtifactDownloader}. */
public interface ArtifactProvider {

    File fetch(MicroGArtifacts.Artifact artifact) throws ArtifactDownloader.DownloadException;
}
