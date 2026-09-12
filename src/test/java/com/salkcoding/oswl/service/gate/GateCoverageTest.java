package com.salkcoding.oswl.service.gate;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.*;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.service.policy.PolicyService;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GateCoverageTest {
    @Mock ProjectRepository projects;
    @Mock ScanResultRepository scans;
    @Mock ScanComponentRepository components;
    @Mock ScanFindingRepository findings;
    @Mock LibraryRepository libraries;
    @Mock PolicyService policies;
    @Mock org.springframework.context.MessageSource messageSource;
    @Mock OswlMetrics metrics;
    @InjectMocks GatePolicyService service;
    Project project;
    ScanResult scan;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "defaultFailOnSeverity", "HIGH");
        // Individual rule tests opt into EPSS; policy-floor tests set their enforced baseline explicitly.
        ReflectionTestUtils.setField(service, "defaultFailOnEpss", -1.0);
        project = Project.builder().id(1L).name("Coverage").build();
        scan = ScanResult.builder().id(2L).project(project).scannedAt(java.time.LocalDateTime.of(2026,9,12,12,0))
                .status(ScanStatus.COMPLETED).build();
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        when(scans.findRecentCompleted(1L, 1)).thenReturn(List.of(scan));
        when(policies.resolveGateOptions(1L)).thenReturn(GatePolicyService.GateOptions.defaults());
    }

    private Library evidenceLibrary() {
        var library = Library.builder().id(3L).name("fixture").version("1").ecosystem("NPM").build();
        library.recordLookupOutcomes(Map.of("OSV","RESOLVED"));
        library.markFetched();
        return library;
    }

    private void preserve(ScanResult target, Library library) throws Exception {
        var value = new com.salkcoding.oswl.dto.scan.ScanAssessment(1,"fixture",List.of(
                com.salkcoding.oswl.service.scan.ScanAssessmentService.fromLibrary(library)));
        ReflectionTestUtils.setField(target,"assessmentJson",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"format-float", "format-text", "malicious-number", "verified-text",
            "version-number", "id-float", "severity-number", "epss-text"})
    void gateCannotCoercePreservedEvidenceTypes(String state) throws Exception {
        var library = evidenceLibrary();
        preserve(scan, library);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(scan.getAssessmentJson());
        var entry = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("libraries").get(0);
        switch (state) {
            case "format-float" -> root.put("formatVersion", 1.9);
            case "format-text" -> root.put("formatVersion", "1");
            case "malicious-number" -> entry.put("malicious", 0);
            case "verified-text" -> entry.put("lookupTimesVerified", "true");
            case "version-number" -> entry.put("version", 1);
            case "id-float" -> entry.put("libraryId", 3.9);
            case "severity-number" -> ((com.fasterxml.jackson.databind.node.ArrayNode) entry.get("findings"))
                    .addObject().put("cveId", "CVE-2026-123455").put("severity", 4);
            case "epss-text" -> ((com.fasterxml.jackson.databind.node.ArrayNode) entry.get("findings"))
                    .addObject().put("cveId", "CVE-2026-123455").put("epssScore", "0");
        }
        String json = mapper.writeValueAsString(root);
        ReflectionTestUtils.setField(scan, "assessmentJson", json);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.evaluate(1L, GatePolicyService.GateOptions.defaults()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(scan.getAssessmentJson()).isEqualTo(json);
        assertThat(library.getVersion()).isEqualTo("1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"outcome", "findings", "format", "trailing"})
    void ambiguousPreservedJsonCannotBecomeAPassingGate(String state) throws Exception {
        var library = evidenceLibrary();
        preserve(scan, library);
        String json = scan.getAssessmentJson();
        json = switch (state) {
            case "outcome" -> json.replace("\"OSV\":\"RESOLVED\"", "\"OSV\":\"UNAVAILABLE\",\"OSV\":\"RESOLVED\"");
            case "findings" -> json.replace("\"findings\":[]", "\"findings\":[{\"cveId\":\"CVE-2026-123450\",\"severity\":\"CRITICAL\"}],\"findings\":[]");
            case "format" -> json.replace("\"formatVersion\":1", "\"formatVersion\":999,\"formatVersion\":1");
            default -> json + " {}";
        };
        ReflectionTestUtils.setField(scan, "assessmentJson", json);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.evaluate(1L, GatePolicyService.GateOptions.defaults()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(scan.getAssessmentJson()).isEqualTo(json);
        assertThat(library.isVulnerabilitiesAnalyzed()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"malicious,false", "malicious,true", "findings,false", "findings,true", "identical,false", "identical,true"})
    void duplicatePreservedLibraryIdsCannotSelectOneAssessment(String state, boolean reversed) throws Exception {
        var library = evidenceLibrary();
        preserve(scan, library);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.readTree(scan.getAssessmentJson());
        var entries = (com.fasterxml.jackson.databind.node.ArrayNode) root.get("libraries");
        var original = entries.get(0).deepCopy();
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        if (state.equals("malicious")) changed.put("malicious", true);
        if (state.equals("findings")) ((com.fasterxml.jackson.databind.node.ArrayNode) changed.get("findings"))
                .addObject().put("cveId", "CVE-2026-123450").put("severity", "CRITICAL");
        entries.removeAll();
        entries.add(reversed ? original : changed);
        entries.add(reversed ? changed : original);
        String json = mapper.writeValueAsString(root);
        ReflectionTestUtils.setField(scan, "assessmentJson", json);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.evaluate(1L, GatePolicyService.GateOptions.defaults()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(scan.getAssessmentJson()).isEqualTo(json);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> malformedOutcomes() {
        return java.util.stream.Stream.of(false,true).flatMap(preserved -> java.util.stream.Stream.of(
                "unknown-status","empty-status","lowercase-status","unknown-source","padded-source","snapshot-resolved","valid","deps-only")
                .map(state -> org.junit.jupiter.params.provider.Arguments.of(preserved,state)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("malformedOutcomes")
    void unrecognizedLookupEvidenceCannotPassAlongsideResolvedSource(boolean preserved, String state) throws Exception {
        var library = evidenceLibrary();
        Map<String,String> outcomes = switch (state) {
            case "unknown-status" -> Map.of("OSV","RESOLVED","NVD","ERROR");
            case "empty-status" -> Map.of("OSV","RESOLVED","NVD","");
            case "lowercase-status" -> Map.of("OSV","RESOLVED","NVD","resolved");
            case "unknown-source" -> Map.of("OSV","RESOLVED","FUTURE_PROVIDER","RESOLVED");
            case "padded-source" -> Map.of("OSV ","RESOLVED");
            case "snapshot-resolved" -> Map.of("OSV","RESOLVED","SNAPSHOT","RESOLVED");
            case "deps-only" -> Map.of("DEPS_DEV","RESOLVED");
            default -> Map.of("OSV","RESOLVED","GITHUB_ADVISORY","NOT_CONFIGURED","NVD","UNSUPPORTED");
        };
        library.recordLookupOutcomes(outcomes); library.markFetched();
        if (preserved) preserve(scan,library);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isEqualTo(Set.of("valid","deps-only").contains(state));
        assertThat(result.coverage().complete()).isEqualTo(Set.of("valid","deps-only").contains(state));
        assertThat(library.getVulnerabilityLookupOutcomes()).isEqualTo(outcomes);
        if (preserved) assertThat(com.salkcoding.oswl.service.scan.ScanAssessmentService.read(scan.getAssessmentJson())
                .libraries().getFirst().lookupOutcomes()).isEqualTo(outcomes);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false,true})
    void legacyFetchDateWithoutSourceOutcomesCannotProveCoverage(boolean hasLookupDate) {
        var library = evidenceLibrary();
        ReflectionTestUtils.setField(library,"vulnerabilityLookupOutcomes",null);
        if (!hasLookupDate) ReflectionTestUtils.setField(library,"vulnerabilityLookupAt",null);
        var fetched = library.getFetchedAt();
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().unanalysedComponents()).isEqualTo(1);
        assertThat(library.getFetchedAt()).isEqualTo(fetched);
        assertThat(library.getVulnerabilityLookupOutcomes()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,fetched-future", "true,fetched-future", "false,lookup-future", "true,lookup-future", "false,lookup-missing", "true,lookup-missing", "false,valid", "true,valid"})
    void completionRequiresUsableLookupTimes(boolean preserved, String state) throws Exception {
        var library = evidenceLibrary();
        if (state.equals("fetched-future")) ReflectionTestUtils.setField(library,"fetchedAt",java.time.LocalDateTime.of(2999,1,1,0,0));
        if (state.equals("lookup-future")) ReflectionTestUtils.setField(library,"vulnerabilityLookupAt",java.time.LocalDateTime.of(2999,1,1,0,0));
        if (state.equals("lookup-missing")) ReflectionTestUtils.setField(library,"vulnerabilityLookupAt",null);
        if (preserved) {
            preserve(scan,library);
            library.recordLookupOutcomes(Map.of("OSV","RESOLVED"));
            library.markFetched();
        }
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isEqualTo(state.equals("valid"));
        assertThat(result.coverage().complete()).isEqualTo(state.equals("valid"));
    }

    @Test void oldPreservedDatesCannotAcquireVerificationFromCurrentCache() throws Exception {
        var library = evidenceLibrary();
        preserve(scan,library);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var tree = json.readTree(scan.getAssessmentJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("libraries").get(0)).remove("lookupTimesVerified");
        ReflectionTestUtils.setField(scan,"assessmentJson",json.writeValueAsString(tree));
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        assertThat(service.evaluate(1L,GatePolicyService.GateOptions.defaults()).coverage().complete()).isFalse();
    }

    private void findings(Library library) {
        library.getCves().add(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder()
                .cveId("CVE-2026-123450").severity(com.salkcoding.oswl.domain.enums.RiskLevel.HIGH).build());
    }

    @Test void preservedFindingsCannotDisappearWhenSharedLibraryIsRefreshed() throws Exception {
        var library = evidenceLibrary();
        findings(library);
        library.updateLicense("restricted",List.of("restricted"),com.salkcoding.oswl.domain.enums.LicenseStatus.RESTRICTED);
        preserve(scan,library);
        library.getCves().clear();
        library.updateLicense("MIT",List.of("MIT"),com.salkcoding.oswl.domain.enums.LicenseStatus.PERMITTED);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,new GatePolicyService.GateOptions(null,null,null,null,true,false,false,false));
        assertThat(result.coverage().complete()).isTrue();
        assertThat(result.violations()).extracting(v -> v.type()).containsExactlyInAnyOrder("CVE","LICENSE");
    }

    @Test void currentFindingsCannotBeAddedToAPreservedCleanScanOrItsBaseline() throws Exception {
        var library = evidenceLibrary();
        var baseline = ScanResult.builder().id(1L).project(project).status(ScanStatus.COMPLETED).build();
        preserve(baseline,library);
        preserve(scan,library);
        findings(library);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        assertThat(service.evaluate(1L,GatePolicyService.GateOptions.defaults()).passed()).isTrue();
        preserve(scan,library);
        when(scans.findPreviousCompleted(1L,scan.getScannedAt(),scan.getId())).thenReturn(Optional.of(baseline));
        var result = service.evaluate(1L,new GatePolicyService.GateOptions(null,null,null,null,false,true,false,false));
        assertThat(result.passed()).isFalse();
        assertThat(result.newVulnerabilityCount()).isEqualTo(1);
    }

    @Test void unavailableStoredLookupCannotBeRenewedBySharedSuccessfulLookup() throws Exception {
        var library = evidenceLibrary();
        library.recordLookupOutcomes(Map.of("OSV","UNAVAILABLE"));
        preserve(scan,library);
        library.recordLookupOutcomes(Map.of("OSV","RESOLVED"));
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).ignored(true).build()));
        assertThat(service.evaluate(1L,GatePolicyService.GateOptions.defaults()).coverage().complete()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void malwareGateUsesPreservedFlagInBothDirections(boolean malicious) throws Exception {
        var library = evidenceLibrary();
        ReflectionTestUtils.setField(library,"malicious",malicious);
        preserve(scan,library);
        ReflectionTestUtils.setField(library,"malicious",!malicious);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isEqualTo(!malicious);
        if (malicious) assertThat(result.violations()).extracting(v -> v.type()).containsExactly("MALICIOUS");
    }

    @Test void missingStoredMalwareEvidenceAndMissingInventoryCannotPass() throws Exception {
        var library = evidenceLibrary();
        preserve(scan,library);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var tree = json.readTree(scan.getAssessmentJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("libraries").get(0)).remove("malicious");
        ReflectionTestUtils.setField(scan,"assessmentJson",json.writeValueAsString(tree));
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        assertThat(service.evaluate(1L,GatePolicyService.GateOptions.defaults()).passed()).isFalse();
        preserve(scan,library);
        when(components.findByScanResultId(2L)).thenReturn(List.of());
        assertThat(service.evaluate(1L,GatePolicyService.GateOptions.defaults()).coverage().detailsAvailable()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void unscoredFindingStillHonorsIndependentKevAndEpssRules(boolean preserved, boolean kev) throws Exception {
        var library = evidenceLibrary();
        library.getCves().add(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder()
                .cveId("CVE-2026-123450").kevListed(kev ? true : null).epssScore(kev ? null : 0.9).build());
        if (preserved) preserve(scan,library);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,new GatePolicyService.GateOptions(null,"HIGH",kev,kev ? -1.0 : 0.5,false,false,false,false));
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.type()).isEqualTo("CVE");
            assertThat(v.severity()).isEqualTo("UNSCORED");
            assertThat(v.reason()).contains(kev ? "CISA KEV listed" : "EPSS");
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0.5,false", "0.499,true"})
    void unscoredEpssUsesTheConfiguredBoundaryWithoutInventingSeverity(double score, boolean passed) throws Exception {
        var library = evidenceLibrary();
        library.getCves().add(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder()
                .cveId("CVE-2026-123450").epssScore(score).build());
        preserve(scan,library);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,new GatePolicyService.GateOptions(null,"HIGH",false,0.5,false,false,false,false));
        assertThat(result.passed()).isEqualTo(passed);
        assertThat(result.evaluatedCount()).isEqualTo(1);
        if (!passed) assertThat(result.violations()).singleElement().satisfies(v -> {
            assertThat(v.severity()).isEqualTo("UNSCORED");
            assertThat(v.reason()).doesNotContain("severity");
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"NaN","Infinity","-Infinity","1.0001"})
    void invalidEpssThresholdCannotProduceAPassingGate(String value) {
        org.mockito.Mockito.reset(scans);
        org.mockito.Mockito.lenient().when(scans.findRecentCompleted(1L,1)).thenReturn(List.of(scan));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.evaluate(1L,
                new GatePolicyService.GateOptions(null,null,false,Double.valueOf(value),false,false,false,false)))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class);
        org.mockito.Mockito.verifyNoInteractions(scans);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void invalidEffectivePolicyOrInstanceThresholdIsRejected(boolean policy) {
        org.mockito.Mockito.reset(scans);
        org.mockito.Mockito.lenient().when(scans.findRecentCompleted(1L,1)).thenReturn(List.of(scan));
        if (policy) when(policies.resolveGateOptions(1L)).thenReturn(
                new GatePolicyService.GateOptions(null,null,null,Double.NaN,null,null,null,null));
        else ReflectionTestUtils.setField(service,"defaultFailOnEpss",Double.NaN);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.evaluate(1L,GatePolicyService.GateOptions.defaults()))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class);
        org.mockito.Mockito.verifyNoInteractions(scans);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles={-1.0,0.0,1.0})
    void validEpssBoundariesAndExplicitDisableRemainSupported(double value) {
        var result = service.evaluate(1L,new GatePolicyService.GateOptions(null,null,null,value,null,null,null,null));
        assertThat(result.passed()).isTrue();
        if (value < 0) assertThat(result.thresholds().failOnEpss()).isNull();
        else assertThat(result.thresholds().failOnEpss()).isEqualTo(value);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"unknown",""})
    void invalidSeverityIsNotSilentlyReplaced(String severity) {
        org.mockito.Mockito.reset(scans);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.evaluate(1L,
                new GatePolicyService.GateOptions(null,severity,null,null,null,null,null,null)))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class);
        org.mockito.Mockito.verifyNoInteractions(scans);
    }

    @Test void requestCannotDisableInstanceDefaultsWhenThereIsNoPolicyRow() {
        ReflectionTestUtils.setField(service,"defaultFailOnKev",true);
        ReflectionTestUtils.setField(service,"defaultFailOnEpss",0.5);
        var result = service.evaluate(1L,new GatePolicyService.GateOptions(null,"CRITICAL",false,-1.0,false,true,true,false));
        assertThat(result.thresholds().failOnSeverity()).isEqualTo("HIGH");
        assertThat(result.thresholds().failOnKev()).isTrue();
        assertThat(result.thresholds().failOnEpss()).isEqualTo(0.5);
        assertThat(result.onlyNew()).isFalse();
        assertThat(result.onlyReachable()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"CRITICAL","NONE"})
    void requestCannotWeakenEffectivePolicy(String severity) {
        when(policies.resolveGateOptions(1L)).thenReturn(new GatePolicyService.GateOptions(null,"HIGH",true,0.3,true,false,false,true));
        var library = evidenceLibrary();
        findings(library);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,new GatePolicyService.GateOptions(null,severity,false,-1.0,false,true,true,false));
        assertThat(result.passed()).isFalse();
        assertThat(result.violations()).extracting(v -> v.type()).contains("CVE");
        assertThat(result.thresholds().failOnSeverity()).isEqualTo("HIGH");
        assertThat(result.thresholds().failOnKev()).isTrue();
        assertThat(result.thresholds().failOnEpss()).isEqualTo(0.3);
        assertThat(result.thresholds().failOnLicenseViolation()).isTrue();
        assertThat(result.thresholds().failOnSecrets()).isTrue();
        assertThat(result.onlyNew()).isFalse();
        assertThat(result.onlyReachable()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"NONE,true","HIGH,false"})
    void disabledPolicySeverityCanBeEnabledByRequest(String severity, boolean passed) {
        when(policies.resolveGateOptions(1L)).thenReturn(new GatePolicyService.GateOptions(null,"NONE",false,-1.0,false,false,false,false));
        var library = evidenceLibrary();
        findings(library);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,new GatePolicyService.GateOptions(null,severity,null,null,null,null,null,null));
        assertThat(result.passed()).isEqualTo(passed);
        assertThat(result.thresholds().failOnSeverity()).isEqualTo(severity);
    }

    @Test void requestCanStrengthenEffectivePolicy() {
        when(policies.resolveGateOptions(1L)).thenReturn(new GatePolicyService.GateOptions(null,"HIGH",false,0.7,false,true,true,false));
        var result = service.evaluate(1L,new GatePolicyService.GateOptions(null,"LOW",true,0.2,true,false,false,true));
        assertThat(result.thresholds().failOnSeverity()).isEqualTo("LOW");
        assertThat(result.thresholds().failOnKev()).isTrue();
        assertThat(result.thresholds().failOnEpss()).isEqualTo(0.2);
        assertThat(result.thresholds().failOnLicenseViolation()).isTrue();
        assertThat(result.thresholds().failOnSecrets()).isTrue();
        assertThat(result.onlyNew()).isFalse();
        assertThat(result.onlyReachable()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.salkcoding.oswl.domain.enums.MatchConfidence.class)
    void cpeConfidenceAloneCannotBecomeConfirmedGateEvidence(com.salkcoding.oswl.domain.enums.MatchConfidence confidence) throws Exception {
        var library = evidenceLibrary();
        library.getCves().add(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder()
                .cveId("CVE-2026-123450").severity(com.salkcoding.oswl.domain.enums.RiskLevel.HIGH)
                .sources(Set.of(com.salkcoding.oswl.domain.enums.CveSource.NVD)).matchConfidence(confidence).build());
        preserve(scan,library);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().complete()).isFalse();
        assertThat(result.violations()).extracting(v -> v.type()).contains("MATCH_REVIEW").doesNotContain("CVE");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"OSV,false","OSV,true","DEPS_DEV,false","DEPS_DEV,true","GITHUB_ADVISORY,false","GITHUB_ADVISORY,true"})
    void packageSourceEvidenceIsNotDiscardedBecauseNvdAlsoContributed(
            com.salkcoding.oswl.domain.enums.CveSource source, boolean preserved) throws Exception {
        var library = evidenceLibrary();
        library.getCves().add(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder()
                .cveId("CVE-2026-123450").severity(com.salkcoding.oswl.domain.enums.RiskLevel.HIGH)
                .sources(Set.of(source,com.salkcoding.oswl.domain.enums.CveSource.NVD))
                .matchConfidence(com.salkcoding.oswl.domain.enums.MatchConfidence.LOW).build());
        if (preserved) preserve(scan,library);
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,GatePolicyService.GateOptions.defaults());
        assertThat(result.coverage().complete()).isTrue();
        assertThat(result.violations()).extracting(v -> v.type()).containsExactly("CVE");
    }

    @Test void candidateReviewCannotBeHiddenByIgnoringAComponent() {
        var library = evidenceLibrary();
        library.getCves().add(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder()
                .cveId("CVE-2026-123450").sources(Set.of(com.salkcoding.oswl.domain.enums.CveSource.CPE)).build());
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).ignored(true).build()));
        var result = service.evaluate(1L,GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().complete()).isFalse();
        assertThat(result.violations()).extracting(v -> v.type()).contains("MATCH_REVIEW").doesNotContain("CVE");
    }

    @Test void baselineCandidateDoesNotSuppressLaterPackageConfirmedFinding() throws Exception {
        ReflectionTestUtils.setField(service,"defaultOnlyNew",true);
        var library = evidenceLibrary();
        library.getCves().add(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder()
                .cveId("CVE-2026-123450").severity(com.salkcoding.oswl.domain.enums.RiskLevel.HIGH)
                .sources(Set.of(com.salkcoding.oswl.domain.enums.CveSource.NVD)).build());
        var baseline = ScanResult.builder().id(1L).project(project).status(ScanStatus.COMPLETED).build();
        preserve(baseline,library);
        library.getCves().clear();
        library.getCves().add(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder()
                .cveId("CVE-2026-123450").severity(com.salkcoding.oswl.domain.enums.RiskLevel.HIGH)
                .sources(Set.of(com.salkcoding.oswl.domain.enums.CveSource.OSV)).build());
        preserve(scan,library);
        when(scans.findPreviousCompleted(1L,scan.getScannedAt(),scan.getId())).thenReturn(Optional.of(baseline));
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).build()));
        var result = service.evaluate(1L,GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isFalse();
        assertThat(result.newVulnerabilityCount()).isEqualTo(1);
        assertThat(result.violations()).extracting(v -> v.type()).containsExactly("CVE");
    }

    @Test void missingLookupCannotPassEvenWhenIgnoredOrFiltered() {
        Library library = Library.builder().id(3L).name("unsupported").version("1").build();
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder()
                .library(library).ignored(true).scanResult(scan).build()));
        var result = service.evaluate(1L, new GatePolicyService.GateOptions(null, null, null, null, null, true, true, null));
        assertThat(result.passed()).isFalse();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.coverage().unanalysedComponents()).isEqualTo(1);
        assertThat(result.violations()).extracting(v -> v.type()).containsExactly("COVERAGE");
        assertThat(result.commentMarkdown()).contains("incomplete analysis coverage");
    }

    @Test void completedLookupWithNoFindingsCanPass() {
        Library library = evidenceLibrary();
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).scanResult(scan).build()));
        var result = service.evaluate(1L, GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isTrue();
        assertThat(result.coverage().complete()).isTrue();
    }

    @Test void failedRefreshKeepsPriorEvidenceButCannotPassUntilSuccessfulRetry() {
        Library library = Library.builder().id(3L).name("cached").version("1").build(); library.markFetched();
        var prior = library.getFetchedAt();
        when(components.findByScanResultId(2L)).thenReturn(List.of(ScanComponent.builder().library(library).scanResult(scan).build()));
        library.recordLookupOutcomes(Map.of("OSV", "UNAVAILABLE"));
        assertThat(service.evaluate(1L, GatePolicyService.GateOptions.defaults()).passed()).isFalse();
        assertThat(library.getFetchedAt()).isEqualTo(prior);
        library.recordLookupOutcomes(Map.of("OSV", "RESOLVED")); library.markFetched();
        assertThat(service.evaluate(1L, GatePolicyService.GateOptions.defaults()).passed()).isTrue();
    }

    @Test void archivedDetailCannotPassAsAnEmptyScan() {
        scan.archive(12, new int[5], new int[4]);
        var result = service.evaluate(1L, GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().detailsAvailable()).isFalse();
    }

    @Test void incompleteScannerCannotPassEvenWithSecretPolicyDisabled() {
        when(findings.hasIncompleteScanner(2L, 1L)).thenReturn(true);
        var result = service.evaluate(1L, new GatePolicyService.GateOptions(null,null,null,null,null,true,true,false));
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().complete()).isFalse();
        assertThat(result.violations()).extracting(v -> v.type()).containsExactly("COVERAGE");
    }

    @Test void runningScanCannotPassBeforeEnrichment() {
        scan.startScanning();
        var result = service.evaluate(1L, GatePolicyService.GateOptions.defaults());
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().scanCompleted()).isFalse();
    }
}
