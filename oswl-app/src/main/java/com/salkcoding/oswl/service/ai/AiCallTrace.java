package com.salkcoding.oswl.service.ai;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DEBUG-level tracing for long-running AI HTTP calls: periodic heartbeat logs and
 * optional prompt/response (including model "thinking"/reasoning fields) excerpts.
 */
@Component
public class AiCallTrace {

    private static final List<String> REASONING_KEYS = List.of(
            "reasoning", "reasoning_content", "thinking", "thought");

    private final AiDebugSettings settings;

    public AiCallTrace(AiDebugSettings settings) {
        this.settings = settings;
    }

    public Session begin(Logger log, String providerTag, String operation, String detail) {
        if (!log.isDebugEnabled()) {
            return Session.noop();
        }
        long startMs = System.currentTimeMillis();
        log.debug("[AI][{}] ▶ {} — {}", providerTag, operation, detail);

        ScheduledFuture<?> heartbeat = null;
        ScheduledExecutorService executor = null;
        int intervalSec = settings.getHeartbeatSeconds();
        if (intervalSec > 0) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ai-call-heartbeat");
                t.setDaemon(true);
                return t;
            });
            AtomicBoolean first = new AtomicBoolean(true);
            ScheduledExecutorService exec = executor;
            heartbeat = executor.scheduleAtFixedRate(() -> {
                long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
                if (first.getAndSet(false)) {
                    log.debug("[AI][{}] … {} — inference in progress ({}s)", providerTag, operation, elapsedSec);
                } else {
                    log.debug("[AI][{}] … {} — still waiting ({}s elapsed)", providerTag, operation, elapsedSec);
                }
            }, intervalSec, intervalSec, TimeUnit.SECONDS);
        }

        return new Session(log, providerTag, operation, startMs, heartbeat, executor);
    }

    public void logPromptExcerpt(Logger log, String providerTag, String operation, String prompt) {
        if (!log.isDebugEnabled() || !settings.isLogPromptExcerpt() || prompt == null) {
            return;
        }
        log.debug("[AI][{}] prompt excerpt ({}): {}", providerTag, operation, excerpt(prompt));
    }

    public void logAssistantMessage(Logger log, String providerTag, String operation,
                                    String content, Map<?, ?> message) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (message != null) {
            for (String key : REASONING_KEYS) {
                Object value = message.get(key);
                if (value == null) continue;
                String text = value.toString().strip();
                if (!text.isEmpty()) {
                    log.debug("[AI][{}] {} ({}): {}", providerTag, key, operation, excerpt(text));
                }
            }
        }
        if (settings.isLogResponseExcerpt() && content != null && !content.isBlank()) {
            log.debug("[AI][{}] response excerpt ({}): {}", providerTag, operation, excerpt(content));
        }
    }

    private String excerpt(String text) {
        int max = Math.max(80, settings.getExcerptChars());
        String normalized = text.replace("\r\n", "\n").strip();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "… (" + normalized.length() + " chars total)";
    }

    public static final class Session implements AutoCloseable {

        private static final Session NOOP = new Session(null, null, null, 0, null, null);

        private final Logger log;
        private final String providerTag;
        private final String operation;
        private final long startMs;
        private final ScheduledFuture<?> heartbeat;
        private final ScheduledExecutorService executor;

        private Session(Logger log, String providerTag, String operation, long startMs,
                        ScheduledFuture<?> heartbeat, ScheduledExecutorService executor) {
            this.log = log;
            this.providerTag = providerTag;
            this.operation = operation;
            this.startMs = startMs;
            this.heartbeat = heartbeat;
            this.executor = executor;
        }

        static Session noop() {
            return NOOP;
        }

        @Override
        public void close() {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            if (executor != null) {
                executor.shutdownNow();
            }
            if (log != null) {
                long elapsedMs = System.currentTimeMillis() - startMs;
                log.debug("[AI][{}] ◀ {} — finished in {}ms", providerTag, operation, elapsedMs);
            }
        }
    }
}
