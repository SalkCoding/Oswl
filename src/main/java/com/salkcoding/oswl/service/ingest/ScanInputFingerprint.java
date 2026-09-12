package com.salkcoding.oswl.service.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Fixed serialization contract: transport formatting and credentials are not analysis input. */
final class ScanInputFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ScanInputFingerprint() {}

    static String digest(ScanPayload payload) {
        try {
            var input = new Input("scan-input-v1", payload.getVersion(), payload.getSubmitterEmail(),
                    payload.getComponents() == null ? null : payload.getComponents().stream()
                            .map(ScanInputFingerprint::component).toList());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(input)));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint scan input", e);
        }
    }

    private static Component component(ScanPayload.ComponentPayload c) {
        return new Component(c.getName(), c.getVersion(), c.getEcosystem(), c.getDependencyInfo(),
                c.getScope(), c.getLicenses(), c.getDependencyPaths() == null ? null :
                c.getDependencyPaths().stream().map(path -> path == null ? null : path.stream()
                        .map(n -> new Node(n.getName(), n.getVersion())).toList()).toList());
    }

    private record Input(String contract, String version, String submitterEmail, List<Component> components) {}
    private record Component(String name, String version, String ecosystem, String dependencyInfo,
                             String scope, List<String> licenses, List<List<Node>> dependencyPaths) {}
    private record Node(String name, String version) {}
}
