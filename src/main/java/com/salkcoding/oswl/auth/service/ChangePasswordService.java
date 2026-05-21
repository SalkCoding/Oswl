package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 초대 사용자의 강제 비밀번호 변경 플로우를 처리한다.
 *
 * <p>보안 불변조건:
 * <ul>
 *   <li>변경 적용 전에 현재(임시) 비밀번호를 검증해야 한다.</li>
 *   <li>새 비밀번호는 현재 비밀번호와 동일하면 안 된다.</li>
 *   <li>{@code mustChangePassword}는 해시 업데이트와 동일한 트랜잭션 내에서 원자적으로 해제된다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangePasswordService {

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    /**
     * {@code currentPassword}를 검증하고 {@code newPassword}를 인코딩하여 저장하고,
     * {@code mustChangePassword} 플래그를 제거한다 — 단일 트랜잭션 내에서 수행.
     *
     * @throws IllegalArgumentException 검증 실패 시 {@code CURRENT_PASSWORD_WRONG} 또는
     *                                  {@code SAME_AS_CURRENT} 코드 내포.
     * @throws IllegalStateException    사용자 행을 찾지 못할 때.
     */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("사용자를 찾지 못함: " + userId));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            log.warn("[ChangePassword] userId={}의 현재 비밀번호가 올바르지 않음", userId);
            throw new IllegalArgumentException("CURRENT_PASSWORD_WRONG");
        }

        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            log.debug("[ChangePassword] 거부: userId={}의 새 비밀번호가 현재와 동일함", userId);
            throw new IllegalArgumentException("SAME_AS_CURRENT");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        // 트랜잭션 내 Dirty-check flush — 명시적 save가 없어도 되지만
        // 명확성을 위해 유지.
        userRepository.save(user);

        auditLogService.log("AUTH.PASSWORD_CHANGE", "USER",
                userId.toString(), user.getEmail(), "강제 비밀번호 변경 완료");

        log.info("[ChangePassword] userId={}의 비밀번호를 업데이트함", userId);
    }
}
