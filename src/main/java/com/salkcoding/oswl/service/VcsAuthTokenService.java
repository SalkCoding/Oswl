package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * Resolves VCS credentials for the current user from the HTTP session (GitHub PAT map)
 * or persisted {@link UserVcsConnection} records (Settings → VCS).
 */
@Service
@RequiredArgsConstructor
public class VcsAuthTokenService {

    static final String SESSION_GITHUB_TOKENS = "githubTokens";

    private final UserVcsConnectionRepository vcsConnectionRepository;
    private final EncryptionService           encryptionService;
    private final SessionCipherService        sessionCipher;

    /**
     * GitHub token: session PAT (Git Integration) first, then Settings → VCS connection.
     *
     * @param repoOwner optional repo owner login to pick the matching session PAT
     */
    public String resolveGithubToken(HttpSession session, Long userId, String repoOwner) {
        String fromSession = getGithubTokenFromSession(session, repoOwner);
        if (fromSession != null) return fromSession;
        return getTokenFromConnection(userId, VcsProvider.GITHUB);
    }

    public String getTokenFromConnection(Long userId, VcsProvider provider) {
        if (userId == null) return null;
        return vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(userId, provider)
                .map(this::decryptToken)
                .orElse(null);
    }

    public UserVcsConnection getConnection(Long userId, VcsProvider provider) {
        if (userId == null) return null;
        return vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(userId, provider)
                .orElse(null);
    }

    private String decryptToken(UserVcsConnection conn) {
        return encryptionService.decrypt(conn.getAccessTokenEncrypted());
    }

    @SuppressWarnings("unchecked")
    private String getGithubTokenFromSession(HttpSession session, String repoOwner) {
        if (session == null) return null;
        Object obj = session.getAttribute(SESSION_GITHUB_TOKENS);
        if (!(obj instanceof Map<?, ?> tokens) || tokens.isEmpty()) return null;
        Map<String, String> map = (Map<String, String>) tokens;
        String encrypted = (repoOwner != null && map.containsKey(repoOwner))
                ? map.get(repoOwner)
                : map.values().iterator().next();
        return sessionCipher.decrypt(encrypted);
    }
}
