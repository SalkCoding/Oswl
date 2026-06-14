package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.VersionDiffService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("VersionDiffController 단위 테스트")
class VersionDiffControllerTest {

    @Mock VersionDiffService versionDiffService;
    @Mock ProjectAccessService projectAccessService;
    @InjectMocks VersionDiffController controller;

    @org.junit.jupiter.api.BeforeEach
    void stubAccess() {
        org.mockito.Mockito.doNothing().when(projectAccessService).assertCanViewProject(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("index: version-diff/index 뷰를 반환한다")
    void index_returnsVersionDiffView() {
        Model model = new ConcurrentModel();

        String view = controller.index(1L, null, null, model);

        assertThat(view).isEqualTo("version-diff/index");
    }

    @Test
    @DisplayName("index: from/to 파라미터를 서비스에 전달한다")
    void index_passesFromToParamsToService() {
        Model model = new ConcurrentModel();

        controller.index(5L, 10L, 20L, model);

        verify(versionDiffService).populateModel(5L, 10L, 20L, model);
    }

    @Test
    @DisplayName("index: from/to 파라미터가 null이어도 서비스를 호출한다")
    void index_nullFromTo_stillCallsService() {
        Model model = new ConcurrentModel();

        controller.index(3L, null, null, model);

        verify(versionDiffService).populateModel(3L, null, null, model);
    }
}
