package com.salkcoding.oswl.dto.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Value;

/**
 * Test request that sends a sample message to the supplied URL without persisting it.
 */
@Value
public class WebhookTestRequest {

    @NotBlank
    String url;
}
