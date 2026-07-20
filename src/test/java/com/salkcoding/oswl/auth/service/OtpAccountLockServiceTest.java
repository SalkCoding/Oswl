package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OtpAccountLockService unit tests")
class OtpAccountLockServiceTest {

    @Mock UserRepository  userRepository;
    @Mock AuditLogService auditLogService;

    @InjectMocks OtpAccountLockService otpAccountLockService;

    @Test
    @DisplayName("lockAccount disables an enabled account, persists it, and writes an audit entry")
    void lockAccount_enabledUser_disabledSavedAndAudited() {
        User user = mock(User.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getId()).thenReturn(1L);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        otpAccountLockService.lockAccount("user@example.com", 5);

        verify(user).setEnabled(false);
        verify(userRepository).save(user);
        verify(auditLogService).logAnonymous(eq("user@example.com"), eq("USER.DEACTIVATE"), eq("USER"),
                eq("1"), eq("user@example.com"), anyString());
    }

    @Test
    @DisplayName("lockAccount is a no-op for an already disabled account")
    void lockAccount_alreadyDisabled_noOp() {
        User user = mock(User.class);
        when(user.isEnabled()).thenReturn(false);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        otpAccountLockService.lockAccount("user@example.com", 5);

        verify(user, never()).setEnabled(false);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("lockAccount is a no-op when the user does not exist")
    void lockAccount_unknownUser_noOp() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        otpAccountLockService.lockAccount("ghost@example.com", 5);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
    }
}
