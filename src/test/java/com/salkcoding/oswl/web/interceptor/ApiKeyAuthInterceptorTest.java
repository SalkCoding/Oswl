package com.salkcoding.oswl.web.interceptor;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyAuthInterceptor 단위 테스트")
class ApiKeyAuthInterceptorTest {

    @Mock ApiKeyService apiKeyService;
    @InjectMocks ApiKeyAuthInterceptor interceptor;

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock Object handler;

    @Test
    @DisplayName("preHandle: Authorization 헤더가 없으면 401을 반환하고 false를 반환한다")
    void preHandle_missingAuthHeader_returns401() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
    }

    @Test
    @DisplayName("preHandle: Bearer로 시작하지 않는 헤더는 401을 반환한다")
    void preHandle_nonBearerHeader_returns401() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic abc123");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
    }

    @Test
    @DisplayName("preHandle: 빈 Bearer 토큰은 401을 반환한다")
    void preHandle_emptyBearerToken_returns401() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer ");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
    }

    @Test
    @DisplayName("preHandle: 유효한 API 키는 요청 속성을 설정하고 true를 반환한다")
    void preHandle_validApiKey_setsAttributesAndReturnsTrue() throws Exception {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(10L);
        ApiKey apiKey = mock(ApiKey.class);
        when(apiKey.getProject()).thenReturn(project);

        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(apiKeyService.validateAndRecord("valid-token")).thenReturn(apiKey);

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isTrue();
        verify(request).setAttribute(ApiKeyAuthInterceptor.ATTR_API_KEY, apiKey);
        verify(request).setAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID, 10L);
        verifyNoInteractions(response);
    }

    @Test
    @DisplayName("preHandle: ApiKeyService가 UnauthorizedException을 던지면 401을 반환한다")
    void preHandle_unauthorizedException_returns401() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");
        when(apiKeyService.validateAndRecord("bad-token"))
                .thenThrow(new UnauthorizedException("Invalid API key"));

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
    }
}
