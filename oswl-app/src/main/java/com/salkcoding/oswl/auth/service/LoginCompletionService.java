package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Records post-login side effects once authentication is fully complete. */
@Service
@RequiredArgsConstructor
public class LoginCompletionService {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public void recordSuccessfulLogin(String email) {
        userRepository.updateLastLoginAt(email, LocalDateTime.now());
        auditLogService.log("AUTH.LOGIN_SUCCESS", "AUTH", null, null, null);
    }
}
