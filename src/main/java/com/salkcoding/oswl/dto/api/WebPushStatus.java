package com.salkcoding.oswl.dto.api;
import java.time.LocalDateTime;
import java.util.List;
public record WebPushStatus(boolean enabled,String publicKey,List<Subscription> subscriptions) {
    public record Subscription(Long id,String endpointHash,boolean newHighRisk,boolean gateFailure,LocalDateTime expiresAt,boolean active) {}
}
