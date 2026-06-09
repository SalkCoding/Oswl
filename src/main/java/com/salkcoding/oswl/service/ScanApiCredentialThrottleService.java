package com.salkcoding.oswl.service;

import com.salkcoding.oswl.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate limiting for CLI scan API credential checks (API key + submitter password).
 * Keys are scoped per project and submitter email (or client IP for invalid API keys).
 */
@Slf4j
@Service
public class ScanApiCredentialThrottleService {

    private final int failureMaxAttempts;
    private final long failureWindowMs;
    private final int requestMaxAttempts;
    private final long requestWindowMs;

    private final ConcurrentHashMap<String, Window> failureWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> requestWindows = new ConcurrentHashMap<>();

    public ScanApiCredentialThrottleService(
            @Value("${oswl.scan-api.auth-failure-max-attempts:10}") int failureMaxAttempts,
            @Value("${oswl.scan-api.auth-failure-window-seconds:900}") int failureWindowSeconds,
            @Value("${oswl.scan-api.request-max-per-minute:30}") int requestMaxPerMinute) {
        this.failureMaxAttempts = failureMaxAttempts;
        this.failureWindowMs = failureWindowSeconds * 1000L;
        this.requestMaxAttempts = requestMaxPerMinute;
        this.requestWindowMs = 60_000L;
    }

    /** Before validating submitter password — limits brute force per project+email. */
    public void assertCredentialCheckAllowed(Long projectId, String submitterEmail) {
        assertAllowed(requestKey(projectId, submitterEmail), requestWindows, requestMaxAttempts, requestWindowMs,
                "Too many scan submission attempts. Try again later.");
    }

    public void recordCredentialSuccess(Long projectId, String submitterEmail) {
        String key = requestKey(projectId, submitterEmail);
        failureWindows.remove(key);
        requestWindows.remove(key);
    }

    public void recordCredentialFailure(Long projectId, String submitterEmail) {
        String key = requestKey(projectId, submitterEmail);
        recordFailure(key);
        assertFailureLimit(key);
    }

    private void assertFailureLimit(String key) {
        Window window = failureWindows.get(key);
        if (window == null) {
            return;
        }
        synchronized (window) {
            prune(window.events, failureWindowMs);
            if (window.events.size() > failureMaxAttempts) {
                throw new TooManyRequestsException(
                        "Too many failed scan authentication attempts. Try again later.");
            }
        }
    }

    /** Invalid API key attempts — keyed by client IP to slow key guessing. */
    public void assertApiKeyCheckAllowed(String clientIp) {
        assertAllowed("api-key:" + normalizeIp(clientIp), failureWindows, failureMaxAttempts, failureWindowMs,
                "Too many invalid API key attempts. Try again later.");
    }

    public void recordApiKeyFailure(String clientIp) {
        recordFailure("api-key:" + normalizeIp(clientIp));
    }

    private void recordFailure(String key) {
        Window window = failureWindows.computeIfAbsent(key, k -> new Window());
        synchronized (window) {
            prune(window.events, failureWindowMs);
            window.events.addLast(Instant.now());
        }
        purgeIdleWindow(key, failureWindows, failureWindowMs);
    }

    private static void assertAllowed(String key, ConcurrentHashMap<String, Window> map,
                                      int maxAttempts, long windowMs, String message) {
        Window window = map.computeIfAbsent(key, k -> new Window());
        synchronized (window) {
            prune(window.events, windowMs);
            if (window.events.size() >= maxAttempts) {
                log.warn("[ScanApiThrottle] Rate limit exceeded for key={}", key);
                throw new TooManyRequestsException(message);
            }
            window.events.addLast(Instant.now());
        }
        purgeIdleWindow(key, map, windowMs);
    }

    /** Drops map entries whose sliding window has no recent events (prevents unbounded key growth). */
    private static void purgeIdleWindow(String key, ConcurrentHashMap<String, Window> map, long windowMs) {
        Window window = map.get(key);
        if (window == null) return;
        synchronized (window) {
            prune(window.events, windowMs);
            if (window.events.isEmpty()) {
                map.remove(key, window);
            }
        }
    }

    @Scheduled(fixedDelay = 3_600_000)
    void purgeStaleThrottleWindows() {
        int removed = 0;
        removed += purgeMap(failureWindows, failureWindowMs);
        removed += purgeMap(requestWindows, requestWindowMs);
        if (removed > 0) {
            log.debug("[ScanApiThrottle] Purged {} idle rate-limit window(s)", removed);
        }
    }

    private static int purgeMap(ConcurrentHashMap<String, Window> map, long windowMs) {
        int count = 0;
        for (var entry : map.entrySet()) {
            Window window = entry.getValue();
            synchronized (window) {
                prune(window.events, windowMs);
                if (window.events.isEmpty() && map.remove(entry.getKey(), window)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String requestKey(Long projectId, String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        return "cred:" + projectId + ":" + normalized;
    }

    private static String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    private static void prune(Deque<Instant> events, long windowMs) {
        Instant cutoff = Instant.now().minusMillis(windowMs);
        while (!events.isEmpty() && events.peekFirst().isBefore(cutoff)) {
            events.removeFirst();
        }
    }

    private static final class Window {
        final Deque<Instant> events = new ArrayDeque<>();
    }
}
