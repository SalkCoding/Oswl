package com.salkcoding.oswl.auth.controller;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.dto.CacheSettingDto;
import com.salkcoding.oswl.auth.dto.UpdateCacheTtlRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.CacheManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@Tag(TestTags.WEB)
@ExtendWith(MockitoExtension.class)
@DisplayName("CacheSettingsController 단위 테스트")
class CacheSettingsControllerTest {

    @Mock CacheManagementService cacheManagementService;
    @InjectMocks CacheSettingsController controller;

    @Test
    @DisplayName("list: 서비스에서 반환한 CacheSettingDto 목록을 그대로 반환한다")
    void list_returnsCacheSettings() {
        CacheSettingDto dto = mock(CacheSettingDto.class);
        when(cacheManagementService.findAll()).thenReturn(List.of(dto));

        List<CacheSettingDto> result = controller.list();

        assertThat(result).containsExactly(dto);
        verify(cacheManagementService).findAll();
    }

    @Test
    @DisplayName("list: 빈 목록도 그대로 반환한다")
    void list_emptyList_returnedAsIs() {
        when(cacheManagementService.findAll()).thenReturn(List.of());

        assertThat(controller.list()).isEmpty();
    }

    @Test
    @DisplayName("update: 요청의 cacheKey와 ttlSeconds로 서비스를 호출한다")
    void update_delegatesToService() {
        UpdateCacheTtlRequest request = new UpdateCacheTtlRequest();
        request.setCacheKey("CVE_CACHE");
        request.setTtlSeconds(3600L);

        controller.update(request);

        verify(cacheManagementService).updateTtl("CVE_CACHE", 3600L);
    }

    @Test
    @DisplayName("clear: cacheKey와 userId로 서비스를 호출한다")
    void clear_withPrincipal_delegatesToService() {
        OswlUserPrincipal principal = mock(OswlUserPrincipal.class);
        when(principal.getUserId()).thenReturn(42L);

        controller.clear("TEST_CACHE", principal);

        verify(cacheManagementService).clearCache("TEST_CACHE", 42L);
    }

    @Test
    @DisplayName("clear: principal이 null이면 userId를 null로 전달한다")
    void clear_nullPrincipal_passesNullUserId() {
        controller.clear("TEST_CACHE", null);

        verify(cacheManagementService).clearCache("TEST_CACHE", null);
    }
}
