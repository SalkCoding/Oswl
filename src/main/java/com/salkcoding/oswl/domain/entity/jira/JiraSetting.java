package com.salkcoding.oswl.domain.entity.jira;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Single-row Jira integration settings.
 * When enabled, triage actions can create Jira issues for a component's vulnerabilities.
 * The API token is stored AES-256-GCM encrypted (same as VCS tokens / mail password).
 */
@Entity
@Table(name = "jira_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class JiraSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Jira Cloud/Server base URL, e.g. https://yourco.atlassian.net */
    @Column(name = "base_url", length = 300)
    private String baseUrl;

    /** Atlassian account email (used as the Basic-auth username). */
    @Column(length = 255)
    private String email;

    /** Jira API token — AES-256-GCM encrypted. */
    @Column(name = "api_token", length = 1000)
    private String apiToken;

    /** Target project key, e.g. "SEC". */
    @Column(name = "project_key", length = 50)
    private String projectKey;

    /** Issue type name to create, e.g. "Task", "Bug". */
    @Column(name = "issue_type", length = 50)
    @Builder.Default
    private String issueType = "Task";

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void update(String baseUrl, String email, String apiToken,
                       String projectKey, String issueType, boolean enabled) {
        this.baseUrl = baseUrl;
        this.email = email;
        if (apiToken != null && !apiToken.isBlank()) {
            this.apiToken = apiToken; // caller passes already-encrypted value; blank = keep existing
        }
        this.projectKey = projectKey;
        this.issueType = (issueType != null && !issueType.isBlank()) ? issueType : "Task";
        this.enabled = enabled;
    }

    public boolean isConfigured() {
        return enabled && baseUrl != null && !baseUrl.isBlank()
                && email != null && !email.isBlank()
                && apiToken != null && !apiToken.isBlank()
                && projectKey != null && !projectKey.isBlank();
    }
}
