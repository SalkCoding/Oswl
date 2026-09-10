package com.salkcoding.oswl.domain.entity.notification;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Entity @Table(name="web_push_subscriptions", uniqueConstraints=@UniqueConstraint(columnNames="endpoint_hash"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class WebPushSubscription {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(name="endpoint_hash",nullable=false,length=64) private String endpointHash;
    @Column(name="encrypted_subscription",nullable=false,columnDefinition="TEXT") private String encryptedSubscription;
    @Column(name="expires_at",nullable=false) private LocalDateTime expiresAt;
}
