package com.salkcoding.oswl.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.BindingResult;

/**
 * Password rules from {@link com.salkcoding.oswl.auth.entity.SecuritySetting}
 * (minimum length). Used by setup, invite, and password-change flows.
 */
@Service
@RequiredArgsConstructor
public class PasswordPolicyService {

    private final SecuritySettingService securitySettingService;

    public int getMinLength() {
        return securitySettingService.getOrCreate().getMinPasswordLength();
    }

    public boolean meetsMinLength(String password) {
        if (password == null) {
            return false;
        }
        return password.length() >= getMinLength();
    }

    /**
     * Adds a field error when the password is shorter than the configured minimum.
     */
    public void validateMinLength(String password, BindingResult bindingResult, String field) {
        int minLen = getMinLength();
        if (!meetsMinLength(password)) {
            bindingResult.rejectValue(field, "password.tooShort",
                    new Object[]{minLen},
                    "Password must be at least {0} characters long.");
        }
    }
}
