package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.LicenseControllerSpec;
import com.salkcoding.oswl.service.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/projects/{projectId}/license")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'LICENSE_VIEW') or hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class LicenseController implements LicenseControllerSpec {

    private final LicenseService licenseService;

    @GetMapping
    public String index(@PathVariable Long projectId,
                        @RequestParam(required = false) Long scanId,
                        Model model) {
        licenseService.populateModel(projectId, scanId, model);
        return "license/index";
    }
}
