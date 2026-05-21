package com.salkcoding.oswl.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * 성공한 모든 인증의 원격 IP를 캡체하여 {@link LastLoginIpStore}에
 * 사용자 email 주소를 키로 저장한다.
 *
 * {@link OswlSessionExpiredStrategy}가 나중에 이 정보를 읽어 밀려난
 * 사용자에게 강제 로그아웃을 트리거한 IP를 알린다.
 */
@Component
@RequiredArgsConstructor
public class LoginIpCaptureListener {

    private final LastLoginIpStore lastLoginIpStore;

    @EventListener
    public void onAuthSuccess(AuthenticationSuccessEvent event) {
        if (!(event.getAuthentication().getDetails() instanceof WebAuthenticationDetails details)) {
            return;
        }
        String ip = details.getRemoteAddress();
        if (ip == null || ip.isBlank()) {
            return;
        }
        // IPv6 루프백을 IPv4로 정규화
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            ip = "127.0.0.1";
        }
        String email = event.getAuthentication().getName();
        lastLoginIpStore.put(email, ip);
    }
}
