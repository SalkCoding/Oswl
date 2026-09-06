package com.salkcoding.oswl.controller.spec;
import com.salkcoding.oswl.dto.api.*;
import io.swagger.v3.oas.annotations.Operation;
public interface WebPushControllerSpec {
    @Operation(summary="Read this account's browser subscriptions and public VAPID configuration") WebPushStatus status();
    @Operation(summary="Subscribe or update this account's browser security alerts") Long subscribe(WebPushSubscriptionRequest request);
    @Operation(summary="Remove an owned browser subscription and its queued deliveries") void unsubscribe(Long id);
}
