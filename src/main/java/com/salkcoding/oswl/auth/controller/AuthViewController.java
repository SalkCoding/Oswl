package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.enums.TwoFaMode;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.OtpService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
@RequiredArgsConstructor
public class AuthViewController {

    private final OtpService             otpService;
    private final SecuritySettingService securitySettingService;

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    @GetMapping("/login/otp-verify")
    public String otpVerifyPage(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);

        // Guard: if no pending 2FA in session, redirect to login
        if (session == null || !otpService.isPending(session)) {
            return "redirect:/login";
        }

        model.addAttribute("maskedEmail",
                maskEmail(otpService.getPendingPrincipal(session).getUsername()));
        model.addAttribute("expirySeconds", otpService.remainingSeconds(session));
        model.addAttribute("mailFailed",
                Boolean.TRUE.equals(session.getAttribute(OtpService.SESSION_MAIL_FAILED)));

        return "auth/otp-verify";
    }

    @GetMapping("/change-password")
    public String changePasswordPage(@AuthenticationPrincipal OswlUserPrincipal principal) {
        // Only serve this page to users who still need to change their password.
        // Others are redirected away to avoid confusion.
        if (principal == null || !principal.isMustChangePassword()) {
            return "redirect:/projects";
        }
        return "auth/change-password";
    }

    /**
     * GET /my/change-password — voluntary password change.
     *
     * If OTP (2FA) is enabled and the step-up OTP has not yet been verified in this
     * session, an OTP is generated and the user is redirected to the verification page.
     */
    @GetMapping("/my/change-password")
    public String myChangePasswordPage(
            @AuthenticationPrincipal OswlUserPrincipal principal,
            HttpServletRequest request) {

        if (principal == null) return "redirect:/login";

        TwoFaMode twoFaMode = securitySettingService.getOrCreate().getTwoFaMode();
        if (twoFaMode != TwoFaMode.DISABLED) {
            HttpSession session = request.getSession(false);
            if (session == null || !otpService.isChangePasswordOtpVerified(session)) {
                // Start step-up OTP challenge
                HttpSession s = request.getSession(true);
                otpService.storeChangePasswordOtp(s, principal.getUsername(), principal.getDisplayName());
                return "redirect:/my/change-password/otp";
            }
        }
        return "auth/my-change-password";
    }

    /**
     * GET /my/change-password/otp — step-up OTP verification page for password change.
     */
    @GetMapping("/my/change-password/otp")
    public String myChangePasswordOtpPage(
            @AuthenticationPrincipal OswlUserPrincipal principal,
            HttpServletRequest request,
            Model model) {

        if (principal == null) return "redirect:/login";

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(OtpService.SESSION_CHANGE_PW_OTP) == null) {
            return "redirect:/my/change-password";
        }

        model.addAttribute("maskedEmail", maskEmail(principal.getUsername()));
        model.addAttribute("expirySeconds", otpService.remainingChangePasswordOtpSeconds(session));
        return "auth/my-change-password-otp";
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        String[] parts = email.split("@", 2);
        String local = parts[0];
        String masked = local.length() <= 2
                ? local + "**"
                : local.substring(0, 2) + "*".repeat(local.length() - 2);
        return masked + "@" + parts[1];
    }

    @RequestMapping("/error/403")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String forbidden() {
        return "error/403";
    }

    @RequestMapping("/error/401")
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public String unauthorized() {
        return "error/401";
    }

    @RequestMapping("/error/500")
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String serverError() {
        return "error/500";
    }

    @RequestMapping("/error/503")
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public String serviceUnavailable() {
        return "error/503";
    }
}
