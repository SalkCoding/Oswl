package com.salkcoding.oswl.auth.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("ChangePasswordService unit tests")
class ChangePasswordServiceTest {

    @Mock UserRepository  userRepository;
    @Mock AuditLogService auditLogService;

    // Real encoder to generate/verify BCrypt hashes
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @InjectMocks ChangePasswordService changePasswordService;

    private void injectEncoder() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                changePasswordService, "passwordEncoder", encoder);
    }

    /** Only stubs getPasswordHash() — callers add further stubs as needed. */
    private User userWithPassword(String rawPassword) {
        User user = mock(User.class);
        when(user.getPasswordHash()).thenReturn(encoder.encode(rawPassword));
        return user;
    }

    @Test
    @DisplayName("changePassword success: updates hash and clears mustChangePassword flag")
    void changePassword_success() {
        injectEncoder();
        User user = userWithPassword("oldPass123!");
        when(user.getEmail()).thenReturn("user@example.com");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        changePasswordService.changePassword(1L, "oldPass123!", "newPass456!");

        verify(user).setPasswordHash(argThat(h -> encoder.matches("newPass456!", h)));
        verify(user).setMustChangePassword(false);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("changePassword with wrong current password throws CURRENT_PASSWORD_WRONG")
    void changePassword_wrongCurrent_throws() {
        injectEncoder();
        User user = userWithPassword("realPass123!");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() ->
                changePasswordService.changePassword(1L, "wrongPass!", "newPass456!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CURRENT_PASSWORD_WRONG");
    }

    @Test
    @DisplayName("changePassword with same password throws SAME_AS_CURRENT")
    void changePassword_sameAsCurrent_throws() {
        injectEncoder();
        User user = userWithPassword("same123!");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() ->
                changePasswordService.changePassword(1L, "same123!", "same123!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SAME_AS_CURRENT");
    }

    @Test
    @DisplayName("changePassword for unknown userId throws IllegalStateException")
    void changePassword_userNotFound_throws() {
        injectEncoder();
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                changePasswordService.changePassword(99L, "any", "any2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("99");
    }
}
