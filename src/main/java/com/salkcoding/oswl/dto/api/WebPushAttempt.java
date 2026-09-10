package com.salkcoding.oswl.dto.api;
public record WebPushAttempt(Long deliveryId,WebPushSubscriptionRequest subscription,String payload) {}
