package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.SecurityCenterControllerSpec;
import com.salkcoding.oswl.service.SecurityCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/projects/{projectId}/security-center")
@RequiredArgsConstructor
public class SecurityCenterController implements SecurityCenterControllerSpec {

    private final SecurityCenterService securityCenterService;

    @GetMapping
    public String index(@PathVariable Long projectId, Model model) {
        securityCenterService.populateModel(projectId, model);
        return "security-center/index";
    }
}
