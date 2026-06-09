package com.salkcoding.oswl.license;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the bundled SPDX license list ({@code license-policy/spdx-licenses.json}).
 */
@Slf4j
@Component
public class SpdxLicenseRegistry {

    private static final String RESOURCE = "license-policy/spdx-licenses.json";
    private static final String SPDX_LICENSE_BASE = "https://spdx.org/licenses/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, SpdxLicenseInfo> byId = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            SpdxLicenseListFile file = MAPPER.readValue(in, SpdxLicenseListFile.class);
            if (file.licenses == null) {
                log.warn("[SpdxLicenseRegistry] No licenses in {}", RESOURCE);
                return;
            }
            for (SpdxLicenseRecord record : file.licenses) {
                if (record.licenseId == null || record.licenseId.isBlank()) {
                    continue;
                }
                if (record.isDeprecatedLicenseId) {
                    continue;
                }
                byId.put(record.licenseId.toUpperCase(), new SpdxLicenseInfo(
                        record.licenseId,
                        record.name != null ? record.name.strip() : record.licenseId,
                        record.isOsiApproved,
                        record.reference != null && !record.reference.isBlank()
                                ? record.reference.strip()
                                : SPDX_LICENSE_BASE + record.licenseId + ".html"
                ));
            }
            log.info("[SpdxLicenseRegistry] Loaded {} active SPDX licenses (version {})",
                    byId.size(), file.licenseListVersion);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
    }

    public List<SpdxLicenseInfo> allActive() {
        return List.copyOf(byId.values());
    }

    public String displayName(String spdxId) {
        if (spdxId == null || spdxId.isBlank()) {
            return "";
        }
        SpdxLicenseInfo info = byId.get(spdxId.strip().toUpperCase());
        return info != null ? info.name() : spdxId.strip();
    }

    /** Official SPDX license page (from bundled list, or constructed fallback). */
    public String referenceUrl(String spdxId) {
        if (spdxId == null || spdxId.isBlank()) {
            return null;
        }
        SpdxLicenseInfo info = byId.get(spdxId.strip().toUpperCase());
        if (info != null) {
            return info.referenceUrl();
        }
        return SPDX_LICENSE_BASE + spdxId.strip() + ".html";
    }

    public record SpdxLicenseInfo(String id, String name, boolean osiApproved, String referenceUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class SpdxLicenseListFile {
        public String licenseListVersion;
        public List<SpdxLicenseRecord> licenses;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class SpdxLicenseRecord {
        public String licenseId;
        public String name;
        public String reference;
        public boolean isDeprecatedLicenseId;
        public boolean isOsiApproved;
    }
}
