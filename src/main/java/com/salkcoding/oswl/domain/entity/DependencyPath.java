package com.salkcoding.oswl.domain.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

/**
 * Stores one concrete dependency path from the root project to a scanned library.
 *
 * A single {@link ScanComponent} may be reached via multiple paths
 * (e.g. both as a direct dependency AND as a transitive dependency through another lib).
 * Each path is stored as one row, ordered by {@code pathIndex}.
 *
 * {@code pathNodes} holds the full chain from root (index 0) to the target library
 * (last index), serialized as a JSON array so no extra JOIN table is needed.
 *
 * Example for a transitive dependency:
 * <pre>
 *   pathNodes = [
 *     { "name": "com.example:my-app",          "version": "1.0.0" },   // root project
 *     { "name": "org.springframework:spring-web", "version": "6.0.0" }, // intermediate
 *     { "name": "commons-lang3",                "version": "3.12.0" }  // target ← this lib
 *   ]
 *   depth = 3
 * </pre>
 */
@Entity
@Table(name = "dependency_paths",
        indexes = @Index(name = "idx_dep_paths_scan_comp", columnList = "scan_component_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class DependencyPath {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_component_id", nullable = false)
    private ScanComponent scanComponent;

    /** Ordinal among multiple paths to the same component (0-based) */
    @Column(name = "path_index", nullable = false)
    private int pathIndex;

    /**
     * Ordered list of package references forming the full path from root to target.
     * Stored as a JSON array in a TEXT column for portability (H2 dev + PostgreSQL prod).
     */
    @Column(name = "path_nodes", nullable = false, columnDefinition = "TEXT")
    @Convert(converter = DependencyPath.PathNodeListConverter.class)
    @Builder.Default
    private List<PathNode> pathNodes = List.of();

    /** Pre-computed node count — {@code pathNodes.size()}. Enables fast "direct vs transitive" checks. */
    @Column(nullable = false)
    private int depth;

    // ── Value Object ─────────────────────────────────────────────────────

    /**
     * A single package reference in a dependency path.
     * Serialized as {@code {"name":"...","version":"..."}} inside the JSON array.
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PathNode {
        private String name;
        private String version;
    }

    // ── JPA AttributeConverter ────────────────────────────────────────────

    /**
     * Converts {@code List<PathNode>} ↔ JSON string for portable TEXT/VARCHAR storage.
     * Works identically on H2 (dev) and PostgreSQL (prod).
     */
    @Converter
    public static class PathNodeListConverter
            implements AttributeConverter<List<PathNode>, String> {

        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final TypeReference<List<PathNode>> TYPE_REF = new TypeReference<>() {};

        @Override
        public String convertToDatabaseColumn(List<PathNode> nodes) {
            if (nodes == null || nodes.isEmpty()) return "[]";
            try {
                return MAPPER.writeValueAsString(nodes);
            } catch (Exception e) {
                return "[]";
            }
        }

        @Override
        public List<PathNode> convertToEntityAttribute(String json) {
            if (json == null || json.isBlank()) return List.of();
            try {
                return MAPPER.readValue(json, TYPE_REF);
            } catch (Exception e) {
                return List.of();
            }
        }
    }
}
