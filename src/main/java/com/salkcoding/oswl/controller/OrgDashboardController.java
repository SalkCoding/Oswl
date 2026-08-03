package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.OrgDashboardControllerSpec;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.service.org.OrgDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/org-dashboard")
@PreAuthorize("hasPermission(null, 'ORG_DASHBOARD_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class OrgDashboardController implements OrgDashboardControllerSpec {

    private final OrgDashboardService orgDashboardService;
    private final AuditLogService auditLogService;

    @GetMapping
    public String index(Model model) {
        orgDashboardService.populateModel(model);
        auditLogService.log("ORG_DASHBOARD.VIEW", "ORG_DASHBOARD", null, null, null);
        return "org-dashboard/index";
    }
}
