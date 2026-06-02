package com.salkcoding.oswl.auth.security;

import java.io.Serializable;

/**
 * Session-safe OTP pending state — no password hash or credentials.
 */
public record OtpPendingIdentity(
        Long userId,
        String email,
        String displayName,
        boolean mustChangePassword
) implements Serializable {

    public static OtpPendingIdentity from(OswlUserPrincipal principal) {
        return new OtpPendingIdentity(
                principal.getUserId(),
                principal.getUsername(),
                principal.getDisplayName(),
                principal.isMustChangePassword());
    }
}
