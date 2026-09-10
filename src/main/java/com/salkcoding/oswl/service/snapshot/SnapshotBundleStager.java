package com.salkcoding.oswl.service.snapshot;

import com.salkcoding.oswl.exception.InvalidRequestException;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

/** Bounded disk staging; ZIP validation completes before the caller opens a write transaction. */
@Slf4j
final class SnapshotBundleStager implements AutoCloseable {
    private static final long ENTRY_LIMIT = 200L * 1024 * 1024;
    private static final long BUNDLE_LIMIT = 512L * 1024 * 1024;
    private static final int LINE_LIMIT = 1024 * 1024;
    private final Map<String, Path> files = new LinkedHashMap<>();
    private final List<Path> temporaryFiles = new ArrayList<>();

    Map<String, Path> files() { return files; }

    void read(InputStream input, Set<String> known) throws IOException {
        long bundleBytes = 0;
        int entries = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkInterrupted();
                if (++entries > 128) throw new InvalidRequestException("Snapshot bundle has too many ZIP entries.");
                String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                boolean recognized = !entry.isDirectory() && (name.equals("meta.json") || known.contains(name));
                Path file = null;
                if (recognized) {
                    if (files.containsKey(name)) throw new InvalidRequestException("Duplicate snapshot entry: " + name);
                    file = Files.createTempFile("oswl-snapshot-", ".stage");
                    temporaryFiles.add(file);
                    files.put(name, file);
                }
                long entryBytes = 0;
                try (OutputStream out = file == null ? OutputStream.nullOutputStream() : Files.newOutputStream(file)) {
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        checkInterrupted();
                        entryBytes += n;
                        bundleBytes += n;
                        long limit = name.equals("meta.json") ? LINE_LIMIT : ENTRY_LIMIT;
                        if (entryBytes > limit || bundleBytes > BUNDLE_LIMIT)
                            throw new InvalidRequestException("Snapshot bundle exceeds the decompressed size limit.");
                        out.write(buffer, 0, n);
                    }
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0 && entryBytes > compressed * 100.0)
                    throw new InvalidRequestException("Snapshot entry has a suspicious compression ratio: " + name);
            }
        }
    }

    static void forEachLine(Path path, Consumer<String> consumer) {
        try (Reader reader = new BufferedReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                if (c == '\n') {
                    checkInterrupted();
                    consumer.accept(line.toString());
                    line.setLength(0);
                } else {
                    if (line.length() >= LINE_LIMIT) throw new InvalidRequestException("Snapshot JSON line exceeds the size limit.");
                    line.append((char) c);
                }
            }
            checkInterrupted();
            if (!line.isEmpty()) consumer.accept(line.toString());
        } catch (IOException e) {
            throw new InvalidRequestException("Cannot read staged snapshot: " + e.getMessage());
        }
    }

    static String checksum(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[64 * 1024];
            int n;
            while ((n = input.read(bytes)) != -1) { checkInterrupted(); digest.update(bytes, 0, n); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new InvalidRequestException("Cannot verify snapshot checksum: " + e.getMessage());
        }
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new InvalidRequestException("Snapshot import was interrupted.");
    }

    @Override public void close() {
        for (Path file : temporaryFiles) {
            try { Files.deleteIfExists(file); }
            catch (IOException e) { log.warn("[Snapshot] Cannot remove staging file {}: {}", file, e.getMessage()); }
        }
    }
}
