package com.salkcoding.oswl.dto.mobile;

import java.util.List;

/**
 * One project's unacknowledged CVE alerts, grouped for the mobile notifications view
 * — the existing acknowledge endpoint clears a whole project's alerts at once,
 * so the UI acts at the same granularity rather than per-alert.
 */
public record MobileProjectAlertsDto(Long projectId, String projectName, List<MobileAlertDto> alerts) {}
