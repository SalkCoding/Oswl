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
