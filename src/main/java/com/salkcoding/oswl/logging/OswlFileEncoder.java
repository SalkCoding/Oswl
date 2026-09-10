package com.salkcoding.oswl.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Production file-log encoder. Plain text by default (same fields/order as the
 * console); OSWL_LOG_JSON=true switches every line to a single JSON object for direct SIEM
 * ingestion. The env var is read once at JVM start — no extra logging library, no logback
 * conditional-processing dependency (Janino) needed, since the choice is made in Java rather
 * than in the XML config.
 */
public class OswlFileEncoder extends EncoderBase<ILoggingEvent> {

    private static final boolean JSON_ENABLED = "true".equalsIgnoreCase(System.getenv("OSWL_LOG_JSON"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PatternLayout plainLayout;

    @Override
    public void start() {
        if (!JSON_ENABLED) {
            plainLayout = new PatternLayout();
            plainLayout.setContext(context);
            plainLayout.setPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} %5p [req=%X{requestId:--}] [user=%X{userId:--}] "
                    + "--- [%15.15t] %-40.40logger{39} : %m%n%wEx");
            plainLayout.start();
        }
        super.start();
    }

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        String line = JSON_ENABLED ? encodeJson(event) : plainLayout.doLayout(event);
        return line.getBytes(StandardCharsets.UTF_8);
    }

    private String encodeJson(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        fields.put("level", event.getLevel().toString());
        fields.put("logger", event.getLoggerName());
        fields.put("thread", event.getThreadName());
        fields.put("message", event.getFormattedMessage());
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null && mdc.get("requestId") != null) {
            fields.put("requestId", mdc.get("requestId"));
        }
        if (mdc != null && mdc.get("userId") != null) {
            fields.put("userId", mdc.get("userId"));
        }
        IThrowableProxy throwableProxy = event.getThrowableProxy();
        if (throwableProxy != null) {
            fields.put("exception", ThrowableProxyUtil.asString(throwableProxy));
        }
        try {
            return MAPPER.writeValueAsString(fields) + System.lineSeparator();
        } catch (Exception e) {
            return event.getFormattedMessage() + System.lineSeparator();
        }
    }
}
