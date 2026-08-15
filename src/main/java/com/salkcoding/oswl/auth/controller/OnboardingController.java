package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.OnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Post-setup onboarding wizard — a skippable, re-runnable guided tour covering
 * SSO/user invites, repo connection, policy defaults, and notifications. Tracked separately
 * from {@link com.salkcoding.oswl.auth.entity.InstanceSetupLock}, which only covers creating
 * the first admin account. Reachable by any authenticated user (re-run link in Settings), but
 * the post-login auto-redirect to it only fires for the system admin.
 */
@Controller
@RequestMapping("/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping
    public String wizard(Model model, @AuthenticationPrincipal OswlUserPrincipal principal) {
        model.addAttribute("stepStatus",
                onboardingService.stepStatus(principal != null ? principal.getUserId() : null));
        return "auth/onboarding";
    }

    /** Marks the wizard done — reached by both "Finish" and every step's "Skip" advancing to the end. */
    @PostMapping("/complete")
    public String complete() {
        onboardingService.markCompleted();
        return "redirect:/projects";
    }
}
