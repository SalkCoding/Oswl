package com.salkcoding.oswl.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.util.Map;

/**
 * Serves the public /oss-notices page. Not an API — a plain view controller, so springdoc/
 * controller-spec annotations do not apply here.
 *
 * <p>Reads the OSS version manifest generated at build time by the {@code generateOssManifest}
 * Gradle task so the page always shows the versions build.gradle actually resolved, instead
 * of hand-maintained literals.
 */
@Slf4j
@Controller
public class OssNoticesController {

    private static final String MANIFEST_RESOURCE = "oss-versions.json";
    // Matches the rest of the codebase's convention (GitHubService, AirgappedSnapshotService,
    // etc.) of a self-instantiated ObjectMapper rather than the Spring-managed bean.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @GetMapping("/oss-notices")
    public String ossNotices(Model model) {
        model.addAttribute("ossVersions", loadVersions());
        return "oss-notices/index";
    }

    private Map<String, String> loadVersions() {
        var resource = new ClassPathResource(MANIFEST_RESOURCE);
        if (!resource.exists()) {
            log.warn("[OssNotices] {} not found on classpath (run without './gradlew build'?) — versions will show as '-'",
                    MANIFEST_RESOURCE);
            return Map.of();
        }
        try (var in = resource.getInputStream()) {
            return MAPPER.readValue(in, new TypeReference<>() {});
        } catch (IOException e) {
            log.warn("[OssNotices] Failed to read {}: {} — versions will show as '-'", MANIFEST_RESOURCE, e.getMessage());
            return Map.of();
        }
    }
}
