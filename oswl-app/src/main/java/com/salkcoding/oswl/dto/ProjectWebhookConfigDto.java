package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ProjectWebhookConfigDto {
    boolean enabled;
    String webhookUrl;
    /** Plain secret — only returned immediately after rotate; otherwise null. */
    String secret;
    boolean secretConfigured;
}
