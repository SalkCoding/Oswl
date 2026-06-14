package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class NotificationSettingDto {
    boolean slackConfigured;
    boolean teamsConfigured;
    boolean emailDigestEnabled;
    boolean notifyCriticalCve;
    boolean notifyLicenseViolation;
    String slackWebhookUrl;
    String teamsWebhookUrl;
}
