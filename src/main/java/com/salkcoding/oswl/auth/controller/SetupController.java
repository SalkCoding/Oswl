package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.SetupRequest;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.RoleTemplateBootstrapService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/setup")
@RequiredArgsConstructor
public class SetupController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleTemplateBootstrapService roleTemplateBootstrapService;
    private final AuditLogService auditLogService;

    @GetMapping
    public String setupForm(Model model) {
        if (userRepository.existsByIsSystemAdminTrue()) return "redirect:/login";
        if (!model.containsAttribute("setupRequest")) {
            model.addAttribute("setupRequest", new SetupRequest());
        }
        return "auth/setup";
    }

    @PostMapping
    @Transactional
    public String createInitialAdmin(@Valid @ModelAttribute("setupRequest") SetupRequest request,
                                     BindingResult bindingResult,
                                     Model model) {
        if (userRepository.existsByIsSystemAdminTrue()) return "redirect:/login";

        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            bindingResult.rejectValue("passwordConfirm", "mismatch", "Passwords do not match.");
        }
        if (bindingResult.hasErrors()) {
            return "auth/setup";
        }

        roleTemplateBootstrapService.ensureBuiltInTemplates();

        User admin = User.builder()
                .email(request.getEmail().trim().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName().trim())
                .isSystemAdmin(true)
                .enabled(true)
                .build();
        userRepository.save(admin);

        auditLogService.logAnonymous(request.getEmail().trim().toLowerCase(),
                "SYSTEM.SETUP", "SYSTEM", null,
                request.getDisplayName().trim(), null);

        return "redirect:/login?setup";
    }
}
