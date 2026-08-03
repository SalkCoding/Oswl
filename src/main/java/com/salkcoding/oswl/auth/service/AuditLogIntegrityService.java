package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.dto.AuditLogIntegrityReport;
import com.salkcoding.oswl.auth.entity.AuditLog;
import com.salkcoding.oswl.auth.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditLogIntegrityService {

    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int MAX_BROKEN_IDS = 100;

    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public AuditLogIntegrityReport verify(AuditLogFilter filter) {
        return verify(filter, DEFAULT_BATCH_SIZE);
    }

    /**
     * Verifies the audit log integrity hash chain in batches.
     *
     * <p>Rows are examined in primary-key order. Legacy rows without a stored hash are allowed
     * only before the first hashed row; once the chain has started, an unhashed row is treated
     * as a broken link. Each hashed row must reference the stored hash of the row immediately
     * before it and its own stored hash must match a recomputation over its contents.</p>
     */
    @Transactional(readOnly = true)
    public AuditLogIntegrityReport verify(AuditLogFilter filter, int batchSize) {
        if (batchSize < 1) {
            batchSize = DEFAULT_BATCH_SIZE;
        }

        long total = 0;
        long unhashedCount = 0;
        long brokenCount = 0;
        Long firstBrokenId = null;
        Long lastId = null;
        List<Long> brokenIds = new ArrayList<>();

        String previousHash = null;
        boolean previousWasHashed = false;
        boolean chainStartedInResult = false;

        int pageNumber = 0;
        Page<AuditLog> page;
        do {
            Pageable pageable = PageRequest.of(pageNumber, batchSize);
            page = auditLogRepository.findAllByFilterOrderByIdAsc(
                    filter != null ? filter.getStartDate() : null,
                    filter != null ? filter.getEndDate() : null,
                    pageable);

            for (AuditLog entry : page.getContent()) {
                total++;
                lastId = entry.getId();

                boolean currentHashed = entry.getHash() != null;

                if (!currentHashed) {
                    unhashedCount++;
                    if (previousWasHashed) {
                        // A hashed row was followed by an unhashed one: the chain is broken.
                        brokenCount++;
                        if (firstBrokenId == null) {
                            firstBrokenId = entry.getId();
                        }
                        addBrokenId(brokenIds, entry.getId());
                    }
                    previousWasHashed = false;
                    previousHash = null;
                    continue;
                }

                if (!chainStartedInResult) {
                    // First hashed row in the result set starts the verified segment.
                    chainStartedInResult = true;
                    // If this is the absolute chain start, prevHash must be null. When a date
                    // filter omits earlier rows we skip this check for the first hashed row.
                    if (filter == null
                            || (filter.getStartDate() == null && filter.getEndDate() == null)) {
                        if (entry.getPrevHash() != null) {
                            brokenCount++;
                            if (firstBrokenId == null) {
                                firstBrokenId = entry.getId();
                            }
                            addBrokenId(brokenIds, entry.getId());
                        }
                    }
                } else if (previousWasHashed) {
                    if (entry.getPrevHash() == null
                            || !entry.getPrevHash().equals(previousHash)
                            || !auditLogService.computeHash(entry).equals(entry.getHash())) {
                        brokenCount++;
                        if (firstBrokenId == null) {
                            firstBrokenId = entry.getId();
                        }
                        addBrokenId(brokenIds, entry.getId());
                    }
                }

                previousHash = entry.getHash();
                previousWasHashed = true;
            }
            pageNumber++;
        } while (page.hasNext());

        AuditLogIntegrityReport report = AuditLogIntegrityReport.builder()
                .verified(brokenCount == 0)
                .total(total)
                .unhashedCount(unhashedCount)
                .brokenCount(brokenCount)
                .firstBrokenId(firstBrokenId)
                .lastId(lastId)
                .brokenIds(brokenIds)
                .build();

        auditLogService.log("AUDIT_LOG.VERIFY", "AUDIT_LOG", null, null,
                "total=" + total + "; broken=" + brokenCount
                        + (firstBrokenId != null ? "; firstBrokenId=" + firstBrokenId : ""));

        return report;
    }

    private void addBrokenId(List<Long> brokenIds, Long id) {
        if (brokenIds.size() < MAX_BROKEN_IDS) {
            brokenIds.add(id);
        }
    }
}
