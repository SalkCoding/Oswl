package com.salkcoding.oswl.dto;

import lombok.Data;

@Data
public class UpdateProjectWebhookRequest {
    private Boolean enabled;
    /** When true, generates a new secret and returns it once in the response. */
    private Boolean rotateSecret;
}
