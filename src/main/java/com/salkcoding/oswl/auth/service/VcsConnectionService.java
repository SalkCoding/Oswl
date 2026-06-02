package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.AddVcsConnectionRequest;
import com.salkcoding.oswl.auth.dto.VcsConnectionDto;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.aop.Auditable;
import com.salkcoding.oswl.client.VcsTokenValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VcsConnectionService {

    private final UserVcsConnectionRepository repository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final AuditLogService auditLogService;
    private final VcsTokenValidator vcsTokenValidator;

    @Transactional(readOnly = true)
    public List<VcsConnectionDto> findByCurrentUser(Long userId) {
        return repository.findByUserIdAndActiveTrue(userId).stream()
                .map(c -> VcsConnectionDto.builder()
                        .id(c.getId())
                        .provider(c.getProvider())
                        .serverUrl(c.getServerUrl())
                        .vcsUsername(c.getVcsUsername())
                        .createdAt(c.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    @Auditable(action = "VCS.CONNECT", targetType = "VCS_CONNECTION",
               targetIdExpr = "#result.id.toString()",
               targetNameExpr = "#result.provider + (#result.serverUrl != null ? ' / ' + #result.serverUrl : '')")
    public VcsConnectionDto addConnection(Long userId, AddVcsConnectionRequest request) {
        normalizeVcsRequest(request);
        vcsTokenValidator.validate(request.getProvider(), request.getServerUrl(),
                request.getAccessToken(), request.getVcsUsername());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        repository.findByUserIdAndProviderAndActiveTrue(userId, request.getProvider())
                .ifPresent(existing -> existing.setActive(false));

        UserVcsConnection conn = UserVcsConnection.builder()
                .user(user)
                .provider(request.getProvider())
                .serverUrl(request.getServerUrl())
                .accessTokenEncrypted(encryptionService.encrypt(request.getAccessToken()))
                .vcsUsername(request.getVcsUsername() != null ? request.getVcsUsername() : "")
                .active(true)
                .build();
        UserVcsConnection saved = repository.save(conn);
        log.info("[VCS] Connected userId={} provider={} server={}",
                userId, saved.getProvider(),
                saved.getServerUrl() != null ? saved.getServerUrl() : "cloud");
        return VcsConnectionDto.builder()
                .id(saved.getId())
                .provider(saved.getProvider())
                .serverUrl(saved.getServerUrl())
                .vcsUsername(saved.getVcsUsername())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Transactional
    public void removeConnection(Long connectionId, Long requestingUserId) {
        UserVcsConnection conn = repository.findById(connectionId)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found."));
        if (!conn.getUser().getId().equals(requestingUserId)) {
            throw new SecurityException("You can only delete your own connections.");
        }
        conn.setActive(false);
        log.info("[VCS] Disconnected userId={} connectionId={} provider={}",
                requestingUserId, connectionId, conn.getProvider());
        auditLogService.log("VCS.DISCONNECT", "VCS_CONNECTION", connectionId.toString(),
                conn.getProvider() + (conn.getServerUrl() != null ? " / " + conn.getServerUrl() : ""), null);
    }

    private void normalizeVcsRequest(AddVcsConnectionRequest request) {
        switch (request.getProvider()) {
            case BITBUCKET -> normalizeBitbucketRequest(request);
            case GITHUB    -> normalizeGitHubRequest(request);
            case GITLAB    -> normalizeGitLabRequest(request);
        }
    }

    private void normalizeGitHubRequest(AddVcsConnectionRequest request) {
        String serverUrl = request.getServerUrl();
        if (serverUrl == null || serverUrl.isBlank()) {
            request.setServerUrl(null);
            return;
        }
        request.setServerUrl(serverUrl.trim().replaceAll("/+$", ""));
    }

    private void normalizeGitLabRequest(AddVcsConnectionRequest request) {
        String serverUrl = request.getServerUrl();
        if (serverUrl == null || serverUrl.isBlank()) {
            request.setServerUrl(null);
            return;
        }
        request.setServerUrl(serverUrl.trim().replaceAll("/+$", ""));
    }

    private void normalizeBitbucketRequest(AddVcsConnectionRequest request) {
        if (request.getProvider() != VcsProvider.BITBUCKET) return;
        String serverUrl = request.getServerUrl();
        boolean cloud = serverUrl == null || serverUrl.isBlank()
                || serverUrl.trim().replaceAll("/+$", "").equalsIgnoreCase("https://bitbucket.org")
                || serverUrl.trim().replaceAll("/+$", "").equalsIgnoreCase("http://bitbucket.org");
        if (cloud) {
            request.setServerUrl(null);
            String vcsUsername = request.getVcsUsername();
            if (vcsUsername == null || vcsUsername.isBlank()) {
                throw new IllegalArgumentException(
                        "Workspace slug is required for Bitbucket Cloud. Enter your workspace slug (e.g. salkcoding).");
            }
            vcsUsername = vcsUsername.trim();
            // email|slug stored as-is; slug-only and email-only are also accepted.
            request.setVcsUsername(vcsUsername);
        } else {
            if (request.getServerUrl().isBlank()) {
                throw new IllegalArgumentException(
                        "Server URL is required for Bitbucket Server / Data Center (e.g. https://bitbucket.example.com).");
            }
            request.setVcsUsername(null);
        }
    }
}
