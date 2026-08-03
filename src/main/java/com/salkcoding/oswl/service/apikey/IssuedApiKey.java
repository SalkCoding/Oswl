package com.salkcoding.oswl.service.apikey;

import com.salkcoding.oswl.domain.entity.apikey.ApiKey;

/**
 * Result of issuing a CLI API key — plain token is available only in this object.
 */
public record IssuedApiKey(ApiKey key, String plainToken) {}
