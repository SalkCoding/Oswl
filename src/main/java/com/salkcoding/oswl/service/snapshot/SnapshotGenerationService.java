package com.salkcoding.oswl.service.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.repository.DatabaseMutationLockRepository;
import com.salkcoding.oswl.repository.snapshot.SnapshotGenerationRepository;
import com.salkcoding.oswl.repository.snapshot.SnapshotMetaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SnapshotGenerationService {
    private final SnapshotGenerationRepository generations;
    private final SnapshotMetaRepository metadata;
    private final DatabaseMutationLockRepository locks;
    private final ObjectMapper json = new ObjectMapper();

    /** Preserve the legacy store before the first publication changes its rows. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void initialize() {
        locks.lock(DatabaseMutationLockRepository.SNAPSHOT_GENERATION);
        generations.initializePointer();
        if (generations.activeId() == null) publish();
    }

    public long activeGeneration() {
        Long active = generations.activeId();
        if (active == null) throw new IllegalStateException("Snapshot generations are not initialized");
        return active;
    }

    public SnapshotGenerationScope open(long generationId) {
        try {
            var root = json.readTree(generations.metadata(generationId));
            if (!root.isObject()) throw new IllegalStateException("Invalid snapshot generation metadata");
            java.util.Map<String, java.time.LocalDate> dates = new java.util.LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                var date = entry.getValue().path("sourceAsOf");
                dates.put(entry.getKey(), date.isNull() || date.isMissingNode() ? null : java.time.LocalDate.parse(date.asText()));
            });
            return new SnapshotGenerationScope(generationId, dates);
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Snapshot generation metadata is unreadable", invalid);
        }
    }

    public java.util.Set<String> keys(long id, String source) { return generations.keys(id, source); }

    public java.util.Map<String, String> payloads(long id, String source, java.util.Collection<String> keys) {
        return generations.payloads(id, source, keys);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long publish() {
        var sources = json.createObjectNode();
        for (var source : metadata.findAll()) {
            var node = sources.putObject(source.getSource());
            node.put("recordCount", source.getRecordCount());
            node.put("importedAt", source.getImportedAt() == null ? null : source.getImportedAt().toString());
            node.put("bundleId", source.getBundleId());
            node.put("builtAt", source.getBuiltAt() == null ? null : source.getBuiltAt().toString());
            node.put("sourceAsOf", source.getSourceAsOf() == null ? null : source.getSourceAsOf().toString());
            node.put("origin", source.getOrigin());
            node.put("formatVersion", source.getFormatVersion());
            node.put("dataNotices", source.getDataNotices());
        }
        return generations.capture(sources.toString());
    }
}
