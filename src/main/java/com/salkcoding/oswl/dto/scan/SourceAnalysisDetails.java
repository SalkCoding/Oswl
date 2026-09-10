package com.salkcoding.oswl.dto.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Persisted static-analysis coverage; absence is not a runtime safety finding. */
public record SourceAnalysisDetails(String language, int fileCount, long bytesRead,
        String confidence, boolean partial, String reason, List<String> limitations) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public String toJson() {
        try { return JSON.writeValueAsString(this); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode source analysis", e); }
    }
    public static SourceAnalysisDetails fromJson(String value) {
        if (value == null || value.isBlank()) return null;
        try { return JSON.readValue(value, SourceAnalysisDetails.class); }
        catch (Exception e) { return null; }
    }
}
