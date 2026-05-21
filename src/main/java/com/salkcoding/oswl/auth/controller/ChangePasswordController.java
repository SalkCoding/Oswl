package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.ChangePasswordRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.ChangePasswordService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * POST /api/change-password — 관리자 초대 사용자의 강제 비밀번호 변경을 처리한다.
 *
 * <p>보안 속성:
 * <ul>
 *   <li>Spring Security의 {@code CookieCsrfTokenRepository}를 통한 CSRF 보호 (X-XSRF-TOKEN 헤더).</li>
 *   <li>OTP 통과 후 완전한 인증 필요. {@link com.salkcoding.oswl.auth.security.MustChangePasswordFilter}가
 *       성공하기 전까지 다른 URL에 대한 접근을 차단한다.</li>
 *   <li>쓰기 전에 현재 비밀번호를 검증한다.</li>
 *   <li>세션 고정 공격로 부터 보호하기 위해 성공 후 세션 ID를 교체한다.</li>
 *   <li>재로그인 없이도 필터 플래그가 즉시 해제되도록 SecurityContext를 업데이트한다.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChangePasswordController {

    private final ChangePasswordService  changePasswordService;
    private final UserDetailsService     userDetailsService;
    private final SecuritySettingService securitySettingService;

    @PostMapping("/api/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @RequestBody ChangePasswordRequest req,
            @AuthenticationPrincipal OswlUserPrincipal principal,
            HttpServletRequest request) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "인증되지 않았습니다."));
        }

        // ── 입력값 검증 ──────────────────────────────────────────────────────

        String currentPw = req.getCurrentPassword() != null ? req.getCurrentPassword() : "";
        String newPw     = req.getNewPassword()     != null ? req.getNewPassword()     : "";
        String confirmPw = req.getConfirmPassword() != null ? req.getConfirmPassword() : "";

        int minLen = securitySettingService.getOrCreate().getMinPasswordLength();
        if (newPw.length() < minLen) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "새 비밀번호는 최소 " + minLen + "자 이상이어야 합니다."));
        }
        if (!newPw.equals(confirmPw)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "비밀번호가 일치하지 않습니다."));
        }

        // ── 서비스에서 비즈니스 로직 (DB 쓰기, 감사 로그) ──────────────────────

        try {
            changePasswordService.changePassword(principal.getUserId(), currentPw, newPw);
        } catch (IllegalArgumentException e) {
            return switch (e.getMessage()) {
                case "CURRENT_PASSWORD_WRONG" ->
                        ResponseEntity.badRequest()
                                .body(Map.of("message", "현재 비밀번호가 올바르지 않습니다."));
                case "SAME_AS_CURRENT" ->
                        ResponseEntity.badRequest()
                                .body(Map.of("message", "새 비밀번호는 현재 비밀번호와 달라야 합니다."));
                default -> {
                    log.error("[ChangePassword] 사용자 {}의 예상치 못한 검증 오류: {}",
                            principal.getUsername(), e.getMessage());
                    yield ResponseEntity.status(500).body(Map.of("message", "예상치 못한 오류가 발생했습니다."));
                }
            };
        } catch (Exception e) {
            log.error("[ChangePassword] 사용자 {} 오류: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message", "예상치 못한 오류가 발생했습니다."));
        }

        // ── 프린시퍼를 재구성하고 SecurityContext 업데이트 ─────────────────────
        // 재로그인 없이도 mustChangePassword == false가 즉시 반영되도록 DB에서 재로드.

        OswlUserPrincipal updated =
                (OswlUserPrincipal) userDetailsService.loadUserByUsername(principal.getUsername());

        UsernamePasswordAuthenticationToken newAuth =
                new UsernamePasswordAuthenticationToken(updated, null, updated.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(newAuth);
        SecurityContextHolder.setContext(context);

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }

        // 자격증명 변경 후 세션 고정 공격을 방지하기 위해 세션 ID 교체.
        request.changeSessionId();

        log.info("[ChangePassword] 사용자 {}의 비밀번호가 성공적으로 변경되었습니다.", principal.getUsername());
        return ResponseEntity.ok(Map.of("redirectUrl", "/projects"));
    }
}
