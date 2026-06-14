package com.salkcoding.oswl.service.ai;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.FAST)
@DisplayName("AiCallTrace 단위 테스트")
class AiCallTraceTest {

    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void tearDown() {
        if (appender != null) {
            Logger logback = (Logger) LoggerFactory.getLogger("ai-trace-test");
            logback.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("heartbeat가 설정되면 대기 중 주기적으로 DEBUG 로그를 남긴다")
    void begin_emitsHeartbeatWhileWaiting() throws Exception {
        AiDebugSettings settings = new AiDebugSettings();
        ReflectionTestUtils.setField(settings, "heartbeatSeconds", 1);

        Logger logback = (Logger) LoggerFactory.getLogger("ai-trace-test");
        logback.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logback.addAppender(appender);

        AiCallTrace trace = new AiCallTrace(settings);
        CountDownLatch done = new CountDownLatch(1);
        org.slf4j.Logger slf4j = LoggerFactory.getLogger("ai-trace-test");

        Thread worker = new Thread(() -> {
            try (AiCallTrace.Session session = trace.begin(slf4j, "Test", "batch.cve", "items=1")) {
                Thread.sleep(2500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        worker.start();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        long heartbeatLogs = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("still waiting")
                        || e.getFormattedMessage().contains("inference in progress"))
                .count();
        assertThat(heartbeatLogs).isGreaterThanOrEqualTo(1);
    }
}
