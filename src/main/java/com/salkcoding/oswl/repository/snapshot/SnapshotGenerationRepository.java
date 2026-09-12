package com.salkcoding.oswl.repository.snapshot;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class SnapshotGenerationRepository {
    private final JdbcTemplate jdbc;

    public Long activeId() {
        return jdbc.query("SELECT generation_id FROM snapshot_active_generation WHERE id=1",
                rows -> rows.next() ? rows.getObject(1, Long.class) : null);
    }

    public void initializePointer() {
        jdbc.update("INSERT INTO snapshot_active_generation(id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM snapshot_active_generation WHERE id=1)");
    }

    public long capture(String metadata) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO snapshot_generations(published_at, source_metadata) VALUES (?, ?)", new String[]{"id"});
            statement.setTimestamp(1, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
            statement.setString(2, metadata);
            return statement;
        }, key);
        long id = java.util.Objects.requireNonNull(key.getKey()).longValue();
        jdbc.update("INSERT INTO snapshot_generation_entries(generation_id, source, entry_key, payload) "
                + "SELECT ?, source, entry_key, payload FROM airgapped_snapshot_entries", id);
        jdbc.update("UPDATE snapshot_active_generation SET generation_id=? WHERE id=1", id);
        return id;
    }

    public String metadata(long generationId) {
        return jdbc.queryForObject("SELECT source_metadata FROM snapshot_generations WHERE id=?", String.class, generationId);
    }

    public Map<String, String> payloads(long generationId, String source, Collection<String> keys) {
        if (keys.isEmpty()) return Map.of();
        return new NamedParameterJdbcTemplate(jdbc).query(
                "SELECT entry_key, payload FROM snapshot_generation_entries WHERE generation_id=:generation AND source=:source AND entry_key IN (:keys)",
                Map.of("generation", generationId, "source", source, "keys", keys), rows -> {
                    Map<String, String> result = new java.util.LinkedHashMap<>();
                    while (rows.next()) result.put(rows.getString(1), rows.getString(2));
                    return result;
                });
    }
}
