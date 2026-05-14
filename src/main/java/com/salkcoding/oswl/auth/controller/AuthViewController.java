package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.service.OtpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
@RequiredArgsConstructor
public class AuthViewController {

    private final OtpService otpService;

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

        return "auth/otp-verify";
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
