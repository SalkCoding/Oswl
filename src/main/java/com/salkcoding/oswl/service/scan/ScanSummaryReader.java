package com.salkcoding.oswl.service.scan;

import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One CVE per distinct library and one license outcome per distinct library in a scan.
 * Live scans reflect current shared library metadata; archived scans retain their saved totals. */
@Service
@RequiredArgsConstructor
public class ScanSummaryReader {
    private final LibraryRepository libraries;
    public record Summary(int[] security, int[] licenses) {}

    @Transactional(readOnly = true)
    public Map<Long, Summary> read(List<ScanResult> scans) {
        Map<Long, Summary> result = new HashMap<>();
        for (ScanResult scan : scans) result.put(scan.getId(), scan.isArchived() ? archived(scan)
                : new Summary(new int[5], new int[4]));
        List<Long> live = scans.stream().filter(s -> !s.isArchived()).map(ScanResult::getId).toList();
        if (live.isEmpty()) return result;
        for (Object[] row : libraries.countSecurityByScanIds(live)) {
            int slot = switch (String.valueOf(row[1])) {
                case "CRITICAL" -> 0; case "HIGH" -> 1; case "MEDIUM" -> 2; case "LOW" -> 3; default -> 4;
            };
            result.get((Long) row[0]).security()[slot] += Math.toIntExact(((Number) row[2]).longValue());
        }
        for (Object[] row : libraries.countLicensesByScanIds(live)) {
            int slot = switch (String.valueOf(row[1])) {
                case "RESTRICTED" -> 0; case "CAUTION" -> 1; case "PERMITTED" -> 3; default -> 2;
            };
            result.get((Long) row[0]).licenses()[slot] += Math.toIntExact(((Number) row[2]).longValue());
        }
        return result;
    }
    private static Summary archived(ScanResult s) {
        return new Summary(new int[]{n(s.getArchivedSecurityCritical()), n(s.getArchivedSecurityHigh()),
                n(s.getArchivedSecurityMedium()), n(s.getArchivedSecurityLow()), n(s.getArchivedSecurityUnscored())},
                new int[]{n(s.getArchivedLicenseCritical()), n(s.getArchivedLicenseHigh()),
                        n(s.getArchivedLicenseMedium()), n(s.getArchivedLicenseLow())});
    }
    private static int n(Integer value) { return value == null ? 0 : value; }
}
