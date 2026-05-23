package com.salkcoding.oswl.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlossaryController 단위 테스트")
class GlossaryControllerTest {

    private final GlossaryController controller = new GlossaryController();

    @Test
    @DisplayName("glossary: glossary/index 뷰를 반환한다")
    void glossary_returnsGlossaryView() {
        String view = controller.glossary();

        assertThat(view).isEqualTo("glossary/index");
    }
}
