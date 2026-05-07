package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ComponentDetailControllerSpec;
import com.salkcoding.oswl.service.ComponentDetailService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/projects/{projectId}/components/{componentId}")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'COMPONENT_DETAIL_VIEW') or hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class ComponentDetailController implements ComponentDetailControllerSpec {

    private final ComponentDetailService componentDetailService;

    @GetMapping
    public String detail(@PathVariable Long projectId,
                         @PathVariable Long componentId,
                         Model model,
                         HttpServletRequest request) {
        componentDetailService.populateModel(projectId, componentId, model);

        if ("true".equals(request.getHeader("HX-Request"))) {
            return "component-detail/fragments/detail-content :: content";
        }
        return "component-detail/index";
    }
}
