package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.ExternalApiSetting;
import com.salkcoding.oswl.repository.ExternalApiSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettingsController unit tests")
class SettingsControllerTest {

    @Mock ExternalApiSettingRepository externalApiSettingRepository;
    @Mock Model model;

    @InjectMocks SettingsController controller;

    private OswlUserPrincipal admin() {
        return new OswlUserPrincipal(1L, "admin@test.com", "hash", "Admin", true, true,
                List.of(), Set.of(), Set.of(), false);
    }

    private OswlUserPrincipal user(Set<Permission> perms) {
        return new OswlUserPrincipal(2L, "user@test.com", "hash", "User", false, true,
                List.of(), Set.of(), perms, false);
    }

    // -- No ExternalApiSetting --

    @Test
    @DisplayName("No ExternalApiSetting: githubConfigured=false")
    void noExternalSetting_githubNotConfigured() {
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        OswlUserPrincipal p = admin();

        String view = controller.settings(p, null, model);

        assertThat(view).isEqualTo("settings/index");
        verify(model).addAttribute("githubConfigured", false);
    }

    // -- ExternalApiSetting exists --

    @Test
    @DisplayName("ExternalApiSetting with github configured: githubConfigured=true")
    void externalSetting_githubConfigured() {
        ExternalApiSetting setting = mock(ExternalApiSetting.class);
        when(setting.isGithubConfigured()).thenReturn(true);
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(setting));
        OswlUserPrincipal p = admin();

        String view = controller.settings(p, null, model);

        verify(model).addAttribute("githubConfigured", true);
        assertThat(view).isEqualTo("settings/index");
    }

    // -- Tab selection --

    @Test
    @DisplayName("Null tab: defaults to first accessible tab")
    void nullTab_defaultsToFirstTab() {
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        OswlUserPrincipal p = admin();

        controller.settings(p, null, model);

        verify(model).addAttribute(eq("activeTab"), eq("admin"));
    }

    @Test
    @DisplayName("Valid tab parameter: sets activeTab to that tab")
    void validTab_setsActiveTab() {
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        OswlUserPrincipal p = admin();

        controller.settings(p, "ai", model);

        verify(model).addAttribute(eq("activeTab"), eq("ai"));
    }

    @Test
    @DisplayName("Invalid/inaccessible tab: falls back to first accessible tab")
    void invalidTab_fallsBackToFirst() {
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        OswlUserPrincipal p = admin();

        controller.settings(p, "nonexistent", model);

        verify(model).addAttribute(eq("activeTab"), eq("admin"));
    }

    @Test
    @DisplayName("Non-admin user with limited permissions: accessible tabs filtered")
    void nonAdminUser_limitedTabs() {
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        OswlUserPrincipal p = user(Set.of(Permission.SETTINGS_VCS_MANAGE));

        controller.settings(p, null, model);

        // verify accessible tabs set in model
        verify(model).addAttribute(eq("accessibleTabs"), argThat(
                tabs -> tabs instanceof List && ((List<?>) tabs).size() == 1));
        verify(model).addAttribute(eq("activeTab"), eq("vcs"));
    }

    @Test
    @DisplayName("Returns currentUser in model")
    void returnsCurrentUserInModel() {
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        OswlUserPrincipal p = admin();

        controller.settings(p, null, model);

        verify(model).addAttribute("currentUser", p);
    }
}
