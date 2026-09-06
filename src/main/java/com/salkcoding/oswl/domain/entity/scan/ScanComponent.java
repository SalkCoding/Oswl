package com.salkcoding.oswl.domain.entity.scan;

import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.Reachability;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Join entity between ScanResult and Library.
 * The actual CVE and license data live on the shared Library entity,
 * while scan-context data (reviewed, ignored, dependencyInfo) is stored here.
 *
 * {@link DependencyPath} rows store the full dependency path tree sent by the CLI
 * so the detail panel can render the correct dependency tree.
 */
@Entity
@Table(name = "scan_components",
        indexes = {
            @Index(name = "idx_scan_components_scan", columnList = "scan_result_id"),
            @Index(name = "idx_scan_components_library", columnList = "library_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ScanComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_result_id", nullable = false)
    private ScanResult scanResult;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "library_id", nullable = false)
    private Library library;

    /**
     * Human-readable dependency summary. Example: "Direct (2) + Transitive (5)"
     * Populated by the CLI at scan time for faster display.
     */
    @Column(name = "dependency_info", length = 300)
    private String dependencyInfo;

    /**
     * Dependency scope for noise reduction: null/"runtime"/"compile" = production;
     * "test", "dev", "provided", "system" = non-runtime (badged, hidden by default filter).
     */
    @Column(name = "scope", length = 20)
    private String scope;

    /**
     * Bytecode call-graph reachability result for Java components.
     * UNKNOWN when no project bytecode was supplied or analysis failed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "reachability", length = 20, nullable = false)
    @Builder.Default
    private Reachability reachability = Reachability.UNKNOWN;

    /**
     * Human-readable evidence backing a REACHABLE verdict — up to a handful of
     * "your class X references library class Y" lines, one per line. Null for
     * NOT_REACHABLE/UNKNOWN, where there's nothing to point to.
     */
    @Column(name = "reachability_evidence", columnDefinition = "TEXT")
    private String reachabilityEvidence;

    @Column(nullable = false)
    @Builder.Default
    private boolean reviewed = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean ignored = false;

    // ── Deferral (exception handling) ───────────────────────────────

    /** Timestamp when deferral was confirmed; null = not deferred */
    @Column(name = "deferred_at")
    private LocalDateTime deferredAt;

    /**
     * Reason code: legal-review | false-positive | wont-fix | temporary | other
     */
    @Column(name = "deferral_reason", length = 50)
    private String deferralReason;

    /** Expiration date of this deferral; null = indefinite */
    @Column(name = "deferral_expires_at")
    private LocalDateTime deferralExpiresAt;

    /** Free-form note (PR description or 'other' reason text) */
    @Column(name = "deferral_note", columnDefinition = "TEXT")
    private String deferralNote;

    /** Display name of the user who applied this deferral; null = not deferred */
    @Column(name = "deferred_by_name", length = 100)
    private String deferredByName;

    /** When the last deferral expired (set by the scheduler) — drives the re-review reminder. */
    @Column(name = "deferral_expired_at")
    private LocalDateTime deferralExpiredAt;

    /** Display name of the user who last marked this component as reviewed; null = not reviewed */
    @Column(name = "reviewed_by_name", length = 100)
    private String reviewedByName;

    /** Jira issue key created for this component's vulnerabilities; null = none. */
    @Column(name = "jira_issue_key", length = 50)
    private String jiraIssueKey;

    /** Browsable URL of the linked Jira issue; null = none. */
    @Column(name = "jira_issue_url", length = 500)
    private String jiraIssueUrl;

    /**
     * Full dependency path tree from the root to this library.
     * Filled from the CLI payload and empty for scans created by older CLI versions.
     */
    @OneToMany(mappedBy = "scanComponent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("pathIndex ASC")
    @Builder.Default
    private List<DependencyPath> dependencyPaths = new ArrayList<>();

    @Column(name = "reachability_analysis", columnDefinition = "TEXT")
    private String reachabilityAnalysis;

    // ── Mutation helpers ─────────────────────────────────────────

    public void markReviewed(boolean reviewed) {
        this.reviewed = reviewed;
        if (!reviewed) {
            this.reviewedByName = null;
        }
    }

    public void markReviewedBy(boolean reviewed, String byName) {
        this.reviewed = reviewed;
        this.reviewedByName = reviewed ? byName : null;
    }

    public void markIgnored(boolean ignored) {
        this.ignored = ignored;
    }

    public void updateReachability(Reachability reachability) {
        this.reachability = reachability != null ? reachability : Reachability.UNKNOWN;
        this.reachabilityEvidence = null;
    }

    /** Same as {@link #updateReachability(Reachability)} but also records why — evidence lines,
     *  each "referencingClass -> referencedClass", one per line. */
    public void updateReachability(Reachability reachability, String evidenceText) {
        this.reachability = reachability != null ? reachability : Reachability.UNKNOWN;
        this.reachabilityEvidence = (this.reachability == Reachability.REACHABLE
                && evidenceText != null && !evidenceText.isBlank()) ? evidenceText : null;
    }

    public void applyDeferral(String reason, LocalDateTime expiresAt, String note) {
        this.deferredAt = LocalDateTime.now();
        this.deferralReason = reason;
        this.deferralExpiresAt = expiresAt;
        this.deferralNote = note;
        this.ignored = true;
    }

    public void applyDeferral(String reason, LocalDateTime expiresAt, String note, String byName) {
        this.deferredAt = LocalDateTime.now();
        this.deferralReason = reason;
        this.deferralExpiresAt = expiresAt;
        this.deferralNote = note;
        this.deferredByName = byName;
        this.ignored = true;
    }

    /** Clears a deferred state (called by the expiry scheduler or manual un-defer). */
    private void clearDeferral() {
        this.deferredAt = null;
        this.deferralReason = null;
        this.deferralExpiresAt = null;
        this.deferralNote = null;
        this.deferredByName = null;
        this.ignored = false;
    }

    /** Expires a deferral: clears it and stamps deferralExpiredAt for the re-review reminder. */
    public void expireDeferral() {
        clearDeferral();
        this.deferralExpiredAt = LocalDateTime.now();
    }

    public boolean isDeferred() {
        return deferredAt != null;
    }

    public void linkJiraIssue(String issueKey, String issueUrl) {
        this.jiraIssueKey = issueKey;
        this.jiraIssueUrl = issueUrl;
    }

    /** True for production dependencies — scope null, "runtime", "compile", or "import". */
    public boolean isRuntimeScope() {
        if (scope == null || scope.isBlank()) return true;
        return switch (scope.toLowerCase()) {
            case "runtime", "compile", "import" -> true;
            default -> false;
        };
    }

    /** Normalized display scope: "runtime" when null/blank, else the stored lowercase value. */
    public String displayScope() {
        return (scope == null || scope.isBlank()) ? "runtime" : scope.toLowerCase();
    }

    // ── Convenience delegates to Library ─────────────────────────────────

    public String getName()    { return library.getName(); }
    public String getVersion() { return library.getVersion(); }
}
