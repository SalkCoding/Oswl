package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.repository.ai.AiPreferencesRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EmbeddedAiDownloadTest {
    @TempDir Path directory;
    private HttpServer server;
    private final byte[] good = "GGUF-transport-fixture-only".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger code = new AtomicInteger(200);
    private final AtomicReference<byte[]> payload = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> hold = new AtomicReference<>();
    private final CountDownLatch received = new CountDownLatch(1);

    @BeforeEach void serve() throws Exception {
        payload.set(good);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/model", exchange -> {
            requests.incrementAndGet(); received.countDown();
            try {
                if (hold.get()!=null) hold.get().await(5, TimeUnit.SECONDS);
                byte[] data=payload.get(); exchange.sendResponseHeaders(code.get(),data.length);
                exchange.getResponseBody().write(data);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void close() { if(hold.get()!=null)hold.get().countDown(); server.stop(0); }
    private EmbeddedAiService service(boolean offline, String fallback) throws Exception {
        return new EmbeddedAiService(directory.toString(), server.getAddress().getPort(), 512,0,1,1,true,0,"",1,
                "http://127.0.0.1:"+server.getAddress().getPort()+"/model",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(good)),good.length,fallback,offline,
                mock(AiPreferencesRepository.class));
    }
    private Path model() { return directory.resolve("model/Qwen/Qwen3.5-2B-Q4_K_M.gguf"); }
    private void noPartial() throws Exception { try(var files=Files.walk(directory)){assertThat(files.filter(p->p.toString().endsWith(".part")).toList()).isEmpty();} }

    @Test void freshDownloadPublishesOnlyVerifiedFileAndDoesNotStartServer() throws Exception {
        var service=service(false,"");
        service.downloadDefaultModel();
        assertThat(Files.readAllBytes(model())).isEqualTo(good);
        assertThat(service.isDownloading()).isFalse();
        assertThat(service.status().getLastError()).isNull();
        assertThat(service.status().isRunning()).isFalse(); noPartial();
    }
    @Test void corruptDownloadPreservesExistingFileAndRetryClearsFailure() throws Exception {
        Files.createDirectories(model().getParent()); Files.writeString(model(),"existing user model");
        payload.set("X".repeat(good.length).getBytes()); var service=service(false,"");
        assertThatThrownBy(service::downloadDefaultModel).hasMessageContaining("checksum");
        assertThat(Files.readString(model())).isEqualTo("existing user model");
        assertThat(service.status().getLastError()).contains("checksum"); noPartial();
        payload.set(good); service.downloadDefaultModel();
        assertThat(service.status().getLastError()).isNull(); assertThat(Files.readAllBytes(model())).isEqualTo(good);
    }
    @Test void failedAndOversizedResponsesRemainFailuresWithoutPartialFiles() throws Exception {
        var service=service(false,""); code.set(503);
        service.downloadDefaultModelAsync();
        assertThat(service.status().getLastError()).contains("HTTP 503"); assertThat(model()).doesNotExist();
        code.set(200); payload.set(new byte[good.length+1]);
        assertThatThrownBy(service::downloadDefaultModel).hasMessageContaining("expected size");
        assertThat(service.isDownloading()).isFalse(); noPartial();
    }
    @Test void offlineModeNeverRequestsModel() throws Exception {
        var service=service(true,"");
        assertThatThrownBy(service::downloadDefaultModel).hasMessageContaining("Air-gapped");
        assertThat(requests.get()).isZero(); assertThat(model()).doesNotExist();
    }
    @Test void concurrentRequestsDoNotStartSecondDownloadAndInterruptionDoesNotFallback() throws Exception {
        hold.set(new CountDownLatch(1)); var service=service(false,"http://127.0.0.1:"+server.getAddress().getPort()+"/model?fallback");
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread worker=Thread.ofVirtual().start(()->{try{service.downloadDefaultModel();}catch(Throwable e){failure.set(e);}});
        assertThat(received.await(3,TimeUnit.SECONDS)).isTrue();
        try {
            service.downloadDefaultModel(); assertThat(requests.get()).isEqualTo(1);
            worker.interrupt(); worker.join(3000);
            assertThat(worker.isAlive()).isFalse(); assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            assertThat(service.isDownloading()).isFalse(); assertThat(requests.get()).isEqualTo(1); noPartial();
        } finally {hold.get().countDown(); worker.interrupt(); worker.join(3000);}
    }
}
