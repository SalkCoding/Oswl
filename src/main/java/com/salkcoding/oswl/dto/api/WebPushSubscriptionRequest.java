package com.salkcoding.oswl.dto.api;
public record WebPushSubscriptionRequest(String endpoint,String p256dh,String auth,boolean newHighRisk,boolean gateFailure,String locale) {
    @Override public String toString() {return "WebPushSubscriptionRequest[redacted]";}
}
