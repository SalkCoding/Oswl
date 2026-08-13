package com.salkcoding.oswl.local;

import com.salkcoding.oswl.service.cvss.CvssV4Calculator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Throwaway sanity check for {@link CvssV4Calculator}, cross-checked against scores computed by
 * FIRST's own unmodified reference JavaScript (github.com/FIRSTdotorg/cvss-v4-calculator,
 * {@code cvss_score.js} run under Node against the same 20 vectors). Not part of the build or
 * test suite — compiled and run manually, mirroring {@link LockParserSanityCheck}.
 */
public final class CvssV4CalculatorSanityCheck {

    private CvssV4CalculatorSanityCheck() {}

    // vector -> expected score, taken verbatim from the Node run of FIRST's reference cvss_score.js.
    private static final Map<String, Double> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N", 9.3);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:N/SA:N", 0.0);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:N/VA:N/SC:N/SI:N/SA:N", 8.7);
        EXPECTED.put("CVSS:4.0/AV:A/AC:L/AT:N/PR:L/UI:N/VC:L/VI:L/VA:N/SC:N/SI:N/SA:N", 5.1);
        EXPECTED.put("CVSS:4.0/AV:P/AC:H/AT:P/PR:H/UI:A/VC:L/VI:L/VA:L/SC:L/SI:L/SA:L", 1.0);
        EXPECTED.put("CVSS:4.0/AV:L/AC:H/AT:P/PR:H/UI:A/VC:N/VI:N/VA:N/SC:N/SI:N/SA:N", 0.0);
        EXPECTED.put("CVSS:4.0/AV:N/AC:H/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:H/SI:H/SA:H", 9.5);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:L/UI:N/VC:L/VI:L/VA:L/SC:N/SI:N/SA:N", 5.3);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:P/VC:H/VI:L/VA:N/SC:N/SI:N/SA:N", 7.1);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:U", 8.1);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:P", 8.9);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N/E:A", 9.3);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:L/VA:N/SC:N/SI:N/SA:N/CR:H/IR:H/AR:H", 8.8);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:L/VA:N/SC:N/SI:N/SA:N/CR:L/IR:L/AR:L", 7.8);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:L/VI:L/VA:L/SC:N/SI:N/SA:N/MVC:H/MVI:H/MVA:H", 9.3);
        EXPECTED.put("CVSS:4.0/AV:A/AC:H/AT:N/PR:L/UI:N/VC:L/VI:N/VA:N/SC:N/SI:N/SA:N", 2.1);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:H/SI:H/SA:H", 7.9);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:P/PR:N/UI:N/VC:H/VI:H/VA:L/SC:N/SI:N/SA:N", 9.2);
        EXPECTED.put("CVSS:4.0/AV:P/AC:L/AT:N/PR:N/UI:N/VC:L/VI:N/VA:N/SC:N/SI:N/SA:N", 2.4);
        EXPECTED.put("CVSS:4.0/AV:N/AC:L/AT:N/PR:H/UI:N/VC:H/VI:H/VA:N/SC:N/SI:N/SA:N", 8.5);
    }

    public static void main(String[] args) {
        int failures = 0;
        for (Map.Entry<String, Double> e : EXPECTED.entrySet()) {
            Double actual = CvssV4Calculator.baseScore(e.getKey());
            boolean ok = actual != null && Math.abs(actual - e.getValue()) < 1e-9;
            System.out.printf("%s %s expected=%s actual=%s%n", ok ? "PASS" : "FAIL", e.getKey(), e.getValue(), actual);
            if (!ok) failures++;
        }

        // Malformed / non-v4.0 vectors must return null, not throw or guess.
        failures += check("null for v3.1 vector", CvssV4Calculator.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H") == null);
        failures += check("null for missing required metric", CvssV4Calculator.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N") == null);
        failures += check("null for garbage", CvssV4Calculator.baseScore("not a vector") == null);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "FAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int check(String label, boolean ok) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + label);
        return ok ? 0 : 1;
    }
}
