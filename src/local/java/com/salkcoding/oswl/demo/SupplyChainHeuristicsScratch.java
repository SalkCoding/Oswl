package com.salkcoding.oswl.demo;

import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.service.vulnerability.SupplyChainHeuristicsService;

import java.util.ArrayList;
import java.util.List;

/**
 * Development-only scratch verifier ({@code src/local/java} — not in the production bootJar)
 * for the supply-chain heuristics.
 *
 * <p>Run after {@code ./gradlew compileJava compileLocalJava processResources}:
 * <pre>
 * java -cp "build/classes/java/local;build/classes/java/main;build/resources/main" \
 *      com.salkcoding.oswl.demo.SupplyChainHeuristicsScratch
 * </pre>
 * Exits non-zero when any expectation fails.
 */
public final class SupplyChainHeuristicsScratch {

    private SupplyChainHeuristicsScratch() {
    }

    private record Case(String ecosystem, String name, boolean expectRisk, String note) {
    }

    static void main(String[] args) {
        SupplyChainHeuristicsService service = new SupplyChainHeuristicsService();
        service.load();

        List<Case> cases = new ArrayList<>(List.of(
                // ── DoD cases: known typosquat names must be flagged ──────────────
                new Case("NPM", "eventstream", true, "1 edit from event-stream"),
                new Case("NPM", "lodashs", true, "1 edit from lodash"),
                new Case("NPM", "crossenv", true, "1 edit from cross-env"),
                // ── Confusable characters ────────────────────────────────────────
                new Case("NPM", "l0dash", true, "0/o confusable of lodash"),
                new Case("NPM", "1odash", true, "1/l confusable of lodash"),
                // ── Typosquat similarity in other ecosystems ─────────────────────
                new Case("PYPI", "reqeusts", true, "transposition of requests (2 edits)"),
                new Case("PYPI", "numpi", true, "1 edit from numpy"),
                new Case("MAVEN", "org.springframework:spring-coree", true, "1 edit from spring-core"),
                // ── Dependency confusion (lightweight) ───────────────────────────
                new Case("NPM", "acme-internal", true, "internal-looking name from public registry"),
                new Case("PYPI", "mylib-private", true, "private-looking name from public registry"),
                new Case("MAVEN", "com.evil:jackson-databind", true, "well-known artifactId, wrong groupId"),
                // ── Genuine popular packages must NOT be flagged ─────────────────
                new Case("NPM", "lodash", false, "the real lodash"),
                new Case("NPM", "event-stream", false, "the real event-stream"),
                new Case("NPM", "cross-env", false, "the real cross-env"),
                new Case("NPM", "react", false, "the real react"),
                new Case("NPM", "axios", false, "the real axios"),
                new Case("PYPI", "requests", false, "the real requests"),
                new Case("PYPI", "python_dateutil", false, "PEP 503 normalizes to python-dateutil"),
                new Case("PYPI", "tomli", false, "real package near toml (allowlist)"),
                new Case("PYPI", "tomli-w", false, "real package near tomli (allowlist)"),
                new Case("MAVEN", "com.fasterxml.jackson.core:jackson-databind", false, "real jackson-databind"),
                new Case("MAVEN", "org.apache.commons:commons-lang3", false, "real commons-lang3"),
                // ── Unrelated / unsupported names must NOT be flagged ────────────
                new Case("NPM", "my-totally-unrelated-lib", false, "no near popular name"),
                new Case("GO", "github.com/gin-gonic/gin", false, "no GO list — heuristics skip"),
                new Case("NPM", "abc", false, "shorter than MIN_NAME_LENGTH")
        ));

        int failures = 0;
        for (Case c : cases) {
            // Exercise the entity path (evaluateAndApply), not just evaluate()
            Library lib = Library.builder()
                    .name(c.name()).version("1.0.0").ecosystem(c.ecosystem()).build();
            boolean changed = service.evaluateAndApply(lib);
            boolean risk = lib.isTyposquatRisk();
            boolean ok = risk == c.expectRisk()
                    && (!risk || (changed && lib.getTyposquatReason() != null));
            if (!ok) {
                failures++;
            }
            System.out.printf("%s %-5s %-45s → risk=%-5s %s%n",
                    ok ? "PASS" : "FAIL", c.ecosystem(), c.name(), risk,
                    risk ? "[" + lib.getTyposquatReason() + "]" : "(" + c.note() + ")");
        }

        // Idempotency: a second evaluation of an unchanged library must report no change
        Library lodash = Library.builder().name("lodashs").version("4.17.21").ecosystem("NPM").build();
        service.evaluateAndApply(lodash);
        if (service.evaluateAndApply(lodash)) {
            System.out.println("FAIL idempotency — second evaluateAndApply reported a change");
            failures++;
        } else {
            System.out.println("PASS idempotency — unchanged result is not re-saved");
        }

        // Clearing: a flag must be removable when the evaluation turns clean
        Library fixed = Library.builder().name("lodashs").version("4.17.21").ecosystem("NPM").build();
        service.evaluateAndApply(fixed);
        Library renamed = Library.builder()
                    .name("lodash").version("4.17.21").ecosystem("NPM").build();
        renamed.updateTyposquatRisk(fixed.isTyposquatRisk(), fixed.getTyposquatReason());
        boolean cleared = service.evaluateAndApply(renamed);
        if (!cleared || renamed.isTyposquatRisk() || renamed.getTyposquatReason() != null) {
            System.out.println("FAIL clearing — clean evaluation did not reset the flag");
            failures++;
        } else {
            System.out.println("PASS clearing — clean evaluation resets flag and reason");
        }

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED (" + cases.size() + " cases + idempotency + clearing)");
    }
}
