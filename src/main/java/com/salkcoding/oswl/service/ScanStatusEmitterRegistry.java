package com.salkcoding.oswl.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of open SSE emitters, keyed by project ID.
 * Call {@link #notifyStatus} after a scan transaction commits to push events.
 */
@Slf4j
@Component
public class ScanStatusEmitterRegistry {

    /** projectId → active emitters watching that project */
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> registry =
            new ConcurrentHashMap<>();

    /**
     * Opens a new SSE emitter subscribed to the given project IDs.
     * The emitter cleans itself up on completion / timeout / error.
     */
    public SseEmitter subscribe(Collection<Long> projectIds) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = no server-side timeout

        projectIds.forEach(id ->
                registry.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(emitter));

        Runnable cleanup = () -> projectIds.forEach(id -> {
            CopyOnWriteArrayList<SseEmitter> list = registry.get(id);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) {
                    registry.remove(id, list);
                }
            }
        });

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }

    /**
     * Pushes a {@code scan-update} event to all emitters watching {@code projectId}.
     * Must be called <em>after</em> the DB transaction has committed.
     */
    public void notifyStatus(Long projectId, String status) {
        CopyOnWriteArrayList<SseEmitter> emitters = registry.get(projectId);
        if (emitters == null || emitters.isEmpty()) return;

        String data = "{\"projectId\":%d,\"status\":\"%s\"}".formatted(projectId, status);
        List<SseEmitter> stale = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("scan-update").data(data));
                emitter.complete();
            } catch (IOException e) {
                stale.add(emitter);
                log.debug("[SSE] stale emitter removed for project {}: {}", projectId, e.getMessage());
            }
        }

        emitters.removeAll(stale);
        if (emitters.isEmpty()) {
            registry.remove(projectId, emitters);
        }
        log.debug("[SSE] notified {} emitter(s) for project {} → {}", emitters.size() + stale.size(), projectId, status);
    }
}
