package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.LicenseConflictDto;
import com.salkcoding.oswl.dto.LicenseContextDto;
import com.salkcoding.oswl.dto.LicenseObligationGroupDto;
import com.salkcoding.oswl.dto.LicenseReviewItemDto;
import com.salkcoding.oswl.dto.LicenseRowDto;
import com.salkcoding.oswl.dto.VersionSummaryDto;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LicenseService {

    private final ProjectRepository    projectRepository;
    private final ScanResultRepository scanResultRepository;
    private final LibraryRepository    libraryRepository;

    // ── Page rendering ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Long scanId, LicenseContextDto context, Model model) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", project.getName());
        model.addAttribute("context", context);

        List<ScanResult> allScans = scanResultRepository.findCompletedByProjectId(projectId);

        ScanResult scan;
        if (scanId != null) {
            scan = allScans.stream()
                    .filter(s -> s.getId().equals(scanId))
                    .findFirst()
                    .orElse(allScans.isEmpty() ? null : allScans.get(0));
        } else {
            scan = allScans.isEmpty() ? null : allScans.get(0);
        }

        Long activeScanId = scan != null ? scan.getId() : null;

        List<VersionSummaryDto> scanVersions = allScans.stream()
                .map(s -> VersionSummaryDto.builder()
                        .scanId(s.getId())
                        .version(s.getVersion() != null ? s.getVersion()
                                : s.getScannedAt().toLocalDate().toString().replace("-", "."))
                        .scannedAt(s.getScannedAt() != null
                                ? s.getScannedAt().toLocalDate().toString().replace("-", ".") : "-")
                        .current(s.getId().equals(activeScanId))
                        .build())
                .toList();

        model.addAttribute("scanVersions", scanVersions);
        model.addAttribute("currentScanId", activeScanId);

        if (scan == null) {
            model.addAttribute("projectVersion", "-");
            model.addAttribute("totalLicenses", 0);
            model.addAttribute("restrictedCount", 0);
            model.addAttribute("cautionCount", 0);
            model.addAttribute("permittedCount", 0);
            model.addAttribute("unknownCount", 0);
            model.addAttribute("totalObligations", 0);
            model.addAttribute("obligationRestrictedCount", 0);
            model.addAttribute("obligationCautionCount", 0);
            model.addAttribute("obligationPermittedCount", 0);
            model.addAttribute("obligations", List.of());
            model.addAttribute("conflicts", List.of());
            model.addAttribute("reviewItems", List.of());
            model.addAttribute("licenses", List.of());
            return;
        }

        model.addAttribute("projectVersion", scan.getVersion() != null ? scan.getVersion() : "-");

        List<Library> libraries = libraryRepository.findByScanResultIdWithCves(scan.getId());

        // Group by license name (excluding empty/unknown — those go to the Review section)
        Map<String, List<Library>> byLicense = libraries.stream()
                .filter(l -> l.getLicenseName() != null && !l.getLicenseName().isBlank())
                .collect(Collectors.groupingBy(Library::getLicenseName));

        List<LicenseRowDto> licenses = byLicense.entrySet().stream()
                .map(entry -> LicenseRowDto.builder()
                        .name(entry.getKey())
                        .riskLevel(computeMaxRisk(entry.getValue()))
                        .libraryCount(entry.getValue().size())
                        .libraryNames(entry.getValue().stream()
                                .map(l -> l.getName() + " " + l.getVersion())
                                .sorted()
                                .collect(Collectors.toList()))
                        .aiLicenseSummary(entry.getValue().stream()
                                .map(Library::getAiLicenseSummary)
                                .filter(s -> s != null && !s.isBlank())
                                .findFirst()
                                .orElse(null))
                        .build())
                .sorted(Comparator.comparingInt(dto -> riskOrdinal(dto.getRiskLevel())))
                .collect(Collectors.toList());

        long restrictedCount = licenses.stream().filter(l -> "RESTRICTED".equals(l.getRiskLevel())).count();
        long cautionCount    = licenses.stream().filter(l -> "CAUTION".equals(l.getRiskLevel())).count();
        long permittedCount  = licenses.stream().filter(l -> "PERMITTED".equals(l.getRiskLevel())).count();
        long unknownCount    = licenses.stream().filter(l -> "UNKNOWN".equals(l.getRiskLevel())).count();

        List<LicenseObligationGroupDto> obligations = computeObligations(libraries, context);
        List<LicenseConflictDto>        conflicts   = computeConflicts(libraries);
        List<LicenseReviewItemDto>      reviewItems = computeReviewItems(libraries);

        model.addAttribute("totalLicenses", licenses.size());
        model.addAttribute("restrictedCount", restrictedCount);
        model.addAttribute("cautionCount", cautionCount);
        model.addAttribute("permittedCount", permittedCount);
        model.addAttribute("unknownCount", unknownCount);
        model.addAttribute("totalObligations", obligations.size());
        long obligationRestrictedCount = obligations.stream().filter(o -> "RESTRICTED".equals(o.getRiskLevel())).count();
        long obligationCautionCount    = obligations.stream().filter(o -> "CAUTION".equals(o.getRiskLevel())).count();
        long obligationPermittedCount  = obligations.stream().filter(o -> "PERMITTED".equals(o.getRiskLevel())).count();
        model.addAttribute("obligationRestrictedCount", obligationRestrictedCount);
        model.addAttribute("obligationCautionCount", obligationCautionCount);
        model.addAttribute("obligationPermittedCount", obligationPermittedCount);
        model.addAttribute("obligations", obligations);
        model.addAttribute("conflicts", conflicts);
        model.addAttribute("reviewItems", reviewItems);
        model.addAttribute("licenses", licenses);
    }

    private String computeMaxRisk(List<Library> libs) {
        if (libs.stream().anyMatch(l -> l.getLicenseStatus() == LicenseStatus.RESTRICTED)) return "RESTRICTED";
        if (libs.stream().anyMatch(l -> l.getLicenseStatus() == LicenseStatus.CAUTION))     return "CAUTION";
        if (libs.stream().anyMatch(l -> l.getLicenseStatus() == LicenseStatus.UNKNOWN))     return "UNKNOWN";
        return "PERMITTED";
    }

    private int riskOrdinal(String risk) {
        return switch (risk) {
            case "RESTRICTED" -> 0;
            case "CAUTION"    -> 1;
            case "UNKNOWN"    -> 2;
            default           -> 3; // PERMITTED
        };
    }

    // ── Obligation catalog ───────────────────────────────────────────────────────

    /**
     * One declarative rule. {@code applies} is evaluated against the user-supplied
     * deployment context so that obligations not relevant to the current
     * distribution model (e.g. ALLOW_RELINKING for dynamic linking) are filtered out.
     */
    private record ObligationRule(
            String key,
            String label,
            String description,
            String riskLevel,
            String clauseCitation,
            String noticeTemplate,
            Set<String> spdxIds,
            java.util.function.Predicate<LicenseContextDto> applies) {}

    private static final List<ObligationRule> OBLIGATION_RULES = new ArrayList<>();

    static {
        OBLIGATION_RULES.add(new ObligationRule(
                "INCLUDE_LICENSE_NOTICE",
                "Include License Notice",
                "Include the complete license text and copyright notices in all copies and distributions of the software.",
                "PERMITTED",
                "MIT §1 · Apache-2.0 §4(a)(b) · BSD §1-3 · GPL §1 · LGPL §6 · MPL-2.0 §3.3",
                "This product includes software licensed under the following terms:\n\n{LIBRARY_LIST}\n\nThe full license text is reproduced in LICENSES/ directory.",
                Set.of("MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "BSD-4-Clause", "ISC", "Zlib", "BSL-1.0",
                       "MPL-1.0", "MPL-1.1", "MPL-2.0", "EPL-1.0", "EPL-2.0",
                       "LGPL-2.0", "LGPL-2.1", "LGPL-3.0", "GPL-2.0", "GPL-3.0",
                       "AGPL-1.0", "AGPL-3.0", "EUPL-1.1", "EUPL-1.2",
                       "APSL-2.0", "CDDL-1.0", "CDDL-1.1", "CC-BY-4.0", "Python-2.0", "PostgreSQL"),
                ctx -> true));

        OBLIGATION_RULES.add(new ObligationRule(
                "INCLUDE_COPYRIGHT_NOTICE",
                "Preserve Copyright Notices",
                "Reproduce each upstream copyright line verbatim — even when the rest of the license header is omitted.",
                "PERMITTED",
                "MIT §1 · BSD §1 · Apache-2.0 §4(c) · ISC",
                "Copyright (c) <YEAR> <AUTHOR> — see LICENSES/{LICENSE_FILE} for the full notice.\n\nApplies to:\n{LIBRARY_LIST}",
                Set.of("MIT", "BSD-2-Clause", "BSD-3-Clause", "BSD-4-Clause", "ISC", "Zlib",
                       "Apache-2.0", "MPL-2.0"),
                ctx -> true));

        OBLIGATION_RULES.add(new ObligationRule(
                "STATE_CHANGES",
                "State Significant Changes",
                "Mark each modified file with a prominent notice describing what was changed and on which date.",
                "CAUTION",
                "Apache-2.0 §4(b) · MPL-2.0 §3.3 · GPL §2(a) · LGPL §2(a) · EPL §3(a)(ii)",
                "Modified by <ORG>, <YYYY-MM-DD>: <SHORT_DESCRIPTION>\n\nFiles changed under:\n{LIBRARY_LIST}",
                Set.of("Apache-2.0", "MPL-1.0", "MPL-1.1", "MPL-2.0", "EPL-1.0", "EPL-2.0",
                       "LGPL-2.0", "LGPL-2.1", "LGPL-3.0", "GPL-2.0", "GPL-3.0",
                       "AGPL-1.0", "AGPL-3.0", "EUPL-1.1", "EUPL-1.2", "APSL-2.0", "CDDL-1.0"),
                ctx -> ctx.isDistributed() && ctx.isModified()));

        OBLIGATION_RULES.add(new ObligationRule(
                "DISCLOSE_SOURCE",
                "Disclose Corresponding Source",
                "Provide — or offer in writing — the complete corresponding source code (including build scripts) for the covered libraries.",
                "RESTRICTED",
                "GPL-2.0 §3 · GPL-3.0 §6 · AGPL-3.0 §13 · LGPL §6 · MPL-2.0 §3.2",
                "Source code for the following components is available at <URL> or by written request to <CONTACT>:\n\n{LIBRARY_LIST}",
                Set.of("GPL-2.0", "GPL-3.0", "AGPL-1.0", "AGPL-3.0", "SSPL-1.0",
                       "LGPL-2.0", "LGPL-2.1", "LGPL-3.0",
                       "MPL-1.0", "MPL-1.1", "MPL-2.0",
                       "EPL-1.0", "EPL-2.0", "EUPL-1.1", "EUPL-1.2", "CDDL-1.0"),
                LicenseContextDto::isDistributed));

        OBLIGATION_RULES.add(new ObligationRule(
                "SAME_LICENSE_COPYLEFT",
                "Derivatives Must Use Same License",
                "Any derivative work that combines with these components must be distributed under the same (or a compatible later) license.",
                "RESTRICTED",
                "GPL-2.0 §2(b) · GPL-3.0 §5 · AGPL-3.0 §5 · LGPL §2 (combined work)",
                "<DERIVATIVE_WORK_NAME> incorporates components licensed under <SPDX_ID> and is therefore distributed under <SPDX_ID>.\n\nCovered components:\n{LIBRARY_LIST}",
                Set.of("GPL-2.0", "GPL-3.0", "AGPL-1.0", "AGPL-3.0", "SSPL-1.0",
                       "LGPL-2.0", "LGPL-2.1", "LGPL-3.0"),
                LicenseContextDto::isDistributed));

        OBLIGATION_RULES.add(new ObligationRule(
                "WEAK_COPYLEFT_FILE_SCOPE",
                "File-Scope Copyleft (Modified Files Only)",
                "Modifications to the licensed files themselves remain copyleft, but the rest of your project may stay proprietary.",
                "CAUTION",
                "MPL-2.0 §3.1-3.3 · EPL-2.0 §3.1 · CDDL-1.0 §3.1",
                "The following files originate from MPL/EPL/CDDL projects. Modifications to these files must be released under the original license:\n\n{LIBRARY_LIST}",
                Set.of("MPL-1.0", "MPL-1.1", "MPL-2.0", "EPL-1.0", "EPL-2.0", "CDDL-1.0", "CDDL-1.1"),
                LicenseContextDto::isDistributed));

        OBLIGATION_RULES.add(new ObligationRule(
                "ALLOW_RELINKING",
                "Allow Library Replacement (Relinking)",
                "When statically linking an LGPL library you must provide object code or a build mechanism so users can replace the library with a modified version.",
                "CAUTION",
                "LGPL-2.1 §6 · LGPL-3.0 §4(d)",
                "Object files / build scripts required to relink the following LGPL components are bundled in <PATH> or available on request:\n\n{LIBRARY_LIST}",
                Set.of("LGPL-2.0", "LGPL-2.1", "LGPL-3.0"),
                ctx -> ctx.isDistributed() && ctx.isStaticLinking()));

        OBLIGATION_RULES.add(new ObligationRule(
                "NETWORK_USE_DISCLOSURE",
                "Network-Use Source Disclosure",
                "If users interact with the modified component over a network, you must offer them the complete corresponding source code — even when no binary is distributed.",
                "RESTRICTED",
                "AGPL-3.0 §13 · SSPL-1.0 §13 · EUPL-1.2 §5",
                "Users interacting with <SERVICE_NAME> may obtain the corresponding source code (including modifications) at <URL>.\n\nCovered components:\n{LIBRARY_LIST}",
                Set.of("AGPL-1.0", "AGPL-3.0", "SSPL-1.0", "EUPL-1.1", "EUPL-1.2"),
                ctx -> true));

        OBLIGATION_RULES.add(new ObligationRule(
                "PATENT_GRANT",
                "Express Patent Grant",
                "These licenses grant you (and your downstream users) a royalty-free patent license covering the contributions.",
                "PERMITTED",
                "Apache-2.0 §3 · MPL-2.0 §2.1 · EPL-2.0 §2 · GPL-3.0 §11 · AGPL-3.0 §11",
                "Patent grants from the following components apply to this distribution:\n\n{LIBRARY_LIST}",
                Set.of("Apache-2.0", "MPL-2.0", "EPL-2.0", "GPL-3.0", "AGPL-3.0", "LGPL-3.0"),
                ctx -> true));

        OBLIGATION_RULES.add(new ObligationRule(
                "PATENT_RETALIATION",
                "Patent-Litigation Termination",
                "If you initiate a patent infringement claim against contributors, your rights under the license terminate automatically.",
                "CAUTION",
                "Apache-2.0 §3 (last sentence) · MPL-2.0 §5.2 · EPL-2.0 §7 · GPL-3.0 §10 · AGPL-3.0 §10",
                "Initiating patent litigation against contributors of these components will terminate your license:\n\n{LIBRARY_LIST}",
                Set.of("Apache-2.0", "MPL-2.0", "EPL-2.0", "GPL-3.0", "AGPL-3.0"),
                ctx -> true));

        OBLIGATION_RULES.add(new ObligationRule(
                "NO_TRADEMARK_USE",
                "No Trademark Use",
                "You may not use the names, trademarks or logos of the upstream project for endorsement or promotion of your derivative.",
                "PERMITTED",
                "Apache-2.0 §6 · BSD-3-Clause §3",
                "<ORG> does not use the trademarks of the following projects to endorse or promote derivative products:\n\n{LIBRARY_LIST}",
                Set.of("Apache-2.0", "BSD-3-Clause", "BSD-4-Clause", "MPL-2.0"),
                LicenseContextDto::isDistributed));

        OBLIGATION_RULES.add(new ObligationRule(
                "ANTI_TIVOIZATION",
                "Anti-Tivoization (Installation Information)",
                "When shipping these components in a User Product (consumer device), you must also provide the Installation Information needed to run a modified version on that device.",
                "RESTRICTED",
                "GPL-3.0 §6 · AGPL-3.0 §6",
                "Installation information for <DEVICE_MODEL> — including signing keys and flashing instructions — is published at <URL> in compliance with GPLv3 §6:\n\n{LIBRARY_LIST}",
                Set.of("GPL-3.0", "AGPL-3.0", "LGPL-3.0"),
                LicenseContextDto::isEmbedded));

        OBLIGATION_RULES.add(new ObligationRule(
                "ADVERTISING_CLAUSE",
                "Advertising Acknowledgement (Obsolete)",
                "Legacy 4-clause BSD requires advertising materials to include an acknowledgement of the upstream project.",
                "CAUTION",
                "BSD-4-Clause §3",
                "All advertising materials mentioning features or use of this software must display the following acknowledgement:\n\"This product includes software developed by <ORG>.\"\n\n{LIBRARY_LIST}",
                Set.of("BSD-4-Clause"),
                LicenseContextDto::isDistributed));
    }

    // ── Obligation evaluation ────────────────────────────────────────────────────

    private List<LicenseObligationGroupDto> computeObligations(List<Library> libraries, LicenseContextDto context) {
        Map<String, List<String>> libsByExpression = libraries.stream()
                .filter(l -> l.getLicenseName() != null && !l.getLicenseName().isBlank())
                .collect(Collectors.groupingBy(
                        Library::getLicenseName,
                        Collectors.mapping(l -> l.getName() + " " + l.getVersion(), Collectors.toList())));

        Map<String, ParsedExpression> parsedByExpression = new HashMap<>();
        libsByExpression.keySet().forEach(expr -> parsedByExpression.put(expr, parseSpdxExpression(expr)));

        List<LicenseObligationGroupDto> result = new ArrayList<>();
        for (ObligationRule rule : OBLIGATION_RULES) {
            if (!rule.applies().test(context)) continue;

            List<LicenseObligationGroupDto.LicenseEntry> entries = new ArrayList<>();
            for (Map.Entry<String, List<String>> le : libsByExpression.entrySet()) {
                ParsedExpression parsed = parsedByExpression.get(le.getKey());
                String matchedSpdx = parsed.firstMatch(rule.spdxIds());
                if (matchedSpdx == null) continue;

                List<String> libs = le.getValue().stream().sorted().collect(Collectors.toList());
                entries.add(LicenseObligationGroupDto.LicenseEntry.builder()
                        .spdxId(le.getKey())
                        .spdxUrl(spdxUrlFor(matchedSpdx))
                        .orChoice(parsed.orChoice())
                        .libraries(libs)
                        .build());
            }
            if (!entries.isEmpty()) {
                entries.sort(Comparator.comparingInt(e -> -e.getLibraries().size()));
                result.add(LicenseObligationGroupDto.builder()
                        .key(rule.key())
                        .label(rule.label())
                        .description(rule.description())
                        .riskLevel(rule.riskLevel())
                        .clauseCitation(rule.clauseCitation())
                        .noticeTemplate(rule.noticeTemplate())
                        .entries(entries)
                        .build());
            }
        }
        return result;
    }

    private static String spdxUrlFor(String id) {
        if (id == null || id.isBlank()) return null;
        return "https://spdx.org/licenses/" + id + ".html";
    }

    // ── SPDX expression parsing ──────────────────────────────────────────────────

    private static final Pattern SPDX_TOKEN_SPLIT = Pattern.compile("[()\\s]+");

    /**
     * Parsed view of an SPDX licence expression.
     *
     * Distinguishes operators so that obligations can be matched correctly:
     *   - AND / WITH → all operands obligate the user simultaneously
     *   - OR         → user may pick the cheapest option (flagged via {@link #orChoice})
     *
     * Exception identifiers (after WITH) are skipped during matching.
     */
    private record ParsedExpression(List<String> licenseIds, boolean orChoice) {
        boolean matches(String targetSpdx) {
            for (String id : licenseIds) {
                if (id.equals(targetSpdx)) return true;
                if (id.startsWith(targetSpdx + "-")) return true;       // GPL-2.0-only, GPL-2.0-or-later
                if (id.equals(targetSpdx + "+")) return true;            // GPL-2.0+
            }
            return false;
        }

        String firstMatch(Set<String> targets) {
            for (String target : targets) {
                if (matches(target)) return target;
            }
            return null;
        }
    }

    /**
     * Parses an SPDX licence expression into its component licence identifiers.
     * Handles {@code AND}, {@code OR}, {@code WITH}, parentheses and the deprecated
     * "{@code +}" suffix.
     */
    private ParsedExpression parseSpdxExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return new ParsedExpression(List.of(), false);
        }

        String[] raw = SPDX_TOKEN_SPLIT.split(expression.trim());
        List<String> ids = new ArrayList<>();
        boolean isOr = false;
        boolean skipNext = false; // skip exception identifier that follows WITH

        for (String token : raw) {
            if (token.isEmpty()) continue;
            String upper = token.toUpperCase(Locale.ROOT);
            if (skipNext) {
                skipNext = false;
                continue;
            }
            switch (upper) {
                case "AND" -> { /* operator: handled implicitly */ }
                case "OR"   -> isOr = true;
                case "WITH" -> skipNext = true;
                default     -> ids.add(normaliseLegacySuffix(token));
            }
        }

        if (ids.isEmpty()) {
            ids.add(expression.trim());
        }
        return new ParsedExpression(ids, isOr);
    }

    /** "GPL-2.0+" → "GPL-2.0-or-later" (SPDX 2.1+ canonical form). */
    private String normaliseLegacySuffix(String id) {
        if (id.endsWith("+") && id.length() > 1) {
            return id.substring(0, id.length() - 1) + "-or-later";
        }
        return id;
    }

    // ── License compatibility / conflict detection ───────────────────────────────

    private record ConflictRule(Set<String> a, Set<String> b, String severity, String title, String explanation, String recommendation) {}

    private static final List<ConflictRule> CONFLICT_RULES = List.of(
            new ConflictRule(
                    Set.of("GPL-2.0"), Set.of("Apache-2.0"),
                    "HIGH",
                    "GPL-2.0 incompatible with Apache-2.0 patent termination",
                    "The Free Software Foundation considers Apache-2.0's patent-termination clause to be an additional restriction not permitted by GPL-2.0-only. Distributing a combined work is therefore considered a license violation.",
                    "Upgrade the GPL-2.0 component to GPL-2.0-or-later / GPL-3.0, or replace one side with an MIT/BSD alternative."),
            new ConflictRule(
                    Set.of("AGPL-3.0", "AGPL-1.0"), Set.of("Apache-2.0", "MIT", "BSD-3-Clause", "BSD-2-Clause", "ISC"),
                    "MEDIUM",
                    "AGPL network-use clause overrides permissive licensing",
                    "Combining AGPL with permissive components forces the entire combined work to be distributed under AGPL when accessed over a network — including for SaaS deployments.",
                    "Isolate AGPL components behind a process boundary (separate microservice with documented IPC), or replace with a non-AGPL alternative."),
            new ConflictRule(
                    Set.of("SSPL-1.0"), Set.of("MIT", "BSD-3-Clause", "BSD-2-Clause", "ISC", "Apache-2.0"),
                    "HIGH",
                    "SSPL-1.0 is not an OSI-approved license",
                    "SSPL-1.0 requires open-sourcing the entire service stack (including orchestration) when offered as a managed service. It is also not OSI-approved and is rejected by several enterprise OSS policies.",
                    "Replace SSPL components or obtain a commercial license from the upstream vendor."),
            new ConflictRule(
                    Set.of("GPL-2.0", "GPL-3.0"), Set.of("CDDL-1.0", "CDDL-1.1", "EPL-1.0", "EPL-2.0", "MPL-1.1"),
                    "HIGH",
                    "GPL ↔ CDDL / EPL / MPL file-scope copyleft incompatibility",
                    "GPL is project-scope copyleft while CDDL/EPL/MPL are file-scope copyleft. Combining them in the same distributable creates conflicting requirements on the project boundary.",
                    "Separate the components into independent processes communicating via IPC, or replace one side."),
            new ConflictRule(
                    Set.of("BSD-4-Clause"), Set.of("GPL-2.0", "GPL-3.0", "AGPL-3.0", "LGPL-2.1", "LGPL-3.0"),
                    "MEDIUM",
                    "BSD-4-Clause advertising clause conflicts with GPL family",
                    "The 4-clause BSD advertising clause is considered a non-removable additional restriction by the FSF, making combination with the GPL family non-compliant.",
                    "Ask the upstream author to relicense under BSD-3-Clause, or replace the dependency.")
    );

    private List<LicenseConflictDto> computeConflicts(List<Library> libraries) {
        Map<String, List<String>> libsByExpression = libraries.stream()
                .filter(l -> l.getLicenseName() != null && !l.getLicenseName().isBlank())
                .collect(Collectors.groupingBy(
                        Library::getLicenseName,
                        Collectors.mapping(l -> l.getName() + " " + l.getVersion(), Collectors.toList())));

        Map<String, ParsedExpression> parsed = new HashMap<>();
        libsByExpression.keySet().forEach(expr -> parsed.put(expr, parseSpdxExpression(expr)));

        List<LicenseConflictDto> conflicts = new ArrayList<>();
        for (ConflictRule rule : CONFLICT_RULES) {
            List<String> libsA = collectMatchingLibraries(libsByExpression, parsed, rule.a());
            if (libsA.isEmpty()) continue;
            List<String> libsB = collectMatchingLibraries(libsByExpression, parsed, rule.b());
            if (libsB.isEmpty()) continue;

            conflicts.add(LicenseConflictDto.builder()
                    .licenseA(String.join(" / ", rule.a()))
                    .licenseB(String.join(" / ", rule.b()))
                    .severity(rule.severity())
                    .title(rule.title())
                    .explanation(rule.explanation())
                    .recommendation(rule.recommendation())
                    .librariesA(libsA)
                    .librariesB(libsB)
                    .build());
        }
        return conflicts;
    }

    private List<String> collectMatchingLibraries(Map<String, List<String>> libsByExpression,
                                                  Map<String, ParsedExpression> parsed,
                                                  Set<String> targets) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : libsByExpression.entrySet()) {
            if (parsed.get(e.getKey()).firstMatch(targets) != null) {
                out.addAll(e.getValue());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    // ── Review (UNKNOWN / Proprietary / Custom / Missing) ────────────────────────

    private List<LicenseReviewItemDto> computeReviewItems(List<Library> libraries) {
        Map<String, List<Library>> grouped = libraries.stream()
                .collect(Collectors.groupingBy(l -> classifyReviewBucket(l.getLicenseName())));

        List<LicenseReviewItemDto> out = new ArrayList<>();
        for (Map.Entry<String, List<Library>> e : grouped.entrySet()) {
            String bucket = e.getKey();
            if (bucket.equals("OK")) continue;

            Map<String, List<Library>> byRaw = e.getValue().stream()
                    .collect(Collectors.groupingBy(l -> l.getLicenseName() == null ? "" : l.getLicenseName()));

            for (Map.Entry<String, List<Library>> rawEntry : byRaw.entrySet()) {
                List<String> libNames = rawEntry.getValue().stream()
                        .map(l -> l.getName() + " " + l.getVersion())
                        .sorted()
                        .toList();
                out.add(LicenseReviewItemDto.builder()
                        .rawLicense(rawEntry.getKey().isBlank() ? "(missing)" : rawEntry.getKey())
                        .reason(bucket)
                        .hint(hintFor(bucket))
                        .libraries(libNames)
                        .build());
            }
        }

        out.sort(Comparator.comparing(LicenseReviewItemDto::getReason)
                .thenComparing(LicenseReviewItemDto::getRawLicense));
        return out;
    }

    private String classifyReviewBucket(String licenseName) {
        if (licenseName == null || licenseName.isBlank()) return "MISSING";
        String upper = licenseName.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("LICENSEREF-")) return "CUSTOM";
        if (upper.equals("PROPRIETARY") || upper.equals("COMMERCIAL") || upper.contains("ALL RIGHTS RESERVED")) return "PROPRIETARY";
        if (upper.equals("UNKNOWN") || upper.equals("NONE") || upper.equals("NOASSERTION")) return "UNKNOWN";
        return "OK";
    }

    private String hintFor(String bucket) {
        return switch (bucket) {
            case "MISSING"     -> "Package manifest provided no license field. Confirm with upstream and add to the internal allowlist.";
            case "UNKNOWN"     -> "Scanner could not match this string to an SPDX identifier. Verify the project's actual license file.";
            case "PROPRIETARY" -> "Commercial / closed-source component. Ensure a valid license agreement is on file.";
            case "CUSTOM"      -> "Non-standard SPDX LicenseRef — requires individual legal review.";
            default            -> "";
        };
    }

    // ── Exports: NOTICE.txt and SPDX SBOM ────────────────────────────────────────

    @Transactional(readOnly = true)
    public ExportPayload buildNoticeFile(Long projectId, Long scanId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        ScanResult scan = resolveScan(projectId, scanId);

        StringBuilder sb = new StringBuilder();
        sb.append("THIRD-PARTY SOFTWARE NOTICES AND INFORMATION\n");
        sb.append("=============================================\n\n");
        sb.append("Project : ").append(project.getName()).append('\n');
        if (scan != null) {
            sb.append("Version : ").append(scan.getVersion() == null ? "-" : scan.getVersion()).append('\n');
            sb.append("Scan    : #").append(scan.getId()).append(" @ ")
                    .append(scan.getScannedAt() == null ? "-" : scan.getScannedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .append('\n');
        }
        sb.append("Generated by OsWL — not legal advice.\n\n");
        sb.append("---------------------------------------------\n\n");

        if (scan == null) {
            sb.append("No completed scans available for project ").append(project.getName()).append(".\n");
            return new ExportPayload(safeFile("NOTICE-" + project.getName() + ".txt"), sb.toString());
        }

        List<Library> libraries = libraryRepository.findByScanResultIdWithCves(scan.getId());

        Map<String, List<Library>> byLicense = libraries.stream()
                .filter(l -> l.getLicenseName() != null && !l.getLicenseName().isBlank())
                .collect(Collectors.groupingBy(Library::getLicenseName, TreeMap::new, Collectors.toList()));

        for (Map.Entry<String, List<Library>> e : byLicense.entrySet()) {
            sb.append("License: ").append(e.getKey()).append('\n');
            sb.append("Components:\n");
            e.getValue().stream()
                    .map(l -> "  - " + l.getName() + " " + l.getVersion())
                    .sorted()
                    .forEach(line -> sb.append(line).append('\n'));
            sb.append('\n');
        }

        long missing = libraries.stream()
                .filter(l -> l.getLicenseName() == null || l.getLicenseName().isBlank())
                .count();
        if (missing > 0) {
            sb.append("---------------------------------------------\n");
            sb.append("Components with no declared license (").append(missing).append("):\n");
            libraries.stream()
                    .filter(l -> l.getLicenseName() == null || l.getLicenseName().isBlank())
                    .map(l -> "  - " + l.getName() + " " + l.getVersion())
                    .sorted()
                    .forEach(line -> sb.append(line).append('\n'));
        }

        return new ExportPayload(safeFile("NOTICE-" + project.getName() + ".txt"), sb.toString());
    }

    @Transactional(readOnly = true)
    public ExportPayload buildSpdxSbom(Long projectId, Long scanId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        ScanResult scan = resolveScan(projectId, scanId);
        StringBuilder sb = new StringBuilder();

        sb.append("SPDXVersion: SPDX-2.3\n");
        sb.append("DataLicense: CC0-1.0\n");
        sb.append("SPDXID: SPDXRef-DOCUMENT\n");
        sb.append("DocumentName: ").append(project.getName()).append('\n');
        sb.append("DocumentNamespace: https://oswl.local/spdx/")
                .append(project.getProjectUuid()).append('/')
                .append(scan == null ? "no-scan" : scan.getId()).append('\n');
        sb.append("Creator: Tool: OsWL\n");
        sb.append("Created: ").append(java.time.LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("Z\n\n");

        if (scan == null) {
            sb.append("# No completed scan available\n");
            return new ExportPayload(safeFile("oswl-sbom-" + project.getName() + ".spdx"), sb.toString());
        }

        List<Library> libraries = libraryRepository.findByScanResultIdWithCves(scan.getId());
        int idx = 0;
        for (Library lib : libraries) {
            String pkgId = "SPDXRef-Package-" + (++idx);
            sb.append("PackageName: ").append(safe(lib.getName())).append('\n');
            sb.append("SPDXID: ").append(pkgId).append('\n');
            sb.append("PackageVersion: ").append(safe(lib.getVersion())).append('\n');
            sb.append("PackageDownloadLocation: NOASSERTION\n");
            String license = lib.getLicenseName();
            sb.append("PackageLicenseConcluded: ").append(license == null || license.isBlank() ? "NOASSERTION" : license).append('\n');
            sb.append("PackageLicenseDeclared: ").append(license == null || license.isBlank() ? "NOASSERTION" : license).append('\n');
            sb.append("PackageCopyrightText: NOASSERTION\n\n");
        }

        return new ExportPayload(safeFile("oswl-sbom-" + project.getName() + ".spdx"), sb.toString());
    }

    private ScanResult resolveScan(Long projectId, Long scanId) {
        List<ScanResult> scans = scanResultRepository.findCompletedByProjectId(projectId);
        if (scans.isEmpty()) return null;
        if (scanId == null) return scans.get(0);
        return scans.stream().filter(s -> s.getId().equals(scanId)).findFirst().orElse(scans.get(0));
    }

    private String safe(String s) {
        return s == null ? "NOASSERTION" : s;
    }

    private String safeFile(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public record ExportPayload(String fileName, String body) {}
}
