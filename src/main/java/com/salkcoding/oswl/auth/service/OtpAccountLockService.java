package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks user accounts after repeated OTP verification failures.
 *
 * A separate bean so the {@code @Transactional} lock runs through the Spring proxy:
 * a private {@code @Transactional} self-invocation inside {@link OtpService} never starts
 * a transaction, so the disabled flag would silently fail to persist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpAccountLockService {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    /** Disables the account (idempotent) and writes an audit entry. */
    @Transactional
    public void lockAccount(String email, int failedAttempts) {
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.isEnabled()) {
                user.setEnabled(false);
                userRepository.save(user);
                auditLogService.logAnonymous(email, "USER.DEACTIVATE", "USER",
                        user.getId().toString(), email,
                        "Auto-locked after " + failedAttempts + " OTP failures");
                log.warn("[OTP] Account '{}' locked after {} OTP failures", email, failedAttempts);
            }
        });
    }
}
