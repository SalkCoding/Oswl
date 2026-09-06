package com.salkcoding.oswl.service.ingest.parser;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.ingest.MavenBomVersionResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;

@Slf4j
@RequiredArgsConstructor
public class MavenPomParser {
    private final MavenBomVersionResolver bomVersionResolver;

    public String getDirectChildText(Element parent, String tag) {
        Element c = getDirectChild(parent, tag);
        return c != null ? c.getTextContent().trim() : null;
    }

    public Element getDirectChild(Element parent, String tag) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e && tag.equals(e.getTagName())) return e;
        }
        return null;
    }

    public String resolveProp(String raw, Map<String, String> props) {
        if (raw == null || !raw.contains("${")) return raw;
        Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String val = props.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public List<ScanPayload.ComponentPayload> parseSingleMavenPom(Path pomFile, Path projectDir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(false);
            Document doc = dbf.newDocumentBuilder().parse(pomFile.toFile());
            doc.getDocumentElement().normalize();
            Element project = doc.getDocumentElement();
            Map<String, String> props = new HashMap<>();
            String projectVersion = getDirectChildText(project, "version");
            Element parent = getDirectChild(project, "parent");
            if (parent != null) {
                String parentVersion = getDirectChildText(parent, "version");
                if (projectVersion == null) projectVersion = parentVersion;
                String parentGroup = getDirectChildText(parent, "groupId");
                if (parentGroup != null) props.put("project.parent.groupId", parentGroup);
                if (parentVersion != null) props.put("project.parent.version", parentVersion);
            }
            if (projectVersion != null) {
                props.put("project.version", projectVersion);
                props.put("version", projectVersion);
                props.put("pom.version", projectVersion);
            }
            Element properties = getDirectChild(project, "properties");
            if (properties != null) {
                NodeList children = properties.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element pe) {
                        props.put(pe.getTagName(), pe.getTextContent().trim());
                    }
                }
            }
            Element depsRoot = getDirectChild(project, "dependencies");
            if (depsRoot == null) return comps;
            NodeList deps = depsRoot.getChildNodes();
            for (int i = 0; i < deps.getLength(); i++) {
                if (!(deps.item(i) instanceof Element dep) || !"dependency".equals(dep.getTagName())) continue;
                String groupId    = resolveProp(getDirectChildText(dep, "groupId"), props);
                String artifactId = resolveProp(getDirectChildText(dep, "artifactId"), props);
                String version    = resolveProp(getDirectChildText(dep, "version"), props);
                String scope      = getDirectChildText(dep, "scope");
                if (groupId == null || artifactId == null) continue;
                // Non-runtime scopes are tagged (not dropped) so the UI can badge and default-filter them.
                comps.add(buildComponent(groupId + ":" + artifactId, version, "MAVEN")
                        .withScope(normalizeMavenScope(scope)));
            }
            log.debug("[DependencyParser][Maven] Parsed {} deps from {}", comps.size(), pomFile);
            comps = bomVersionResolver.enrichComponentVersions(projectDir, comps);
        } catch (Exception e) {
            log.warn("[DependencyParser][Maven] Failed to parse {}: {}", pomFile, e.getMessage());
        }
        return comps;
    }

    public static String normalizeMavenScope(String scope) {
        if (scope == null || scope.isBlank()) return null;
        String s = scope.trim().toLowerCase();
        return switch (s) {
            case "compile", "runtime", "import" -> null;
            default -> s; // test, provided, system
        };
    }

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
