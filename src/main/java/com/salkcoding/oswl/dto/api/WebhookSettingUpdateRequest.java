package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.WebhookProvider;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

/**
 * Save request for webhook settings. A blank URL keeps the existing encrypted URL.
 */
@Value
public class WebhookSettingUpdateRequest {

    @NotNull
    WebhookProvider provider;

    String url;
    boolean enabled;
    boolean notifyNewCve;
    boolean notifyGateFailure;
    boolean notifyScanFailure;
    boolean notifyWaiverExpiry;
}
