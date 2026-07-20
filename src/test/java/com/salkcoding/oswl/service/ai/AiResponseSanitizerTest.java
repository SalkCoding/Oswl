package com.salkcoding.oswl.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiResponseSanitizerTest {

    @Test
    @DisplayName("think 블록과 임의 키 JSON envelope가 함께 와도 평문만 남는다")
    void thinkBlockPlusUnknownEnvelope_unwrappedToPlainText() {
        String raw = "<think>\nThe user wants a trend summary. Let me reason...\n</think>\n"
                + "{\"trend\": \"Security issues increased by 3.\","
                + " \"new_risk\": \"lodash now has a critical CVE.\","
                + " \"priority_action\": \"Upgrade lodash to 4.17.21.\"}";

        String text = AiResponseSanitizer.sanitizePlainText(raw);

        assertThat(text).doesNotContain("<think>", "</think>", "{", "}", "trend");
        assertThat(text).isEqualTo("Security issues increased by 3. "
                + "lodash now has a critical CVE. Upgrade lodash to 4.17.21.");
    }

    @Test
    @DisplayName("chat template이 여는 태그를 미리 채운 경우 닫는 태그 앞의 reasoning을 제거한다")
    void closingThinkTagOnly_dropsReasoningPrefix() {
        String raw = "Let me think about this scan... 3 new CVEs.\n</think>\n"
                + "Security issues increased. Upgrade lodash first.";

        String text = AiResponseSanitizer.sanitizePlainText(raw);

        assertThat(text).isEqualTo("Security issues increased. Upgrade lodash first.");
    }

    @Test
    @DisplayName("max_tokens로 잘린 닫히지 않은 think 블록의 꼬리를 제거한다")
    void unclosedThinkBlock_dropsTruncatedTail() {
        String raw = "Actual answer sentence.\n<think>\nreasoning cut off by max_toke";

        String text = AiResponseSanitizer.sanitizePlainText(raw);

        assertThat(text).isEqualTo("Actual answer sentence.");
    }

    @Test
    @DisplayName("전체가 reasoning뿐이면 빈 문자열을 반환한다")
    void onlyReasoning_returnsBlank() {
        String text = AiResponseSanitizer.sanitizePlainText("<think>just reasoning</think>");

        assertThat(text).isBlank();
    }

    @Test
    @DisplayName("임의 키 JSON envelope만 와도 값들을 평문으로 푼다")
    void unknownEnvelopeWithoutThink_flattened() {
        String text = AiResponseSanitizer.sanitizePlainText(
                "{\"trend\": \"Compliance risk increased.\", \"priority_action\": \"Review GPL components.\"}");

        assertThat(text).isEqualTo("Compliance risk increased. Review GPL components.");
    }

    @Test
    @DisplayName("알려진 필드(summary + recommendedAction)는 기존 규칙대로 추출한다")
    void knownFields_preferredOverFallback() {
        String text = AiResponseSanitizer.sanitizePlainText(
                "{\"summary\": \"RCE risk.\", \"recommendedAction\": \"Upgrade to 2.17.1.\", \"priority\": \"P0\"}");

        assertThat(text).isEqualTo("RCE risk. Upgrade to 2.17.1.");
    }

    @Test
    @DisplayName("일반 평문은 변경하지 않는다")
    void plainProse_unchanged() {
        String text = AiResponseSanitizer.sanitizePlainText("Security posture is stable. Upgrade lodash first.");

        assertThat(text).isEqualTo("Security posture is stable. Upgrade lodash first.");
    }

    @Test
    @DisplayName("펜스로 감싼 JSON 배열은 기존처럼 unwrap한다")
    void fencedJsonArray_stillUnwrapped() {
        String text = AiResponseSanitizer.sanitizePlainText(
                "```json\n[\"First point.\", \"Second point.\"]\n```");

        assertThat(text).isEqualTo("First point.\nSecond point.");
    }

    @Test
    @DisplayName("null과 blank는 그대로 통과시킨다")
    void nullAndBlank_passthrough() {
        assertThat(AiResponseSanitizer.sanitizePlainText(null)).isNull();
        assertThat(AiResponseSanitizer.sanitizePlainText("   ")).isEqualTo("   ");
    }
}
