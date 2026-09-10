package com.salkcoding.oswl.service.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.*;
import com.salkcoding.oswl.dto.snapshot.CocoaPodsSpec;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.snapshot.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:cocoapods-bundle;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT", "oswl.airgapped.enabled=true"})
class CocoaPodsSnapshotTest {
    @Autowired AirgappedSnapshotService service;
    @Autowired SnapshotEntryRepository entries;
    @Autowired SnapshotMetaRepository metadata;
    @Autowired com.salkcoding.oswl.service.ingest.ScanIngestService ingest;
    @Autowired com.salkcoding.oswl.repository.project.ProjectRepository projects;
    @Autowired com.salkcoding.oswl.repository.scan.ScanResultRepository scans;
    @Autowired com.salkcoding.oswl.repository.scan.ScanComponentRepository components;
    @Autowired com.salkcoding.oswl.repository.vulnerability.CveRepository cves;
    @Autowired com.salkcoding.oswl.service.gate.GatePolicyService gate;
    private final ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void clean() { entries.deleteAllInBatch(); metadata.deleteAllInBatch(); }

    @Test void importsScopedSpecResolvesOfflineAndPreservesProvenanceOnExport() throws Exception {
        var spec = spec("FixturePod", "1.0", "MIT");
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("cocoapods-specs.jsonl", mapper.writeValueAsString(spec)+"\n"))));
        var client = new CocoaPodsSpecsClient(true, Duration.ofMillis(1), Duration.ofMillis(1), service);
        assertThat(client.resolve("FixturePod", "1.0")).contains(new CocoaPodsSpecsClient.PodSpecInfo("github.com/fixture/FixturePod", "MIT"));
        assertThat(client.resolve("Missing", "1.0")).isEmpty();
        var exported = service.exportBundle();
        entries.deleteAllInBatch(); metadata.deleteAllInBatch();
        service.importBundle(new ByteArrayInputStream(exported));
        assertThat(service.findCocoaPodsSpec("FixturePod", "1.0")).contains(spec);
        assertThat(metadata.findById("cocoapods-specs").orElseThrow().getSourceAsOf()).hasToString("2026-01-01");
    }

    @Test void podfileReachesActualOfflineEnrichmentAndMissingSpecRemainsUnanalysed() throws Exception {
        var spec = spec("EndToEndPod", "1.0", "MIT");
        String osv = "{\"ecosystem\":\"SwiftURL\",\"name\":\"github.com/fixture/EndToEndPod\",\"version\":\"1.0\",\"vulns\":[{\"cveId\":\"CVE-2026-0002\",\"summary\":\"Owned test fixture\",\"severity\":\"CRITICAL\",\"cvssScore\":9.3}]}";
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("cocoapods-specs.jsonl",mapper.writeValueAsString(spec),"osv.jsonl",osv))));
        var parsed = new com.salkcoding.oswl.service.ingest.parser.CocoaPodsLockParser().parsePodfileLockLines(List.of("PODS:","  - EndToEndPod (1.0)","  - MissingSpecPod (1.0)"),"fixture");
        var project = projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Offline pods").build());
        long scan = ingest.ingest(project.getId(),com.salkcoding.oswl.dto.scan.ScanPayload.create("1",parsed)).getId();
        long deadline = System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while (scans.findById(scan).orElseThrow().getStatus() != com.salkcoding.oswl.domain.enums.ScanStatus.COMPLETED && System.nanoTime()<deadline) Thread.sleep(100);
        assertThat(scans.findById(scan).orElseThrow().getStatus()).isEqualTo(com.salkcoding.oswl.domain.enums.ScanStatus.COMPLETED);
        var rows = components.findByScanResultId(scan);
        var found = rows.stream().filter(c->c.getLibrary().getName().equals("EndToEndPod")).findFirst().orElseThrow().getLibrary();
        assertThat(found.isVulnerabilitiesAnalyzed()).isTrue();
        assertThat(found.getLicenseName()).isEqualTo("MIT");
        assertThat(cves.findAll().stream().filter(c -> c.getLibrary().getId().equals(found.getId())).map(c -> c.getCveId())).contains("CVE-2026-0002");
        var stored = cves.findAll().stream().filter(c -> c.getLibrary().getId().equals(found.getId())).findFirst().orElseThrow();
        assertThat(stored.getSeverity()).isEqualTo(com.salkcoding.oswl.domain.enums.RiskLevel.CRITICAL);
        assertThat(stored.getCvssScore()).isEqualTo(9.3);
        var decision = gate.evaluate(project.getId(), new com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions(scan,"HIGH",null,null,false,false,false,null));
        assertThat(decision.passed()).isFalse();
        assertThat(decision.violations()).anyMatch(v -> "CVE-2026-0002".equals(v.id()));
        var missing = rows.stream().filter(c->c.getLibrary().getName().equals("MissingSpecPod")).findFirst().orElseThrow().getLibrary();
        assertThat(missing.isVulnerabilitiesAnalyzed()).isFalse();
        assertThat(missing.getVulnerabilityLookupOutcomes()).containsEntry("OSV","UNSUPPORTED");
        byte[] exported = service.exportBundle();
        service.importBundle(new ByteArrayInputStream(exported));
        var afterExport = new OsvClient(service,true).queryBatch(List.of(new OsvClient.OsvQuery("SwiftURL","github.com/fixture/EndToEndPod","1.0"))).getFirst();
        assertThat(afterExport.resolved()).isTrue();
        assertThat(afterExport.vulns()).extracting(OsvClient.OsvVuln::cveId).contains("CVE-2026-0002");
    }

    @Test void rejectsTamperingAndLegacySpecBundleWithoutChangingExistingData() throws Exception {
        var spec = spec("FixturePod", "1.0", "MIT");
        String line = mapper.writeValueAsString(spec);
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("cocoapods-specs.jsonl",line))));
        String tampered = line.replace("MIT","Apache-2.0");
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("cocoapods-specs.jsonl",tampered)))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(zip(Map.of("cocoapods-specs.jsonl",line)))))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(service.findCocoaPodsSpec("FixturePod","1.0")).contains(spec);
    }

    @Test void retainedFindingsDoNotHideUnresolvedMarkerAndCvssSurvivesImport() throws Exception {
        String key = AirgappedSnapshotService.componentKey("MAVEN", "fixture", "1");
        String findings = "{\"ecosystem\":\"MAVEN\",\"name\":\"fixture\",\"version\":\"1\",\"vulns\":[{\"cveId\":\"CVE-2026-0001\",\"severity\":\"CRITICAL\",\"cvssScore\":9.3,\"cvss3Vector\":\"CVSS:4.0/AV:N\"}]}";
        String unresolved = "{\"ecosystem\":\"MAVEN\",\"name\":\"fixture\",\"version\":\"1\"}";
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl",findings,"nvd.jsonl",findings,"unresolved.jsonl",unresolved))));
        var result = new OsvClient(service,true).queryBatch(List.of(new OsvClient.OsvQuery("Maven","fixture","1"))).getFirst();
        assertThat(result.vulns()).hasSize(1);
        assertThat(result.vulns().getFirst().cvssScore()).isEqualTo(9.3);
        assertThat(result.vulns().getFirst().effectiveSeverity()).isEqualTo(com.salkcoding.oswl.domain.enums.RiskLevel.CRITICAL);
        assertThat(result.resolved()).isFalse();
        assertThat(service.findNvdVulns(List.of(key)).get(key).getFirst().cvssScore()).isEqualTo(9.3);
        var nvd = new NvdClient(service,true,"",Duration.ofMillis(1),Duration.ofMillis(1));
        assertThat(nvd.findByComponentKeys(List.of("missing"))).isEmpty();
    }

    private CocoaPodsSpec spec(String name, String version, String license) throws Exception {
        String json = mapper.writeValueAsString(Map.of("name",name,"version",version,"license",license,"source",Map.of("git","https://github.com/fixture/"+name+".git")));
        return new CocoaPodsSpec(name,version,"https://example.org/owned-fixture",sha(json),json);
    }
    private byte[] bundle(Map<String,String> data) throws Exception {
        Map<String,Object> files = new LinkedHashMap<>();
        for(var entry:data.entrySet()) files.put(entry.getKey(),Map.of("sha256",sha(entry.getValue())));
        Map<String,String> all = new LinkedHashMap<>(data);
        all.put("meta.json",mapper.writeValueAsString(Map.of("formatVersion",2,"files",files,"sources",Map.of("cocoapods-specs",Map.of("asOf","2026-01-01","origin","owned-fixture")))));
        return zip(all);
    }
    private byte[] zip(Map<String,String> data) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            for(var entry:data.entrySet()) {zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
        return bytes.toByteArray();
    }
    private String sha(String text) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
}
