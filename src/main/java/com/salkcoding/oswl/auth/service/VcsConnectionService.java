package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.AddVcsConnectionRequest;
import com.salkcoding.oswl.auth.dto.VcsConnectionDto;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VcsConnectionService {

    private final UserVcsConnectionRepository repository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;

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
    public VcsConnectionDto addConnection(Long userId, AddVcsConnectionRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        // Disable any existing active connection for the same provider (one per provider per user)
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
                .orElseThrow(() -> new IllegalArgumentException("연결을 찾을 수 없습니다."));
        if (!conn.getUser().getId().equals(requestingUserId)) {
            throw new SecurityException("본인의 연결만 삭제할 수 있습니다.");
        }
        conn.setActive(false);
    }
}
