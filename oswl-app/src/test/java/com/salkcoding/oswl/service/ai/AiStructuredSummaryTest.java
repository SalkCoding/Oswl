package com.salkcoding.oswl.service.ai;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.FAST)
class AiStructuredSummaryTest {

    @Test
    @DisplayName("priority와 action이 display 문자열에 포함된다")
    void formatForDisplay_includesPriorityAndAction() {
        String text = AiStructuredSummary.formatForDisplay(Map.of(
                "summary", "Remote code execution risk",
                "recommendedAction", "Upgrade log4j-core to 2.17.1+",
                "priority", "P0"));

        assertThat(text).contains("[P0]");
        assertThat(text).contains("Remote code execution");
        assertThat(text).contains("Action: Upgrade log4j-core");
    }
}
