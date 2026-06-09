package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Represents one resolved dependency path from the root project to a scanned library.
 * Used exclusively in the component detail panel to render the dependency tree UI.
 *
 * <p>A component may have multiple paths (e.g. reached as both a direct dep AND a
 * transitive dep through another library). Each {@code DependencyPathDto} corresponds
 * to one such path.</p>
 */
@Getter
@Builder
public class DependencyPathDto {

    /** 0-based ordinal among all paths for this component */
    private final int pathIndex;

    /** Total number of nodes in this path (root + intermediates + target) */
    private final int depth;

    /**
     * True when the target library is a direct dependency (no intermediate nodes).
     * Equivalent to {@code depth == 2}: [root, target].
     */
    private final boolean direct;

    /** All nodes in order: root (index 0) → ... → target library (last) */
    private final List<PathNodeDto> nodes;

    // ── Nested DTO ────────────────────────────────────────────────────────

    @Getter
    @Builder
    public static class PathNodeDto {

        /** Full package name as reported by the CLI, e.g. "org.springframework:spring-web" */
        private final String name;

        /**
         * Display-friendly short name: the portion after the last ':' or '/',
         * falling back to the full name if no separator is present.
         */
        private final String shortName;

        /** Package version, e.g. "6.0.0" */
        private final String version;

        /** True for the first node in the path (the root project itself) */
        private final boolean root;

        /** True for the last node in the path (the currently viewed library) */
        private final boolean target;

        /** 0-based position of this node within the path */
        private final int index;
    }
}
