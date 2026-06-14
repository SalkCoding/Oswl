package com.salkcoding.oswl.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Optional verbose AI tracing (heartbeat while waiting, prompt/response excerpts).
 * Active only when the logger is at DEBUG and {@link #getHeartbeatSeconds()} &gt; 0.
 */
@Component
public class AiDebugSettings {

    @Value("${oswl.ai.debug.heartbeat-seconds:0}")
    private int heartbeatSeconds;

    @Value("${oswl.ai.debug.excerpt-chars:500}")
    private int excerptChars;

    @Value("${oswl.ai.debug.log-prompt-excerpt:true}")
    private boolean logPromptExcerpt;

    @Value("${oswl.ai.debug.log-response-excerpt:true}")
    private boolean logResponseExcerpt;

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public int getExcerptChars() {
        return excerptChars;
    }

    public boolean isLogPromptExcerpt() {
        return logPromptExcerpt;
    }

    public boolean isLogResponseExcerpt() {
        return logResponseExcerpt;
    }
}
