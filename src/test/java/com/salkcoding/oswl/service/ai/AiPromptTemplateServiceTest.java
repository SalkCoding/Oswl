package com.salkcoding.oswl.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AiPromptTemplateService")
class AiPromptTemplateServiceTest {

    private AiPromptTemplateService service;

    @BeforeEach
    void setUp() {
        service = new AiPromptTemplateService(new DefaultResourceLoader(), "classpath:ai/prompts.properties");
        service.reloadWithLocale("en");
    }

    @Test
    @DisplayName("CVE 단일 프롬프트에 플레이스홀더가 치환된다")
    void cveSingle_replacesPlaceholders() {
        String prompt = service.cveSingle("CVE-2024-001", "CRITICAL", 9.8, "log4j 2.14.1");

        assertThat(prompt).contains("CVE-2024-001");
        assertThat(prompt).contains("CRITICAL");
        assertThat(prompt).contains("9.8");
        assertThat(prompt).contains("log4j 2.14.1");
        assertThat(prompt).doesNotContain("{cveId}");
    }

    @Test
    @DisplayName("증가/감소 방향이 트렌드 프롬프트에 반영된다")
    void securityTrend_usesDirectionWords() {
        String increased = service.securityTrend("MyApp", 3, "1.0, 2.0", "delta details");
        String decreased = service.securityTrend("MyApp", -2, "1.0, 2.0", "delta details");

        assertThat(increased).contains("increased by 3");
        assertThat(decreased).contains("decreased by 2");
        assertThat(increased).contains("delta details");
    }

    @Test
    @DisplayName("배치 CVE 프롬프트에 항목 목록이 포함된다")
    void batchCvePrompt_includesItems() {
        String prompt = service.batchCvePrompt(List.of(
                cveReq("CVE-1", "HIGH", "lib-a 1.0"),
                cveReq("CVE-2", "LOW", "lib-b 2.0")));

        assertThat(prompt).contains("JSON array");
        assertThat(prompt).contains("1. id=CVE-1");
        assertThat(prompt).contains("2. id=CVE-2");
        assertThat(prompt).contains("fixVersion=");
    }

    @Test
    @DisplayName("시스템 프롬프트와 모델 파라미터가 로드된다")
    void getSystemPrompt_isNotBlank() {
        assertThat(service.getSystemPrompt()).isNotBlank();
        assertThat(service.getTemperature()).isBetween(0.0, 1.0);
        assertThat(service.getMaxTokens()).isGreaterThan(0);
    }

    private static AiAnalysisService.CveSummaryRequest cveReq(String id, String sev, String comp) {
        return new AiAnalysisService.CveSummaryRequest(
                id, sev, 7.5, comp, "title", "summary", "1.2.3", "CWE-1",
                "CVSS:3.1/AV:N", "direct", "PATCHABLE");
    }
}
