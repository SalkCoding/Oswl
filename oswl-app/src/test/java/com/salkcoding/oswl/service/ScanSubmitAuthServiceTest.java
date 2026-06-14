package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.enums.ApiKeyType;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("ScanSubmitAuthService")
class ScanSubmitAuthServiceTest {

    @Mock UserDetailsService userDetailsService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ProjectAccessService projectAccessService;

    @InjectMocks ScanSubmitAuthService scanSubmitAuthService;

    private OswlUserPrincipal principal;

    @BeforeEach
    void setUp() {
        principal = new OswlUserPrincipal(
                1L, "ci@company.com", "{noop}secret", "CI Bot",
                false, true, List.of(), Set.of(), Set.of(Permission.SCAN_SUBMIT), false);
    }

    @Test
    @DisplayName("standard key requires valid password")
    void standardKey_validPassword() {
        when(userDetailsService.loadUserByUsername("ci@company.com")).thenReturn(principal);
        when(passwordEncoder.matches("pass", principal.getPassword())).thenReturn(true);
        doNothing().when(projectAccessService).assertCanSubmitScan(1L, 1L);

        var result = scanSubmitAuthService.authenticate(null, 1L, "ci@company.com", "pass");

        assertThat(result.actorEmail()).isEqualTo("ci@company.com");
    }

    @Test
    @DisplayName("machine key skips password")
    void machineKey_noPassword() {
        User user = User.builder().id(1L).email("ci@company.com").build();
        ApiKey key = ApiKey.builder()
                .keyType(ApiKeyType.MACHINE)
                .boundUser(user)
                .tokenPrefix("oswl_testprefix12")
                .tokenHash("hash")
                .project(Project.builder().id(1L).name("demo").build())
                .build();
        when(userDetailsService.loadUserByUsername("ci@company.com")).thenReturn(principal);
        doNothing().when(projectAccessService).assertCanSubmitScan(1L, 1L);

        var result = scanSubmitAuthService.authenticate(key, 1L, null, null);

        assertThat(result.actorEmail()).isEqualTo("ci@company.com");
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("unknown user throws UnauthorizedException")
    void unknownUser() {
        when(userDetailsService.loadUserByUsername("x@y.com"))
                .thenThrow(new UsernameNotFoundException("nope"));

        assertThatThrownBy(() -> scanSubmitAuthService.authenticate(null, 1L, "x@y.com", "p"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("missing SCAN_SUBMIT throws ForbiddenException")
    void missingPermission() {
        OswlUserPrincipal noPerm = new OswlUserPrincipal(
                2L, "ro@co.com", "{noop}x", "RO", false, true, List.of(), Set.of(), Set.of(), false);
        when(userDetailsService.loadUserByUsername("ro@co.com")).thenReturn(noPerm);
        when(passwordEncoder.matches("p", noPerm.getPassword())).thenReturn(true);

        assertThatThrownBy(() -> scanSubmitAuthService.authenticate(null, 1L, "ro@co.com", "p"))
                .isInstanceOf(ForbiddenException.class);
    }
}
