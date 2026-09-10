package com.salkcoding.oswl.service.ingest;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class DependencyEvidenceMigrationTest {
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
