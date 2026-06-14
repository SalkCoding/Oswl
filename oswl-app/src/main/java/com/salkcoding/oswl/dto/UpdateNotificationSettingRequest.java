package com.salkcoding.oswl.dto;

import lombok.Data;

@Data
public class UpdateNotificationSettingRequest {
    private String slackWebhookUrl;
    private String teamsWebhookUrl;
    private Boolean clearSlackWebhook;
    private Boolean clearTeamsWebhook;
    private Boolean emailDigestEnabled;
    private Boolean notifyCriticalCve;
    private Boolean notifyLicenseViolation;
}
