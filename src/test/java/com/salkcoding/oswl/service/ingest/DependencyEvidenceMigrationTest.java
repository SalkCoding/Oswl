package com.salkcoding.oswl.service.ingest;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class DependencyEvidenceMigrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "OSWL_VERIFY_POSTGRES_URL", matches = "jdbc:postgresql:.*")
    void postgresWideningPreservesRowsAndRollsBackTheIsolatedSchema() throws Exception {
        var properties = new java.util.Properties();
        properties.setProperty("user", System.getenv().getOrDefault("OSWL_VERIFY_POSTGRES_USER", "postgres"));
        properties.setProperty("password", System.getenv().getOrDefault("OSWL_VERIFY_POSTGRES_PASSWORD", ""));
        try (var connection = DriverManager.getConnection(System.getenv("OSWL_VERIFY_POSTGRES_URL"), properties)) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            connection.setAutoCommit(false);
            String schema = "evidence_" + java.util.UUID.randomUUID().toString().replace("-", "");
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout = '5s'");
                    statement.execute("SET LOCAL statement_timeout = '10s'");
                    statement.execute("CREATE SCHEMA " + schema);
                    statement.execute("SET LOCAL search_path TO " + schema);
                    statement.execute("CREATE TABLE scan_components(id BIGINT PRIMARY KEY, dependency_info VARCHAR(300))");
                    statement.execute("INSERT INTO scan_components VALUES(1,'Direct'),(2,NULL)");
                }
                String evidence = "PackageReference 조건=\"대상 프레임워크\" Version=\"8.0.3\"; ".repeat(30);
                try (var insert = connection.prepareStatement("INSERT INTO scan_components VALUES(3,?)")) {
                    insert.setString(1, evidence);
                    var beforeInsert = connection.setSavepoint();
                    assertThatThrownBy(insert::executeUpdate).isInstanceOf(java.sql.SQLException.class)
                            .extracting(error -> ((java.sql.SQLException) error).getSQLState()).isEqualTo("22001");
                    connection.rollback(beforeInsert);
                    try (var statement = connection.createStatement()) {
                        String migration = Files.readString(Path.of("src/main/resources/db/migration/V37__dependency_evidence_text.sql"));
                        statement.execute(migration);
                        statement.execute(migration);
                    }
                    assertThat(insert.executeUpdate()).isEqualTo(1);
                }
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT dependency_info FROM scan_components ORDER BY id")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("Direct");
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isNull();
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(evidence);
                    assertThat(result.next()).isFalse();
                }
            } finally {
                connection.rollback();
            }
            try (var check = connection.prepareStatement("SELECT count(*) FROM pg_namespace WHERE nspname = ?")) {
                check.setString(1, schema);
                try (var result = check.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    void wideningPreservesOldRowsAndAllowsLongEvidence() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:dependency-evidence;MODE=PostgreSQL", "sa", "")) {
            RunScript.execute(connection, new StringReader("CREATE TABLE scan_components(id BIGINT PRIMARY KEY, dependency_info VARCHAR(300));"
                    + "INSERT INTO scan_components VALUES(1,'Direct'),(2,NULL);"));
            String evidence = "PackageReference Version=\"8.0.3\" Condition=\"'$(TargetFramework)' == 'net8.0'\"; ".repeat(20);
            try (var insert = connection.prepareStatement("INSERT INTO scan_components VALUES(3,?)")) {
                insert.setString(1, evidence);
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(java.sql.SQLException.class);
                for (int repeat = 0; repeat < 2; repeat++) {
                    try (var reader = Files.newBufferedReader(Path.of("src/main/resources/db/migration/V37__dependency_evidence_text.sql"))) {
                        RunScript.execute(connection, reader);
                    }
                }
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT dependency_info FROM scan_components ORDER BY id")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("Direct");
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isNull();
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(evidence);
                assertThat(result.next()).isFalse();
            }
        }
    }
}
