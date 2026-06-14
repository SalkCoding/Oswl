package com.salkcoding.oswl.auth.security;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@DisplayName("ForwardedHttpsRequestMatcher unit tests")
class ForwardedHttpsRequestMatcherTest {

    private final ForwardedHttpsRequestMatcher matcher = new ForwardedHttpsRequestMatcher();

    @Test
    @DisplayName("matches X-Forwarded-Proto https")
    void matchesForwardedProto() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.addHeader("X-Forwarded-Proto", "https");
        assertThat(matcher.matches(request)).isTrue();
    }

    @Test
    @DisplayName("does not match plain HTTP without forwarded header")
    void doesNotMatchPlainHttp() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setSecure(false);
        assertThat(matcher.matches(request)).isFalse();
    }
}
