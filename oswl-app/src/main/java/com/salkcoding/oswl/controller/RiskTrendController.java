package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.RiskTrendControllerSpec;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.RiskTrendService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/projects/{projectId}/risk-trend")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'RISK_TREND_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class RiskTrendController implements RiskTrendControllerSpec {

    private final RiskTrendService riskTrendService;
    private final ProjectAccessService projectAccessService;

    @GetMapping
    public String index(@PathVariable Long projectId, Model model) {
        projectAccessService.assertCanViewProject(projectId);
        riskTrendService.populateModel(projectId, model);
        return "risk-trend/index";
    }
}
