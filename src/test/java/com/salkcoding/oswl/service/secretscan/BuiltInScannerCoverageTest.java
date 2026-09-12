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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"299,false", "300,false", "301,false", "299,true", "300,true", "301,true"})
    void findingLimitCannotClaimCompleteCoverage(int count, boolean splitFiles) throws Exception {
        String line = "AKIA1234567890ABCDEF acl = \"public-read\"\n";
        int first = splitFiles ? count / 2 : count;
        java.nio.file.Files.writeString(root.resolve("first.tf"), line.repeat(first));
        if (splitFiles) java.nio.file.Files.writeString(root.resolve("second.tf"), line.repeat(count - first));
        var secret = new SecretScanner();
        secret.loadRules();
        for (var result : java.util.List.of(secret.scan(root), new IacScanner().scan(root))) {
            assertThat(result.stream().filter(f -> f.ruleId().endsWith("-scan-incomplete")).count())
                    .isEqualTo(count >= 300 ? 1 : 0);
            assertThat(result.stream().filter(f -> !f.ruleId().endsWith("-scan-incomplete")).count())
                    .isEqualTo(Math.min(count, 300));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {999999, 1000000, 1000001})
    void oversizedApplicableInputCannotClaimCompleteCoverage(int size) throws Exception {
        byte[] content = new byte[size];
        java.util.Arrays.fill(content, (byte)' ');
        for (int i = 63; i < content.length; i += 64) content[i] = '\n';
        byte[] tail = "AKIA1234567890ABCDEF acl = \"public-read\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(tail, 0, content, content.length - tail.length, tail.length);
        java.nio.file.Files.write(root.resolve("large.tf"), content);
        var secret = new SecretScanner();
        secret.loadRules();
        for (var result : java.util.List.of(secret.scan(root), new IacScanner().scan(root))) {
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().ruleId().endsWith("-scan-incomplete")).isEqualTo(size > 1000000);
        }
    }

    @Test void excludedBinaryFileDoesNotBecomeAnOversizeFailure() throws Exception {
        java.nio.file.Files.write(root.resolve("image.png"), new byte[1000001]);
        var secret = new SecretScanner();
        secret.loadRules();
        assertThat(secret.scan(root)).isEmpty();
        assertThat(new IacScanner().scan(root)).isEmpty();
    }

    @Test void emptyReadableSourceRemainsAValidEmptyResult() {
        var secrets = new SecretScanner();
        secrets.loadRules();
        assertThat(secrets.scan(root)).isEmpty();
        assertThat(new IacScanner().scan(root)).isEmpty();
    }
}
