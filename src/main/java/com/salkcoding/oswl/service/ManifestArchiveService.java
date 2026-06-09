package com.salkcoding.oswl.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts CLI-uploaded manifest archives into a guarded temporary directory.
 */
@Slf4j
@Service
public class ManifestArchiveService {

    @Value("${oswl.scan.parse.max-archive-bytes:52428800}")
    private long maxArchiveBytes;

    /**
     * Extracts a zip archive to a fresh temp directory and returns its root.
     * Rejects path traversal, symlinks, and oversize uploads.
     */
    public Path extractToTempDir(MultipartFile archive) throws IOException {
        if (archive == null || archive.isEmpty()) {
            throw new IllegalArgumentException("Manifest archive is required");
        }
        if (archive.getSize() > maxArchiveBytes) {
            throw new IllegalArgumentException("Manifest archive exceeds size limit");
        }

        Path tempRoot = Files.createTempDirectory("oswl-cli-parse-");
        Path rootReal = tempRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);

        try (InputStream in = archive.getInputStream();
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String rawName = entry.getName().replace('\\', '/');
                if (rawName.isBlank() || rawName.contains("..") || rawName.startsWith("/")) {
                    throw new SecurityException("Invalid zip entry path: " + rawName);
                }
                Path target = rootReal.resolve(rawName).normalize();
                if (!target.startsWith(rootReal)) {
                    throw new SecurityException("Zip entry escapes extract root: " + rawName);
                }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target);
                zis.closeEntry();
            }
        } catch (RuntimeException | IOException e) {
            deleteQuietly(rootReal);
            throw e instanceof IOException io ? io : new IOException(e);
        }

        log.debug("[ManifestArchive] Extracted {} bytes to {}", archive.getSize(), rootReal);
        return rootReal;
    }

    public void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException e) {
            log.warn("[ManifestArchive] Cleanup failed for {}: {}", dir, e.getMessage());
        }
    }

    /** Generates a short label for parse logging when no repo name is available. */
    public static String randomLabel() {
        return "cli-upload-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
