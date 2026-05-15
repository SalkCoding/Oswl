package com.salkcoding.oswl.dto.cli;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class CliAuthResponse {

    private Long userId;
    private String displayName;
    private List<ProjectEntry> projects;

    @Getter
    @Builder
    public static class ProjectEntry {
        private Long id;
        private String name;
        /** Active API key for this project, scoped to this user. */
        private String apiKey;
    }
}
