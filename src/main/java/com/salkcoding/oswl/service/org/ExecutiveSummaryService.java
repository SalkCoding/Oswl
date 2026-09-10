package com.salkcoding.oswl.service.org;

import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.dto.OrgProjectRiskDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExecutiveSummaryService {
    private final OrgDashboardService dashboard;
    private final AuditLogService audit;
    public void populate(Model model) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OswlUserPrincipal principal) || !principal.hasPermission(Permission.ORG_DASHBOARD_VIEW))
            throw new AccessDeniedException("Organization dashboard permission required");
        dashboard.populateModel(model);
        @SuppressWarnings("unchecked") var rows = (List<OrgProjectRiskDto>) model.getAttribute("projectRows");
        model.addAttribute("priorityProjects", rows.stream().filter(OrgProjectRiskDto::isScanned).limit(5).toList());
        model.addAttribute("generatedAt", LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        audit.log("ORG_SUMMARY.VIEW", "ORG_DASHBOARD", null, null, "organization risk summary");
    }
}
