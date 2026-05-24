package com.salkcoding.oswl.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HomeController 단위 테스트")
class HomeControllerTest {

    private final HomeController controller = new HomeController();

    @Test
    @DisplayName("home: /login으로 리다이렉트한다")
    void home_redirectsToLogin() {
        String view = controller.home(null);

        assertThat(view).isEqualTo("redirect:/login");
    }
}
