package com.salkcoding.oswl.service.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCloneExecutorTest {
    @TempDir Path tempDir;

    @Test
    void failingProcessCannotRetainUnboundedOutputOrPartialSecretLines() throws Exception {
        assertThatThrownBy(() -> run(fixture("flood", tempDir.resolve("unused"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exit 1")
                .hasMessageContaining("[output truncated]")
                .satisfies(error -> assertThat(error.getMessage()).hasSizeLessThan(1024))
                .hasMessageNotContaining("xxxxxxxx");
    }

    @Test
    void masksLiteralPasswordEvenWithoutTokenLabel() throws Exception {
        var method = GitCloneExecutor.class.getDeclaredMethod("redactSecrets", String.class, GitCloneCredentials.class);
        method.setAccessible(true);
        assertThat(method.invoke(null, "remote echoed opaque-secret-123",
                new GitCloneCredentials("user", "opaque-secret-123")))
                .isEqualTo("remote echoed ***");
    }

    @Test
    @Timeout(20)
    void interruptionTerminatesObservedProcessAndPreservesInterrupt() throws Exception {
        Path ready = tempDir.resolve("ready.pid");
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                run(fixture("sleep", ready));
            } catch (Throwable e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        ProcessHandle process = null;
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(ready) && worker.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(Files.exists(ready)).isTrue();
            process = ProcessHandle.of(Long.parseLong(Files.readString(ready))).orElseThrow();
            assertThat(process.isAlive()).isTrue();
            worker.interrupt();
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class);
            assertThat(interrupted).isTrue();
            process.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(process.isAlive()).isFalse();
        } finally {
            worker.interrupt();
            if (process != null && process.isAlive()) process.destroyForcibly();
            worker.join(5000);
        }
    }

    @Test
    void rejectsCredentialBearingAndNonHttpsDestinationsBeforeSpawningGit() {
        for (String url : List.of("http://github.com/a/b", "https://user@github.com/a/b",
                "https://github.com/a/b?token=value", "https://github.com/a/b#fragment")) {
            assertThatThrownBy(() -> new GitCloneExecutor().clone(url,
                    new GitCloneCredentials("user", "secret"), null, tempDir.resolve("clone"), "test"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static List<String> fixture(String mode, Path ready) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(java, "-cp", classes, ProcessFixture.class.getName(), mode, ready.toString());
    }

    private static void run(List<String> command) throws Exception {
        var method = GitCloneExecutor.class.getDeclaredMethod("runGit", List.class, Path.class,
                GitCloneCredentials.class, String.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(null, command, null, null, "test", "process fixture");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    public static class ProcessFixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("flood")) {
                System.out.print("x".repeat(1024 * 1024));
                System.exit(1);
            }
            Path destination = Path.of(args[1]);
            Path staging = destination.resolveSibling("pid.staging");
            Files.writeString(staging, String.valueOf(ProcessHandle.current().pid()));
            Files.move(staging, destination);
            Thread.sleep(60_000);
        }
    }
}
