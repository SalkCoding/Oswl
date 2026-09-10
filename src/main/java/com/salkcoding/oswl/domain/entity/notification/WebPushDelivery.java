package com.salkcoding.oswl.domain.entity.notification;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Entity @Table(name="web_push_deliveries",uniqueConstraints=@UniqueConstraint(columnNames={"subscription_id","event_key"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class WebPushDelivery {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="subscription_id",nullable=false) private Long subscriptionId;
    @Column(name="project_id",nullable=false) private Long projectId;
    @Column(name="event_key",nullable=false,length=100) private String eventKey;
    @Column(name="event_type",nullable=false,length=20) private String eventType;
    @Column(nullable=false) private int attempts;
    @Column(nullable=false) private boolean finished;
    @Column(name="next_attempt",nullable=false) private LocalDateTime nextAttempt;
    @Column(name="expires_at",nullable=false) private LocalDateTime expiresAt;
    @Column(name="last_status") private Integer lastStatus;
}
