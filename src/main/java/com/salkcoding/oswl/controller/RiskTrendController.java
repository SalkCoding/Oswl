package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.RiskTrendControllerSpec;
import com.salkcoding.oswl.service.RiskTrendService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/projects/{projectId}/risk-trend")
@RequiredArgsConstructor
public class RiskTrendController implements RiskTrendControllerSpec {

    private final RiskTrendService riskTrendService;

    @GetMapping
    public String index(@PathVariable Long projectId, Model model) {
        riskTrendService.populateModel(projectId, model);
        return "risk-trend/index";
    }
}
