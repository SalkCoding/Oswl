package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.WebhookProvider;
import lombok.Builder;
import lombok.Value;

/**
 * Current Slack/Teams webhook settings. The URL itself is never returned;
 * {@code hasUrl} indicates that an encrypted URL is stored.
 */
@Value
@Builder
public class WebhookSettingResponse {

    WebhookProvider provider;
    boolean hasUrl;
    boolean enabled;
    boolean notifyNewCve;
    boolean notifyGateFailure;
    boolean notifyScanFailure;
    boolean notifyWaiverExpiry;
}
