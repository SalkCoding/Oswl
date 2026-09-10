package com.salkcoding.oswl.controller.org;
import com.salkcoding.oswl.controller.spec.ExecutiveSummaryControllerSpec;
import com.salkcoding.oswl.service.org.ExecutiveSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
@RequiredArgsConstructor
public class ExecutiveSummaryController implements ExecutiveSummaryControllerSpec {
    private final ExecutiveSummaryService service;
    @GetMapping("/org-dashboard/summary")
    @PreAuthorize("hasPermission(null, 'ORG_DASHBOARD_VIEW') or hasRole('SYSTEM_ADMIN')")
    public String summary(Model model) { service.populate(model); return "org-dashboard/summary"; }
}
