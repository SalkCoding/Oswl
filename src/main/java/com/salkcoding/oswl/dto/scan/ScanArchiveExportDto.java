package com.salkcoding.oswl.dto.scan;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full component/CVE/dependency-path detail for one scan, captured just before
 * {@link com.salkcoding.oswl.service.scan.ScanArchivingService} deletes it — archiving is
 * irreversible, so this is the operator's only chance to keep a copy of what's about to go.
 */
public record ScanArchiveExportDto(
        Long scanId,
        String version,
        LocalDateTime scannedAt,
        List<ComponentExportDto> components
) {

    public record ComponentExportDto(
            String libraryName,
            String libraryVersion,
            String ecosystem,
            String licenseStatus,
            String licenseName,
            List<CveExportDto> cves,
            /** Each inner list is one root-to-target dependency chain, as {@code "name@version"} entries. */
            List<List<String>> dependencyPaths
    ) {}

    public record CveExportDto(
            String cveId,
            String severity,
            Double cvssScore,
            Double epssScore,
            Boolean kevListed,
            String fixVersion
    ) {}
}
