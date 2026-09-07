package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.enums.UserThemeMode;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserThemeService {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public UserThemeMode getTheme(Long userId) {
        return userRepository.findById(userId)
                .map(User::getTheme)
                .map(theme -> theme != null ? theme : UserThemeMode.LIGHT)
                .orElse(UserThemeMode.LIGHT);
    }

    @Transactional
    public void updateTheme(OswlUserPrincipal principal, UserThemeMode mode) {
        if (mode == null) {
            mode = UserThemeMode.LIGHT;
        }
        User user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        UserThemeMode previous = user.getTheme();
        user.setTheme(mode);
        auditLogService.log("USER.UPDATE_THEME", "USER",
                user.getId().toString(), user.getEmail(),
                previous + " -> " + mode);
    }
}
