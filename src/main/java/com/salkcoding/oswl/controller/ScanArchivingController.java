package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ScanArchivingControllerSpec;
import com.salkcoding.oswl.dto.scan.ScanArchiveResult;
import com.salkcoding.oswl.service.scan.ScanArchivingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/projects/{projectId}/archive-scans")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ScanArchivingController implements ScanArchivingControllerSpec {

    private final ScanArchivingService scanArchivingService;

    @PostMapping
    public ScanArchiveResult archive(@PathVariable Long projectId,
                                     @RequestParam(required = false) Integer retainCount) {
        return retainCount != null
                ? scanArchivingService.archiveProject(projectId, retainCount)
                : scanArchivingService.archiveProject(projectId);
    }
}
