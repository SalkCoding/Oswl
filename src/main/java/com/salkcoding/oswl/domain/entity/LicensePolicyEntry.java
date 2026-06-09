package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.LicenseStatus;
import jakarta.persistence.*;
import lombok.*;

/**
 * Maps a SPDX license identifier to a LicenseStatus classification.
 * Populated from the built-in defaults on first startup; customisable via Settings UI in the future.
 *
 * Default classifications:
 *   OK        — MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC, Unlicense, 0BSD, CC0-1.0, Zlib, BSL-1.0, MIT-0
 *   WARN      — LGPL-2.0, LGPL-2.1, LGPL-3.0, MPL-1.0, MPL-1.1, MPL-2.0, CDDL-1.0, EPL-1.0, EPL-2.0, EUPL-1.1, EUPL-1.2, APSL-2.0
 *   VIOLATION — GPL-2.0, GPL-3.0, AGPL-1.0, AGPL-3.0, SSPL-1.0, BUSL-1.1, CC-BY-NC-4.0, CC-BY-NC-SA-4.0
 */
@Entity
@Table(name = "license_policy_entries",
        indexes = @Index(name = "idx_license_policy_spdx", columnList = "spdx_id", unique = true))
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class LicensePolicyEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SPDX license identifier, e.g. "MIT", "Apache-2.0", "GPL-3.0-only".
     * Must be unique.
     */
    @Column(name = "spdx_id", nullable = false, length = 100, unique = true)
    private String spdxId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LicenseStatus status;

    /** Human-readable reason for the classification (optional) */
    @Column(length = 500)
    private String reason;

    /** Whether this entry was created by the system defaults (false = user-defined) */
    @Column(name = "built_in", nullable = false)
    @Builder.Default
    private boolean builtIn = true;

    public void updateStatus(LicenseStatus status, String reason) {
        this.status = status;
        this.reason = reason;
        this.builtIn = false;
    }
}
