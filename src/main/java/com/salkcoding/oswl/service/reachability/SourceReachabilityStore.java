package com.salkcoding.oswl.service.reachability;

import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SourceReachabilityStore {
    private final ScanComponentRepository repository;
    public record Candidate(Long id, SourceReferenceAnalyzer.Language language, Set<String> names) {}
    public record Update(Long id, SourceReferenceAnalyzer.AnalysisResult result, String analysis) {}

    @Transactional(readOnly = true)
    public List<Candidate> candidates(Long scanId) {
        return repository.findSourceCandidates(scanId).stream().map(row -> new Candidate(row.getId(),
                "PYPI".equalsIgnoreCase(row.getEcosystem()) ? SourceReferenceAnalyzer.Language.PYTHON
                        : SourceReferenceAnalyzer.Language.JAVASCRIPT,
                LibraryPackageMapper.mapImportNames(row.getEcosystem(), row.getName()))).toList();
    }

    @Transactional
    public void save(List<Update> updates) {
        for (Update update : updates) {
            String evidence = update.result().evidence().stream()
                    .map(e -> e.referencingFile() + " -> import " + e.importedName())
                    .collect(java.util.stream.Collectors.joining("\n"));
            repository.updateSourceAnalysis(update.id(), update.result().reachability(),
                    evidence.isBlank() ? null : evidence, update.analysis());
        }
    }
}
