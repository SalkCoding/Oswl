package com.salkcoding.oswl.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("GlobalExceptionHandler 단위 테스트")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── handleNotFound ───────────────────────────────────────────────────────

    @Test
    @DisplayName("handleNotFound: error/404 뷰를 반환한다")
    void handleNotFound_returnsErrorView() {
        String view = handler.handleNotFound();

        assertThat(view).isEqualTo("error/404");
    }

    // ── handleIllegalArgument ─────────────────────────────────────────────────

    @Test
    @DisplayName("handleIllegalArgument: JSON Accept 헤더이면 404 JSON을 반환한다")
    void handleIllegalArgument_jsonAccept_returnsJsonBody() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Accept")).thenReturn("application/json");

        Object result = handler.handleIllegalArgument(
                new IllegalArgumentException("not found"), request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> entity = (ResponseEntity<Map<String, Object>>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(entity.getBody()).containsEntry("status", 404);
        assertThat(entity.getBody()).containsKey("error");
    }

    @Test
    @DisplayName("handleIllegalArgument: JSON Accept가 아니면 빈 404 응답을 반환한다")
    void handleIllegalArgument_nonJsonAccept_returnsEmptyBody() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Accept")).thenReturn("text/html");

        Object result = handler.handleIllegalArgument(
                new IllegalArgumentException("not found"), request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(entity.getBody()).isNull();
    }

    @Test
    @DisplayName("handleIllegalArgument: Accept 헤더가 null이면 빈 404 응답을 반환한다")
    void handleIllegalArgument_nullAccept_returnsEmptyBody() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Accept")).thenReturn(null);

        Object result = handler.handleIllegalArgument(
                new IllegalArgumentException("oops"), request);

        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── handleIllegalState ────────────────────────────────────────────────────

    @Test
    @DisplayName("handleIllegalState: JSON Accept 헤더이면 400 JSON을 반환한다")
    void handleIllegalState_jsonAccept_returnsJsonBody() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Accept")).thenReturn("application/json; charset=utf-8");

        Object result = handler.handleIllegalState(
                new IllegalStateException("bad state"), request);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> entity = (ResponseEntity<Map<String, Object>>) result;
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(entity.getBody()).containsEntry("status", 400);
    }

    @Test
    @DisplayName("handleIllegalState: JSON Accept가 아니면 빈 400 응답을 반환한다")
    void handleIllegalState_nonJsonAccept_returnsEmptyBody() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Accept")).thenReturn("text/plain");

        ResponseEntity<?> result = (ResponseEntity<?>) handler.handleIllegalState(
                new IllegalStateException("bad"), request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody()).isNull();
    }

    // ── handleUnexpected ──────────────────────────────────────────────────────

    @Test
    @DisplayName("handleUnexpected: error/500 뷰를 반환한다")
    void handleUnexpected_returnsErrorView() {
        String view = handler.handleUnexpected(new RuntimeException("crash"));

        assertThat(view).isEqualTo("error/500");
    }
}
