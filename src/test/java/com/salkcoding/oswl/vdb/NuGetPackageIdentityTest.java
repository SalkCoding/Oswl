package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NuGetPackageIdentityTest {
    @ParameterizedTest
    @ValueSource(strings = {"\u019b", "\u0264", "\u1c89", "\u1c8a", "\ua7cb", "\ua7cc", "\ua7cd", "\ua7da", "\ua7db", "\ua7dc", "\ud801\udc00"})
    void unsupportedRuntimeCasingDoesNotProduceAnIdentity(String name) {
        assertThatThrownBy(() -> AdvisoryPackageNames.canonical("NuGet", name))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"K,K,false", "I,ı,false", "i,İ,false", "İ,i̇,false",
            "S,ſ,false", "ß,ẞ,false", "Σ,ς,true", "σ,ς,true", "É,é,true", "System.Text.Json,system.text.json,true"})
    void nativeOrdinalNameIdentityControlsAdvisoryAndBulkMatching(String name, String returnedName, boolean same) throws Exception {
        assertThat(AdvisoryPackageNames.canonical("NuGet", name)
                .equals(AdvisoryPackageNames.canonical("NuGet", returnedName))).isEqualTo(same);
        var mapper = new ObjectMapper();
        var advisory = mapper.createObjectNode();
        advisory.put("id", "OSV-fixture");
        advisory.put("modified", "2024-09-01T00:00:00Z");
        var affected = advisory.putArray("affected").addObject();
        affected.putObject("package").put("ecosystem", "NuGet").put("name", returnedName);
        var events = affected.putArray("ranges").addObject().put("type", "ECOSYSTEM").putArray("events");
        events.addObject().put("introduced", "0");
        events.addObject().put("fixed", "2.0.0");
        assertThat(OsvRangeEvaluator.evaluateAdvisory(advisory, "NuGet", name, "1.0.0"))
                .isEqualTo(same ? OsvRangeEvaluator.Result.AFFECTED : OsvRangeEvaluator.Result.UNKNOWN);
        assertThat(OsvFixVersionSelector.select(advisory, "NuGet", name, "1.0.0").version())
                .isEqualTo(same ? "2.0.0" : null);
        var found = new java.util.LinkedHashMap<String, java.util.List<com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln>>();
        var unresolved = new java.util.LinkedHashSet<String>();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(new OsvBulkSource(mapper), "processVulnEntry",
                mapper.writeValueAsBytes(advisory), "NUGET", java.util.Map.of(name, java.util.Set.of("1.0.0")), found, unresolved);
        assertThat(found.isEmpty()).isEqualTo(!same);
        assertThat(unresolved).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Contoso/Library", "Contoso Library", " Contoso", "Contoso ",
            "Contoso..Library", "Contoso--Library", "Contoso.-Library", ".Contoso", "Contoso-", "Contoso\n"})
    void malformedIdentityCannotConfirmAnAffectedVersionOrFix(String name) throws Exception {
        var mapper = new ObjectMapper();
        var advisory = mapper.createObjectNode();
        advisory.put("id", "OSV-fixture");
        advisory.put("modified", "2024-09-01T00:00:00Z");
        var affected = advisory.putArray("affected").addObject();
        affected.putObject("package").put("ecosystem", "NuGet").put("name", name);
        var events = affected.putArray("ranges").addObject().put("type", "ECOSYSTEM").putArray("events");
        events.addObject().put("introduced", "0");
        events.addObject().put("fixed", "2.0.0");
        assertThat(OsvRangeEvaluator.evaluateAdvisory(advisory, "NuGet", name, "1.0.0"))
                .isEqualTo(OsvRangeEvaluator.Result.UNKNOWN);
        assertThat(OsvFixVersionSelector.select(advisory, "NuGet", name, "1.0.0").version()).isNull();
        assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                new OsvBulkSource(mapper), "processVulnEntry", mapper.writeValueAsBytes(advisory), "NUGET",
                java.util.Map.of(name, java.util.Set.of("1.0.0")), new java.util.LinkedHashMap<>(),
                new java.util.LinkedHashSet<>())).isInstanceOf(IllegalArgumentException.class);

        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var client = new com.salkcoding.oswl.client.GitHubAdvisoryClient(null, false, "fixture",
                "https://api.github.com", java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        org.springframework.test.util.ReflectionTestUtils.setField(client, "restClient", builder.build());
        var result = new com.salkcoding.oswl.service.vulnerability.sources.GitHubAdvisorySource(client)
                .lookup("NuGet", name, "1.0.0", java.util.List.of());
        assertThat(result.lookupFailed()).isTrue();
        assertThat(result.findings()).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Contoso.Library", "Contoso-Library", "_Contoso", "Contoso_Library", "Contoso._Library", "1"})
    void supportedIdentifiersKeepTheirSeparators(String name) {
        assertThat(AdvisoryPackageNames.canonical("NuGet", name))
                .isEqualTo(name.toLowerCase(java.util.Locale.ROOT));
    }
}
