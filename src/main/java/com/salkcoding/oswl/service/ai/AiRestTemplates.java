package com.salkcoding.oswl.service.ai;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Shared {@link RestTemplate} factory for the AI provider clients.
 *
 * <p>A bare {@code new RestTemplate()} has <em>no</em> connect or read timeout, so an
 * unreachable or stalled provider pins the calling request thread indefinitely — and because
 * enrichment retries failed batches, one stall is paid more than once. Every other outbound
 * client in this codebase (GitHub, GitLab, Bitbucket, deps.dev) sets explicit timeouts; these
 * bring the AI clients in line.
 *
 * <p>The read timeout is deliberately generous: a large batch prompt against a slow local
 * model legitimately takes tens of seconds, so this is a backstop against hangs rather than a
 * latency target.
 */
final class AiRestTemplates {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(90);
    /** Connection tests only list models, so they should fail fast instead of hanging the UI. */
    private static final Duration PROBE_READ_TIMEOUT = Duration.ofSeconds(15);

    private AiRestTemplates() {}

    static RestTemplate forCompletions() {
        return build(READ_TIMEOUT);
    }

    static RestTemplate forProbe() {
        return build(PROBE_READ_TIMEOUT);
    }

    private static RestTemplate build(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }
}
