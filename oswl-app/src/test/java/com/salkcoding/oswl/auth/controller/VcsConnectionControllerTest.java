package com.salkcoding.oswl.auth.controller;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.dto.AddVcsConnectionRequest;
import com.salkcoding.oswl.auth.dto.VcsConnectionDto;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.VcsConnectionService;
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
@DisplayName("VcsConnectionController 단위 테스트")
class VcsConnectionControllerTest {

    @Mock VcsConnectionService vcsConnectionService;
    @InjectMocks VcsConnectionController controller;

    private OswlUserPrincipal mockPrincipal(Long userId) {
        OswlUserPrincipal p = mock(OswlUserPrincipal.class);
        when(p.getUserId()).thenReturn(userId);
        return p;
    }

    @Test
    @DisplayName("list: 현재 사용자의 VCS 연결 목록을 반환한다")
    void list_returnsDtoList() {
        VcsConnectionDto dto = mock(VcsConnectionDto.class);
        when(vcsConnectionService.findByCurrentUser(1L)).thenReturn(List.of(dto));

        List<VcsConnectionDto> result = controller.list(mockPrincipal(1L));

        assertThat(result).containsExactly(dto);
        verify(vcsConnectionService).findByCurrentUser(1L);
    }

    @Test
    @DisplayName("add: 사용자 ID와 요청을 서비스에 전달하고 반환값을 그대로 반환한다")
    void add_delegatesToServiceAndReturnsDto() {
        AddVcsConnectionRequest request = mock(AddVcsConnectionRequest.class);
        VcsConnectionDto dto = mock(VcsConnectionDto.class);
        when(vcsConnectionService.addConnection(2L, request)).thenReturn(dto);

        VcsConnectionDto result = controller.add(mockPrincipal(2L), request);

        assertThat(result).isSameAs(dto);
        verify(vcsConnectionService).addConnection(2L, request);
    }

    @Test
    @DisplayName("remove: 연결 ID와 사용자 ID로 서비스를 호출한다")
    void remove_callsServiceWithCorrectIds() {
        controller.remove(mockPrincipal(3L), 99L);

        verify(vcsConnectionService).removeConnection(99L, 3L);
    }
}
