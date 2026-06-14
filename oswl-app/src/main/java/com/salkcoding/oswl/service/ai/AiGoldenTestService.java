package com.salkcoding.oswl.service.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs lightweight golden checks against prompt rendering (no live LLM calls).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGoldenTestService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiPromptTemplateService promptTemplates;

    public Map<String, Object> runAll() {
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        try {
            List<GoldenCase> cases = loadCases();
            for (GoldenCase c : cases) {
                boolean ok = runCase(c);
                if (ok) passed++;
                results.add(Map.of(
                        "id", c.id(),
                        "passed", ok,
                        "description", c.description()));
            }
        } catch (Exception e) {
            log.warn("[AI] Golden test load failed: {}", e.getMessage());
            return Map.of("passed", 0, "total", 0, "error", e.getMessage(), "results", List.of());
        }
        return Map.of(
                "passed", passed,
                "total", results.size(),
                "results", results);
    }

    private boolean runCase(GoldenCase c) {
        try {
            String rendered = promptTemplates.render(c.templateKey(), c.vars());
            if (c.mustContain() != null) {
                for (String needle : c.mustContain()) {
                    if (!rendered.contains(needle)) return false;
                }
            }
            return !rendered.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private List<GoldenCase> loadCases() throws Exception {
        var resource = new ClassPathResource("ai/golden-tests.json");
        if (!resource.exists()) return List.of();
        return MAPPER.readValue(resource.getInputStream(), new TypeReference<>() {});
    }

    private record GoldenCase(String id, String description, String templateKey,
                              Map<String, Object> vars, List<String> mustContain) {}
}
