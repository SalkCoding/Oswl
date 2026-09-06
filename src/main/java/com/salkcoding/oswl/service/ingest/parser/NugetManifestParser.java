package com.salkcoding.oswl.service.ingest.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.ingest.DependencyManifestParserService.ParseResult;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;

@Slf4j
public class NugetManifestParser {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<ScanPayload.ComponentPayload> parseNuGetLockFile(Path dir, String repoName) {
        try {
            JsonNode root = new ObjectMapper().readTree(dir.resolve("packages.lock.json").toFile());
            Set<String> seen = new LinkedHashSet<>();
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            JsonNode deps = root.path("dependencies");
            if (!deps.isMissingNode()) {
                deps.properties().forEach(fw ->
                    fw.getValue().properties().forEach(pkg -> {
                        String name = pkg.getKey();
                        String ver  = pkg.getValue().path("resolved").asText(null);
                        if (name != null && ver != null && !ver.isBlank() && seen.add(name)) {
                            comps.add(buildComponent(name, ver, "NUGET"));
                        }
                    })
                );
            }
            log.info("[DependencyParser][NuGet] Parsed {} components from packages.lock.json in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][NuGet] Failed to parse packages.lock.json: {}", e.getMessage());
            return null;
        }
    }

    public List<ScanPayload.ComponentPayload> parseDotNetListJson(String json, String repoName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            Set<String> seen = new LinkedHashSet<>();
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            for (JsonNode project : root.path("projects")) {
                for (JsonNode fw : project.path("frameworks")) {
                    for (String section : new String[]{"topLevelPackages", "transitivePackages"}) {
                        for (JsonNode pkg : fw.path(section)) {
                            String id = pkg.path("id").asText(null);
                            String ver = pkg.path("resolvedVersion").asText(null);
                            if (id == null || id.isBlank() || ver == null || ver.isBlank()) {
                                continue;
                            }
                            if (seen.add(id + ":" + ver)) {
                                comps.add(buildComponent(id, ver, "NUGET"));
                            }
                        }
                    }
                }
            }
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][NuGet] Failed to parse dotnet list JSON for '{}': {}", repoName, e.getMessage());
            return List.of();
        }
    }

    public ParseResult parseNuGetStatic(Path dir, String repoName, ManifestIndex index) {
        Set<String> seen = new LinkedHashSet<>();
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Map<String, String> propsVersions = buildNuGetPropsVersionIndex(index);
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(false);
            for (Path props : new ManifestDiscovery().indexByNames(index, "Directory.Packages.props")) {
                mergeDirectoryPackageVersions(dbf, props, propsVersions);
            }
            List<Path> targets = new ArrayList<>();
            targets.addAll(new ManifestDiscovery().indexBySuffix(index, ".csproj"));
            targets.addAll(new ManifestDiscovery().indexByNames(index, "packages.config"));
            for (Path f : targets) {
                try {
                    Document doc = dbf.newDocumentBuilder().parse(f.toFile());
                    if (f.getFileName().toString().equals("packages.config")) {
                        mergeNuGetPackageConfig(doc, seen, comps);
                    } else {
                        mergeNuGetCsproj(doc, seen, comps, propsVersions);
                    }
                } catch (Exception ignored) {}
            }
            log.info("[DependencyParser][NuGet] Parsed {} components from {} manifest file(s) in '{}'",
                    comps.size(), targets.size(), repoName);
        } catch (Exception e) {
            log.error("[DependencyParser][NuGet] Failed to parse .csproj/packages.config: {}", e.getMessage());
        }
        return new ParseResult("NUGET", comps);
    }

    public ParseResult parseNuGetStatic(Path dir, String repoName) {
        return parseNuGetStatic(dir, repoName, new ManifestDiscovery().buildIndex(dir));
    }

    public Map<String, String> buildNuGetPropsVersionIndex(ManifestIndex index) {
        Map<String, String> propsIndex = new LinkedHashMap<>();
        Pattern propVersion = Pattern.compile("<([\\w.]+)>\\s*([\\d][^<]*)\\s*</\\1>");
        for (Path props : new ManifestDiscovery().indexBySuffix(index, ".props")) {
            try {
                String content = Files.readString(props, StandardCharsets.UTF_8);
                Matcher m = propVersion.matcher(content);
                while (m.find()) {
                    String key = m.group(1);
                    String val = m.group(2).trim();
                    if (!val.isBlank() && !val.contains("$(")) {
                        propsIndex.putIfAbsent(key, val);
                    }
                }
            } catch (Exception e) {
                log.debug("[DependencyParser][NuGet] props scan failed for {}: {}", props, e.getMessage());
            }
        }
        return propsIndex;
    }

    public void mergeDirectoryPackageVersions(
            DocumentBuilderFactory dbf, Path propsFile, Map<String, String> propsVersions) {
        try {
            Document doc = dbf.newDocumentBuilder().parse(propsFile.toFile());
            NodeList versions = doc.getElementsByTagName("PackageVersion");
            for (int i = 0; i < versions.getLength(); i++) {
                Element el = (Element) versions.item(i);
                String id = el.getAttribute("Include");
                String ver = el.getAttribute("Version");
                if (!id.isBlank() && !ver.isBlank()) {
                    propsVersions.put(id, resolveNuGetProperty(ver, propsVersions));
                }
            }
        } catch (Exception e) {
            log.debug("[DependencyParser][NuGet] Directory.Packages.props parse failed: {}", e.getMessage());
        }
    }

    public void mergeNuGetCsproj(
            Document doc, Set<String> seen, List<ScanPayload.ComponentPayload> comps,
            Map<String, String> propsVersions) {
        NodeList refs = doc.getElementsByTagName("PackageReference");
        for (int i = 0; i < refs.getLength(); i++) {
            Element ref = (Element) refs.item(i);
            if ("Remove".equalsIgnoreCase(ref.getAttribute("Update"))) {
                continue;
            }
            String name = firstNonBlank(ref.getAttribute("Include"), ref.getAttribute("Update"));
            if (name.isBlank() || name.contains("@(")) {
                continue;
            }
            String ver = firstNonBlank(
                    ref.getAttribute("Version"),
                    getDirectChildText(ref, "Version"));
            ver = resolveNuGetProperty(ver, propsVersions);
            if (propsVersions.containsKey(name) && (ver == null || ver.isBlank() || ver.startsWith("$("))) {
                ver = propsVersions.get(name);
            }
            String key = name + ":" + (ver != null ? ver : "");
            if (seen.add(key)) {
                comps.add(buildComponent(name, ver == null || ver.isBlank() ? null : ver, "NUGET"));
            }
        }
    }

    public void mergeNuGetPackageConfig(
            Document doc, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        NodeList refs = doc.getElementsByTagName("package");
        for (int i = 0; i < refs.getLength(); i++) {
            Element ref = (Element) refs.item(i);
            String name = firstNonBlank(ref.getAttribute("id"), ref.getAttribute("Include"));
            String ver = firstNonBlank(ref.getAttribute("version"), ref.getAttribute("Version"));
            if (!name.isBlank() && seen.add(name + ":" + ver)) {
                comps.add(buildComponent(name, ver.isBlank() ? null : ver, "NUGET"));
            }
        }
    }

    public String resolveNuGetProperty(String raw, Map<String, String> propsVersions) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Matcher m = Pattern.compile("\\$\\(([^)]+)\\)").matcher(raw.trim());
        if (!m.matches()) {
            return raw.trim();
        }
        String resolved = propsVersions.get(m.group(1));
        return resolved != null ? resolved : raw.trim();
    }

    public static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
    private String getDirectChildText(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && tag.equals(child.getTagName()))
                return child.getTextContent().trim();
        }
        return null;
    }

}
