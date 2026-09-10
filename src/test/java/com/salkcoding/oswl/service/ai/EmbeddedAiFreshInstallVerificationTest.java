package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.repository.ai.AiPreferencesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Explicit opt-in: downloads the pinned full model into a new directory and runs a local runtime. */
@EnabledIfEnvironmentVariable(named = "OSWL_VERIFY_FRESH_MODEL", matches = "true")
class EmbeddedAiFreshInstallVerificationTest {
    @Test void realDownloadChecksumStartupAndInference() throws Exception {
        Path parent = Path.of("build/fresh-model-verification"); Files.createDirectories(parent);
        Path root = Files.createTempDirectory(parent, "install-").toAbsolutePath();
        Path runtime = Path.of("embedded-ai/llama");
        assertThat(Files.isDirectory(runtime)).as("Existing runtime required; model files are not reused").isTrue();
        Files.createDirectory(root.resolve("llama"));
        try (var files = Files.list(runtime)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) Files.copy(file, root.resolve("llama").resolve(file.getFileName()));
        }
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var service = new EmbeddedAiService(root.toString(), port, 1024, 0, 2, 1, true, 0, "", 120,
                "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf",
                "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", 1280835840L, "", false,
                mock(AiPreferencesRepository.class));
        Path report = Path.of("build/reports/remaining/fresh-model.txt"); Files.createDirectories(report.getParent());
        Files.writeString(report, "directory=" + root + "\nstatus=downloading\n");
        try {
            service.downloadDefaultModel();
            assertThat(service.status().getLastError()).isNull();
            Files.writeString(report, "directory=" + root + "\nchecksum=verified\nstatus=starting\n");
            service.start();
            assertThat(service.status().isRunning()).as(service.status().getLastError()).isTrue();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[{\"role\":\"user\",\"content\":\"Reply with OK.\"}],\"max_tokens\":16,\"temperature\":0}")) .build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("choices");
            Process ownedProcess = (Process) org.springframework.test.util.ReflectionTestUtils.getField(service, "process");
            assertThat(ownedProcess).isNotNull();
            ownedProcess.destroyForcibly().waitFor();
            assertThat(service.status().isRunning()).isFalse();
            service.start();
            assertThat(service.status().isRunning()).as(service.status().getLastError()).isTrue();
            var retried = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(retried.statusCode()).isEqualTo(200);
            Files.writeString(report, "directory=" + root + "\nchecksum=verified\nstartup=healthy\ninferenceHTTP=200\nforcedKill=owned-process-only\nrestartInferenceHTTP=200\n" + response.body());
        } finally { service.stop(); }
    }
}
