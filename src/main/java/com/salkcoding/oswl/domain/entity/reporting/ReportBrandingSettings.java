package com.salkcoding.oswl.domain.entity.reporting;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Single-row branding settings applied to printable reports (compliance report today,
 * any future printable report later). Logo is stored as a data URI rather than a file
 * path — small enough (SVG/PNG logos are typically well under 100KB) that a dedicated
 * upload/storage path would be more machinery than the feature needs.
 */
@Entity
@Table(name = "report_branding_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ReportBrandingSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", length = 150)
    private String companyName;

    /** "data:image/png;base64,..." — null falls back to the default OsWL logo. */
    @Column(name = "logo_data_uri", columnDefinition = "TEXT")
    private String logoDataUri;

    /** Custom header/disclaimer line shown under the report title. Null = OsWL default. */
    @Column(name = "header_text", length = 300)
    private String headerText;

    @Column(name = "show_cover_page", nullable = false)
    @Builder.Default
    private boolean showCoverPage = false;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void update(String companyName, String logoDataUri, String headerText, boolean showCoverPage) {
        this.companyName = blankToNull(companyName);
        // A null logoDataUri from the client means "leave as-is" (the branding form doesn't
        // re-submit the current logo); an explicit empty string means "remove the logo".
        if (logoDataUri != null) {
            this.logoDataUri = logoDataUri.isBlank() ? null : logoDataUri;
        }
        this.headerText = blankToNull(headerText);
        this.showCoverPage = showCoverPage;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
