package com.salkcoding.oswl.service.secretscan;

import com.salkcoding.oswl.service.iacscan.IacScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class BuiltInScannerCoverageTest {
    @TempDir Path root;

    @Test void missingSourceCannotBecomeAnEmptyCompletedSecretScan() {
        var scanner = new SecretScanner();
        scanner.loadRules();
        assertThat(scanner.scan(root.resolve("missing"))).anyMatch(f -> f.ruleId().equals("secret-scan-incomplete"));
    }

    @Test void missingSourceCannotBecomeAnEmptyCompletedIacScan() {
        assertThat(new IacScanner().scan(root.resolve("missing"))).anyMatch(f -> f.ruleId().equals("iac-scan-incomplete"));
    }

    @Test void unavailableSecretRulesCannotBecomeAnEmptyCompletedScan() {
        assertThat(new SecretScanner().scan(root)).anyMatch(f -> f.ruleId().equals("secret-scan-incomplete"));
    }

    @Test void unreadableIacContentRetainsOtherFindingsAndMarksCoverage() throws Exception {
        java.nio.file.Files.write(root.resolve("bad.tf"), new byte[]{(byte)0xc3, (byte)0x28});
        java.nio.file.Files.writeString(root.resolve("good.tf"), "acl = \"public-read\"");
        var findings = new IacScanner().scan(root);
        assertThat(findings).anyMatch(f -> f.ruleId().equals("iac-scan-incomplete"));
        assertThat(findings).anyMatch(f -> f.ruleId().equals("tf-public-s3-acl"));
    }

    @Test void emptyReadableSourceRemainsAValidEmptyResult() {
        var secrets = new SecretScanner();
        secrets.loadRules();
        assertThat(secrets.scan(root)).isEmpty();
        assertThat(new IacScanner().scan(root)).isEmpty();
    }
}
