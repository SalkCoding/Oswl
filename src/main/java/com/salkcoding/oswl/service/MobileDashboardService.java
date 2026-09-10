package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.policy.PolicyException;
import com.salkcoding.oswl.domain.entity.vulnerability.CveAlert;
import com.salkcoding.oswl.domain.enums.PolicyExceptionStatus;
import com.salkcoding.oswl.dto.mobile.MobileAlertDto;
import com.salkcoding.oswl.dto.mobile.MobileProjectAlertsDto;
import com.salkcoding.oswl.dto.mobile.MobileWaiverDto;
import com.salkcoding.oswl.repository.policy.PolicyExceptionRepository;
import com.salkcoding.oswl.repository.vulnerability.CveAlertRepository;
import com.salkcoding.oswl.service.project.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the mobile "notifications" view — a lightweight, phone-sized screen
 * limited to two things a user might act on away from a desktop: unread CVE alerts on
 * projects they can see, and (for whoever can manage policy) pending waiver approvals.
 * Deliberately not a full feature port — everything else stays desktop-only.
 */
@Service
@RequiredArgsConstructor
public class MobileDashboardService {

    /** A phone screen isn't the place to scroll through hundreds of rows. */
    private static final int MAX_ALERTS = 30;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private final ProjectAccessService projectAccessService;
    private final CveAlertRepository cveAlertRepository;
    private final PolicyExceptionRepository policyExceptionRepository;

    /**
     * Grouped by project (most-recently-alerted project first) since the existing acknowledge
     * endpoint clears an entire project's alerts in one call, not one alert at a time.
     */
    @Transactional(readOnly = true)
    public List<MobileProjectAlertsDto> pendingAlerts() {
        List<Long> projectIds = projectAccessService.accessibleProjectIds();
        if (projectIds.isEmpty()) {
            return List.of();
        }
        List<MobileAlertDto> flat = cveAlertRepository
                .findByProjectIdInAndAcknowledgedFalseOrderByDetectedAtDesc(projectIds).stream()
                .limit(MAX_ALERTS)
                .map(MobileDashboardService::toAlertDto)
                .toList();

        Map<Long, MobileProjectAlertsDto> byProject = new LinkedHashMap<>();
        for (MobileAlertDto a : flat) {
            byProject.computeIfAbsent(a.projectId(),
                            id -> new MobileProjectAlertsDto(id, a.projectName(), new ArrayList<>()))
                    .alerts().add(a);
        }
        return List.copyOf(byProject.values());
    }

    @Transactional(readOnly = true)
    public List<MobileWaiverDto> pendingWaivers() {
        return policyExceptionRepository.findByStatusOrderByCreatedAtDesc(PolicyExceptionStatus.PENDING).stream()
                .map(MobileDashboardService::toWaiverDto)
                .toList();
    }

    private static MobileAlertDto toAlertDto(CveAlert a) {
        return new MobileAlertDto(
                a.getId(), a.getProject().getId(), a.getProject().getName(),
                a.getLibraryName(), a.getLibraryVersion(),
                a.getCveId() != null ? a.getCveId() : a.getVulnId(),
                a.getSeverity() != null ? a.getSeverity().name() : null,
                a.getDetectedAt() != null ? a.getDetectedAt().format(DATE_FMT) : null);
    }

    private static MobileWaiverDto toWaiverDto(PolicyException e) {
        return new MobileWaiverDto(
                e.getId(), e.getProject().getId(), e.getProject().getName(),
                e.getRequesterName(), e.getReason(),
                e.getExpiry() != null ? e.getExpiry().format(DATE_FMT) : null,
                e.getTargetType().name(), e.getTargetId());
    }
}
