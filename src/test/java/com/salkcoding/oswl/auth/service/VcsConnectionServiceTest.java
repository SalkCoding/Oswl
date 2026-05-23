package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.AddVcsConnectionRequest;
import com.salkcoding.oswl.auth.dto.VcsConnectionDto;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.client.VcsTokenValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VcsConnectionService unit tests")
class VcsConnectionServiceTest {

    @Mock UserVcsConnectionRepository repository;
    @Mock UserRepository              userRepository;
    @Mock EncryptionService           encryptionService;
    @Mock AuditLogService             auditLogService;
    @Mock VcsTokenValidator           vcsTokenValidator;

    @InjectMocks VcsConnectionService vcsConnectionService;

    // ── findByCurrentUser ────────────────────────────────────────────

    @Test
    @DisplayName("findByCurrentUser returns mapped DTOs")
    void findByCurrentUser_returnsDtos() {
        UserVcsConnection conn = buildConnection(10L, VcsProvider.GITHUB, null);
        when(repository.findByUserIdAndActiveTrue(1L)).thenReturn(List.of(conn));

        List<VcsConnectionDto> result = vcsConnectionService.findByCurrentUser(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProvider()).isEqualTo(VcsProvider.GITHUB);
    }

    @Test
    @DisplayName("findByCurrentUser returns empty list when no connections")
    void findByCurrentUser_empty() {
        when(repository.findByUserIdAndActiveTrue(1L)).thenReturn(List.of());
        assertThat(vcsConnectionService.findByCurrentUser(1L)).isEmpty();
    }

    // ── addConnection ────────────────────────────────────────────────

    @Test
    @DisplayName("addConnection validates token, encrypts it, and saves")
    void addConnection_validatesAndEncryptsToken() {
        User user = mock(User.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(repository.findByUserIdAndProviderAndActiveTrue(1L, VcsProvider.GITHUB))
                .thenReturn(Optional.empty());
        when(encryptionService.encrypt("tok123")).thenReturn("enc_tok");

        UserVcsConnection saved = buildConnection(20L, VcsProvider.GITHUB, null);
        when(repository.save(any())).thenReturn(saved);

        AddVcsConnectionRequest req = new AddVcsConnectionRequest();
        req.setProvider(VcsProvider.GITHUB);
        req.setAccessToken("tok123");
        req.setVcsUsername("octocat");

        VcsConnectionDto dto = vcsConnectionService.addConnection(1L, req);

        verify(vcsTokenValidator).validate(VcsProvider.GITHUB, null, "tok123", "octocat");
        verify(encryptionService).encrypt("tok123");
        assertThat(dto.getProvider()).isEqualTo(VcsProvider.GITHUB);
    }

    @Test
    @DisplayName("addConnection deactivates existing connection for the same provider")
    void addConnection_deactivatesExistingConnection() {
        User user = mock(User.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserVcsConnection existing = mock(UserVcsConnection.class);
        when(repository.findByUserIdAndProviderAndActiveTrue(1L, VcsProvider.GITLAB))
                .thenReturn(Optional.of(existing));
        when(encryptionService.encrypt(anyString())).thenReturn("enc");

        UserVcsConnection saved = buildConnection(30L, VcsProvider.GITLAB, null);
        when(repository.save(any())).thenReturn(saved);

        AddVcsConnectionRequest req = new AddVcsConnectionRequest();
        req.setProvider(VcsProvider.GITLAB);
        req.setAccessToken("glpat-abc");

        vcsConnectionService.addConnection(1L, req);

        verify(existing).setActive(false);
    }

    @Test
    @DisplayName("addConnection with invalid token propagates validator exception")
    void addConnection_invalidToken_throws() {
        doThrow(new IllegalStateException("GitHub token is invalid"))
                .when(vcsTokenValidator).validate(any(), any(), any(), any());

        AddVcsConnectionRequest req = new AddVcsConnectionRequest();
        req.setProvider(VcsProvider.GITHUB);
        req.setAccessToken("bad-token");

        assertThatThrownBy(() -> vcsConnectionService.addConnection(1L, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GitHub token is invalid");
    }

    // ── removeConnection ──────────────────────────────────────────────

    @Test
    @DisplayName("removeConnection deactivates the connection when owner matches")
    void removeConnection_success() {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(1L);
        UserVcsConnection conn = mock(UserVcsConnection.class);
        when(conn.getUser()).thenReturn(owner);
        when(conn.getProvider()).thenReturn(VcsProvider.GITHUB);
        when(repository.findById(10L)).thenReturn(Optional.of(conn));

        vcsConnectionService.removeConnection(10L, 1L);

        verify(conn).setActive(false);
    }

    @Test
    @DisplayName("removeConnection throws SecurityException when requester is not the owner")
    void removeConnection_notOwner_throws() {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(2L); // different user
        UserVcsConnection conn = mock(UserVcsConnection.class);
        when(conn.getUser()).thenReturn(owner);
        when(repository.findById(10L)).thenReturn(Optional.of(conn));

        assertThatThrownBy(() -> vcsConnectionService.removeConnection(10L, 1L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("removeConnection throws when connection does not exist")
    void removeConnection_notFound_throws() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vcsConnectionService.removeConnection(99L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Connection not found");
    }

    // ── helper ───────────────────────────────────────────────────────

    private UserVcsConnection buildConnection(Long id, VcsProvider provider, String serverUrl) {
        UserVcsConnection c = mock(UserVcsConnection.class);
        when(c.getId()).thenReturn(id);
        when(c.getProvider()).thenReturn(provider);
        when(c.getServerUrl()).thenReturn(serverUrl);
        when(c.getVcsUsername()).thenReturn("octocat");
        when(c.getCreatedAt()).thenReturn(LocalDateTime.now());
        return c;
    }
}
