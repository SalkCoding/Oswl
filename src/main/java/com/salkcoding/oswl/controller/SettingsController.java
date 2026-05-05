package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.ExternalApiSetting;
import com.salkcoding.oswl.repository.ExternalApiSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final ExternalApiSettingRepository externalApiSettingRepository;

    @GetMapping
    public String settings(Model model) {
        ExternalApiSetting s = externalApiSettingRepository.findFirstByOrderByIdAsc().orElse(null);
        model.addAttribute("githubConfigured", s != null && s.isGithubConfigured());
        return "settings/index";
    }
}
