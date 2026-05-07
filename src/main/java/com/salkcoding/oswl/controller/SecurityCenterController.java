package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.SecurityCenterControllerSpec;
import com.salkcoding.oswl.dto.BulkStatusRequest;
import com.salkcoding.oswl.service.SecurityCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/projects/{projectId}/security-center")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'SECURITY_CENTER_VIEW') or hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SecurityCenterController implements SecurityCenterControllerSpec {

    private final SecurityCenterService securityCenterService;

    @GetMapping
    public String index(@PathVariable Long projectId,
                        @RequestParam(required = false) Long scanId,
                        Model model) {
        securityCenterService.populateModel(projectId, scanId, model);
        return "security-center/index";
    }

    @PatchMapping("/bulk-status")
    @org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'SECURITY_CENTER_UPDATE_STATUS') or hasRole('SUPER_ADMIN')")
    @ResponseBody
    public ResponseEntity<Void> bulkStatus(@PathVariable Long projectId,
                                           @RequestBody BulkStatusRequest req) {
        securityCenterService.bulkUpdateStatus(projectId, req);
        return ResponseEntity.noContent().build();
    }
}
