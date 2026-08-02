package com.salkcoding.oswl.client;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Spring-managed wrapper around {@link CpeNameMapper} so the enrichment pipeline can
 * inject name-to-CPE inference as a service.
 */
@Service
public class CpeMatchService {

    public List<CpeNameMapper.CpeCandidate> inferCpes(String name, String version) {
        return CpeNameMapper.infer(name, version);
    }
}
