package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Maps OIDC identity to an existing OsWL user by email. No auto-provisioning.
 */
@Component
@ConditionalOnProperty(prefix = "oswl.oauth2", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class OswlOidcUserService extends OidcUserService {

    private final UserRepository userRepository;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);
        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException("OIDC token missing email claim");
        }
        User user = userRepository.findByEmail(email.strip().toLowerCase())
                .or(() -> userRepository.findByEmail(email.strip()))
                .orElseThrow(() -> new OAuth2AuthenticationException(
                        "No OsWL account for " + email + ". Ask an administrator to create your user first."));
        if (!user.isEnabled()) {
            throw new OAuth2AuthenticationException("Account is disabled");
        }
        return oidcUser;
    }
}
