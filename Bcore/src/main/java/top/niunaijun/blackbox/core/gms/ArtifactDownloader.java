package top.niunaijun.blackbox.core.gms;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads one pinned artifact into the cache directory and only publishes it
 * after the size and SHA-256 match the manifest. A failed download never leaves
 * a file the installer could pick up later.
 */
public final class ArtifactDownloader implements ArtifactProvider {

    public interface Transport {
        InputStream open(String url) throws IOException;
    }

    public enum Failure {
        NETWORK,
        VERIFY,
        IO
    }

    public static final class DownloadException extends Exception {
        public final Failure failure;

        DownloadException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }
    }

    static final Transport HTTP = url -> {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        return connection.getInputStream();
    };

    private final Transport transport;
    private final File cacheDir;

    public ArtifactDownloader(Transport transport, File cacheDir) {
        this.transport = transport;
        this.cacheDir = cacheDir;
    }

    @Override
    public File fetch(MicroGArtifacts.Artifact artifact) throws DownloadException {
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new DownloadException(Failure.IO, "cannot create cache dir", null);
        }
        File target = new File(cacheDir, artifact.fileName);
        File part = new File(cacheDir, artifact.fileName + ".part");
        if (part.exists() && !part.delete()) {
            throw new DownloadException(Failure.IO, "cannot clear stale partial file", null);
        }
        try (InputStream in = transport.open(artifact.url());
             OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            part.delete();
            throw new DownloadException(Failure.NETWORK, "download failed: " + e.getMessage(), e);
        }

        Sha256Verifier.Result result = Sha256Verifier.verify(part, artifact.size, artifact.sha256);
        if (result != Sha256Verifier.Result.OK) {
            part.delete();
            throw new DownloadException(Failure.VERIFY, "artifact check failed: " + result, null);
        }
        if (target.exists() && !target.delete()) {
            part.delete();
            throw new DownloadException(Failure.IO, "cannot replace cached artifact", null);
        }
        if (!part.renameTo(target)) {
            part.delete();
            throw new DownloadException(Failure.IO, "cannot move artifact into place", null);
        }
        return target;
    }
}
