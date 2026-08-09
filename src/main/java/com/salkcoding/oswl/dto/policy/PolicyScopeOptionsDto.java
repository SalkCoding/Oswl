package com.salkcoding.oswl.dto.policy;

import java.util.List;

/**
 * Options for the policy scope selector in the settings UI — the singleton
 * organization (if the bootstrap row exists), every team, and every active project.
 */
public record PolicyScopeOptionsDto(Option organization, List<Option> teams, List<Option> projects) {

    public record Option(Long id, String name) {}
}
