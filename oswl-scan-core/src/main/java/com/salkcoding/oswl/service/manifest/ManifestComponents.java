package com.salkcoding.oswl.service.manifest;

import com.salkcoding.oswl.dto.scan.ScanPayload;

import java.util.List;

/** Factory helpers for manifest parse results. */
public final class ManifestComponents {

    private ManifestComponents() {
    }

    public static ScanPayload.ComponentPayload direct(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
