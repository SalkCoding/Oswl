package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.LicenseControllerSpec;
import com.salkcoding.oswl.service.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/projects/{projectId}/license")
@RequiredArgsConstructor
public class LicenseController implements LicenseControllerSpec {

    private final LicenseService licenseService;

    @GetMapping
    public String index(@PathVariable Long projectId, Model model) {
        licenseService.populateModel(projectId, model);
        return "license/index";
    }
}
