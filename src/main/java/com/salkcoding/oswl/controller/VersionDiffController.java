package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.service.VersionDiffService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/projects/{projectId}/version-diff")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'VERSION_DIFF_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class VersionDiffController {

    private final VersionDiffService versionDiffService;

    @GetMapping
    public String index(@PathVariable Long projectId,
                        @RequestParam(required = false) Long from,
                        @RequestParam(required = false) Long to,
                        Model model) {
        versionDiffService.populateModel(projectId, from, to, model);
        return "version-diff/index";
    }
}
