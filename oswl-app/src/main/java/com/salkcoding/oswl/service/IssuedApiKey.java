package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.ApiKey;

/**
 * Result of issuing a CLI API key — plain token is available only in this object.
 */
public record IssuedApiKey(ApiKey key, String plainToken) {}
