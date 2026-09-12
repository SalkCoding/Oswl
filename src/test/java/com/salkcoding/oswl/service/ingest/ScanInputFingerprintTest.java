package com.salkcoding.oswl.service.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ScanInputFingerprintTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INPUT = """
            {"version":"main","submitterEmail":"user@example.test","components":[
              {"name":"pkg","version":"1","ecosystem":"NPM","scope":"runtime",
               "dependencyInfo":"Direct","licenses":["MIT"],
               "dependencyPaths":[[{"name":"root","version":"1"}]]}]}
            """;

    @Test void ignoresTransportFormattingRetryKeyAndCredentials() throws Exception {
        var original = JSON.readValue(INPUT, ScanPayload.class);
        var retry = JSON.readValue(JSON.writeValueAsString(JSON.readTree(INPUT)), ScanPayload.class);
        retry.setSubmitterPassword("rotated-password");
        retry.setRawJson("sensitive transport data");
        retry.setIdempotencyKey("another-key");
        assertThat(ScanInputFingerprint.digest(retry)).isEqualTo(ScanInputFingerprint.digest(original));
        assertThat(ScanInputFingerprint.digest(original)).matches("[0-9a-f]{64}");
    }

    @ParameterizedTest @ValueSource(strings={"name","version","ecosystem","scope","dependencyInfo","licenses","dependencyPaths"})
    void rejectsChangesToEveryComponentInput(String field) throws Exception {
        var changed = JSON.readTree(INPUT);
        var component = (com.fasterxml.jackson.databind.node.ObjectNode) changed.path("components").get(0);
        component.putNull(field);
        assertThat(ScanInputFingerprint.digest(JSON.treeToValue(changed,ScanPayload.class)))
                .isNotEqualTo(ScanInputFingerprint.digest(JSON.readValue(INPUT,ScanPayload.class)));
    }

    @ParameterizedTest @ValueSource(strings={"version","submitterEmail","components"})
    void rejectsChangesToTopLevelInput(String field) throws Exception {
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(INPUT);
        changed.putNull(field);
        assertThat(ScanInputFingerprint.digest(JSON.treeToValue(changed,ScanPayload.class)))
                .isNotEqualTo(ScanInputFingerprint.digest(JSON.readValue(INPUT,ScanPayload.class)));
    }
}
