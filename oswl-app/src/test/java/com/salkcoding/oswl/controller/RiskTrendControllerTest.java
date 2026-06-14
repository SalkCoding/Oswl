package com.salkcoding.oswl.controller;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.RiskTrendService;
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

@Tag(TestTags.FAST)
@Tag(TestTags.WEB)
@ExtendWith(MockitoExtension.class)
@DisplayName("RiskTrendController 단위 테스트")
class RiskTrendControllerTest {

    @Mock RiskTrendService riskTrendService;
    @Mock ProjectAccessService projectAccessService;
    @InjectMocks RiskTrendController controller;

    @org.junit.jupiter.api.BeforeEach
    void stubAccess() {
        org.mockito.Mockito.doNothing().when(projectAccessService).assertCanViewProject(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("index: risk-trend/index 뷰를 반환하고 서비스에 위임한다")
    void index_returnsRiskTrendView_andDelegatesToService() {
        Model model = new ConcurrentModel();

        String view = controller.index(1L, model);

        assertThat(view).isEqualTo("risk-trend/index");
        verify(riskTrendService).populateModel(1L, model);
    }

    @Test
    @DisplayName("index: 다른 projectId에 대해서도 서비스를 호출한다")
    void index_differentProjectId_delegatesCorrectly() {
        Model model = new ConcurrentModel();

        String view = controller.index(42L, model);

        assertThat(view).isEqualTo("risk-trend/index");
        verify(riskTrendService).populateModel(42L, model);
    }
}
