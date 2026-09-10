package com.salkcoding.oswl.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.sql.SQLException;

@Repository
@RequiredArgsConstructor
public class DatabaseMutationLockRepository {
    public static final long UNKNOWN_LIBRARY_VERSION = 1;
    public static final long WEB_PUSH = 2;
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long id) {
        if (id != UNKNOWN_LIBRARY_VERSION && id != WEB_PUSH) throw new IllegalArgumentException("Unknown mutation lock");
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            boolean postgres = "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName());
            var point = postgres ? null : connection.setSavepoint();
            try (var init = connection.prepareStatement("INSERT INTO database_mutation_locks(id) VALUES (?)" + (postgres ? " ON CONFLICT DO NOTHING" : ""))) {
                init.setLong(1,id);
                try { init.executeUpdate(); }
                catch (SQLException e) {
                    if (point == null || !"23505".equals(e.getSQLState())) throw e;
                    connection.rollback(point);
                }
            } finally { if (point != null) connection.releaseSavepoint(point); }
            try (var lock = connection.prepareStatement("SELECT id FROM database_mutation_locks WHERE id = ? FOR UPDATE")) {
                lock.setLong(1,id);
                try (var row = lock.executeQuery()) { if (!row.next()) throw new SQLException("Missing mutation lock"); }
            }
            return null;
        });
    }
}
