package com.salkcoding.oswl.auth.entity;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_vcs_connections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserVcsConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VcsProvider provider;

    @Column(name = "server_url", length = 500)
    private String serverUrl;

    @Column(name = "access_token_encrypted", columnDefinition = "TEXT", nullable = false)
    private String accessTokenEncrypted;

    @Column(name = "vcs_username", length = 200)
    private String vcsUsername;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
