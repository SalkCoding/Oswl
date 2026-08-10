package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.service.MobileDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

/**
 * Lightweight mobile "notifications" screen (ROADMAP C8) — unacknowledged CVE alerts on
 * projects the user can see, plus (for whoever can manage policy) pending waiver approvals.
 * A phone-sized companion view, not a mobile port of the full app: no scan browsing, no
 * settings, nothing else. Reuses the same session auth and the existing acknowledge/approve/
 * revoke endpoints — this controller only assembles the read side.
 */
@Controller
@RequestMapping("/mobile")
@RequiredArgsConstructor
public class MobileController {

    private final MobileDashboardService mobileDashboardService;

    @GetMapping
    public String index(@AuthenticationPrincipal OswlUserPrincipal principal, Model model) {
        boolean canManagePolicy = principal != null
                && (principal.isSystemAdmin() || principal.hasPermission(Permission.POLICY_MANAGE));
        model.addAttribute("alerts", mobileDashboardService.pendingAlerts());
        model.addAttribute("waivers", canManagePolicy ? mobileDashboardService.pendingWaivers() : List.of());
        model.addAttribute("canManagePolicy", canManagePolicy);
        return "mobile/index";
    }
}
