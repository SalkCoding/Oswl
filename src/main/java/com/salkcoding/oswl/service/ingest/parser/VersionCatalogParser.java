package com.salkcoding.oswl.service.ingest.parser;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.w3c.dom.*;

@Slf4j
public class VersionCatalogParser {

    public List<ScanPayload.ComponentPayload> parseVersionCatalogs(Path dir, String repoName, ManifestIndex index) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        List<Path> catalogs = new ArrayList<>();
        // Original logic used Files.walk(dir, 3); keep that depth limit on top of the shared index.
        for (Path p : new ManifestDiscovery().indexBySuffix(index, ".versions.toml")) {
            if (index.root().relativize(p).getNameCount() <= 3) {
                catalogs.add(p);
            }
        }
        if (catalogs.isEmpty()) { log.debug("[DependencyParser][Gradle] No *.versions.toml in '{}'", repoName); return comps; }
        for (Path catalog : catalogs) {
            try {
                List<String> lines = Files.readAllLines(catalog, StandardCharsets.UTF_8);
                Map<String, String> versions = new LinkedHashMap<>();
                boolean inVersions = false, inLibraries = false;
                for (String rawLine : lines) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.equals("[versions]"))  { inVersions = true;  inLibraries = false; continue; }
                    if (line.equals("[libraries]")) { inLibraries = true; inVersions = false;  continue; }
                    if (line.startsWith("["))       { inVersions = false; inLibraries = false; continue; }
                    if (inVersions) {
                        Matcher vm = Pattern.compile("^([\\w.\\-]+)\\s*=\\s*[\"']([^\"']+)[\"']").matcher(line);
                        if (vm.find()) versions.put(vm.group(1), vm.group(2));
                    } else if (inLibraries) {
                        // Short form: alias = "group:artifact:version"
                        Matcher shortM = Pattern.compile(
                                "^[\\w.\\-]+\\s*=\\s*[\"']([\\w.\\-]+:[\\w.\\-]+):([\\w.+\\-]+)[\"']").matcher(line);
                        if (shortM.find()) { comps.add(buildComponent(shortM.group(1), shortM.group(2), "MAVEN")); continue; }
                        // Table form: alias = { module = "g:a", version.ref = "key" | version = "x" }
                        Matcher tableM = Pattern.compile("^[\\w.\\-]+\\s*=\\s*\\{(.+)\\}").matcher(line);
                        if (tableM.find()) {
                            String body = tableM.group(1);
                            Matcher modM = Pattern.compile("module\\s*=\\s*[\"']([\\w.\\-]+:[\\w.\\-]+)[\"']").matcher(body);
                            if (modM.find()) {
                                String module = modM.group(1); String version = null;
                                Matcher vRefM = Pattern.compile("version\\.ref\\s*=\\s*[\"']([\\w.\\-]+)[\"']").matcher(body);
                                Matcher vM    = Pattern.compile("(?<![.\\w])version\\s*=\\s*[\"']([\\w.+\\-]+)[\"']").matcher(body);
                                if (vRefM.find()) version = versions.get(vRefM.group(1));
                                else if (vM.find()) version = vM.group(1);
                                if (version != null && !version.isBlank()) comps.add(buildComponent(module, version, "MAVEN"));
                            }
                        }
                    }
                }
                log.info("[DependencyParser][Gradle] Version catalog {} → {} entries", catalog.getFileName(), comps.size());
            } catch (Exception e) {
                log.warn("[DependencyParser][Gradle] Failed to parse {}: {}", catalog, e.getMessage());
            }
        }
        return comps;
    }

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
