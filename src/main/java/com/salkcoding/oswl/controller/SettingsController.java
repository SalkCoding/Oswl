package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.web.SettingsTabAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    @GetMapping
    public String settings(@AuthenticationPrincipal OswlUserPrincipal principal,
                           @RequestParam(value = "tab", required = false) String tab,
                           Model model) {
        List<SettingsTabAccess.TabSpec> tabs = SettingsTabAccess.accessibleTabsFor(principal);
        model.addAttribute("accessibleTabs", tabs);

        String activeTab = tab;
        if (activeTab == null || tabs.stream().noneMatch(t -> t.getKey().equals(tab))) {
            activeTab = tabs.isEmpty() ? "" : tabs.get(0).getKey();
        }
        model.addAttribute("activeTab", activeTab);
        model.addAttribute("currentUser", principal);
        return "settings/index";
    }
}
