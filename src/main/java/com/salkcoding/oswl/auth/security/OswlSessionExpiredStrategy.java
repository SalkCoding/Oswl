package com.salkcoding.oswl.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 동일 계정이 다른 위치에서 로그인하여 세션이 만료될 때
 * {@link org.springframework.security.web.session.ConcurrentSessionFilter}가 호출한다.
 *
 * 브라우저 클라이언트는 /login?displaced=true&from=<ip>로 리다이렉트된다.
 * API/AJAX 클라이언트는 401 JSON을 반환받는다.
 */
@Component
@RequiredArgsConstructor
public class OswlSessionExpiredStrategy implements SessionInformationExpiredStrategy {

    private final LastLoginIpStore lastLoginIpStore;

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException {
        HttpServletRequest  request  = event.getRequest();
        HttpServletResponse response = event.getResponse();

        // 이 사용자의 마지막 성공 로그인에서 밀려난 IP 확인
        Object principal = event.getSessionInformation().getPrincipal();
        String email = principal instanceof UserDetails ud ? ud.getUsername() : String.valueOf(principal);
        String displacingIp = lastLoginIpStore.get(email);

        // API/AJAX 요청이마 401 JSON 반환
        String accept = request.getHeader("Accept");
        String uri    = request.getRequestURI();
        if (uri.startsWith("/api/") || (accept != null && accept.contains("application/json"))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"\uc138\uc158 \ub9cc\ub8cc — \ub3d9\uc2dc \ub85c\uadf8\uc778 \uac10\uc9c0\",\"status\":401}");
            return;
        }

        // 리다이렉트 URL 생성
        String redirectUrl = "/login?displaced=true";
        if (displacingIp != null && !displacingIp.isBlank()) {
            redirectUrl += "&from=" + URLEncoder.encode(displacingIp, StandardCharsets.UTF_8);
        }
        response.sendRedirect(redirectUrl);
    }
}
