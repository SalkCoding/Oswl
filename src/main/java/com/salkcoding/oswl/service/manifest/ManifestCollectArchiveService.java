package com.salkcoding.oswl.service.manifest;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds manifest zip archives using {@link ManifestCollectRules} (CLI-equivalent input).
 */
@Service
public class ManifestCollectArchiveService {

  public Path zipProjectManifests(Path projectRoot) throws IOException {
    List<String> rels = ManifestCollectRules.collectRelativePaths(projectRoot);
    if (rels.isEmpty()) {
      throw new IllegalArgumentException("No manifest files found under " + projectRoot);
    }
    Path zip = Files.createTempFile("oswl-manifests-", ".zip");
    try (OutputStream out = Files.newOutputStream(zip);
         ZipOutputStream zos = new ZipOutputStream(out)) {
      for (String rel : rels) {
        ZipEntry entry = new ZipEntry(rel);
        zos.putNextEntry(entry);
        Files.copy(projectRoot.resolve(rel.replace('/', File.separatorChar)), zos);
        zos.closeEntry();
      }
    }
    return zip;
  }

  public Path extractZipToTemp(Path zipFile) throws IOException {
    Path root = Files.createTempDirectory("oswl-manifest-extract-");
    try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName().replace('\\', '/');
        if (name.contains("..")) {
          throw new SecurityException("Invalid zip entry: " + name);
        }
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) {
          throw new SecurityException("Zip entry escapes root: " + name);
        }
        Files.createDirectories(target.getParent());
        Files.copy(zis, target);
        zis.closeEntry();
      }
    }
    return root;
  }
}
