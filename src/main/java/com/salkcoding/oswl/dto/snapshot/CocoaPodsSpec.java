package com.salkcoding.oswl.dto.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.exception.InvalidRequestException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** User-supplied original podspec with independently verifiable provenance. */
public record CocoaPodsSpec(String name, String version, String origin, String sha256, String podspec) {
    public void validate() {
        try {
            if (name == null || !name.matches("[A-Za-z0-9_+.-]{1,200}") || name.equals(".") || name.equals("..")
                    || version == null || !version.matches("[A-Za-z0-9_+.-]{1,100}") || version.equals(".") || version.equals(".."))
                throw new IllegalArgumentException();
            URI uri = URI.create(origin);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || origin.length() > 2000)
                throw new IllegalArgumentException();
            if (podspec == null || podspec.length() > 65536) throw new IllegalArgumentException();
            byte[] bytes = podspec.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 65536 || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(sha256))
                throw new IllegalArgumentException();
            var root = new ObjectMapper().readTree(podspec);
            if (!root.isObject() || !name.equals(root.path("name").asText()) || !version.equals(root.path("version").asText()))
                throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new InvalidRequestException("Invalid CocoaPods spec identity, provenance, checksum or size");
        }
    }
}
