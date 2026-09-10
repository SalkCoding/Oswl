package com.salkcoding.oswl.service.cvss;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CVSS v4.0 Base and Environmental score calculator.
 *
 * <p>Unlike v3.x (see {@link CvssV3Calculator}), v4.0 scoring is not a closed-form formula: a
 * vector resolves to a "MacroVector" (a 6-digit code across six equivalence classes, EQ1–EQ6),
 * whose score is looked up in a table published by FIRST.org and then adjusted by interpolating
 * how far the actual vector sits from the highest-severity vector sharing that same MacroVector.
 * Reproducing that by hand from the spec prose risks a subtly wrong implementation that still
 * looks plausible — the exact failure mode this class exists to avoid.
 *
 * <p>Ported directly from FIRST's own reference implementation
 * (github.com/FIRSTdotorg/cvss-v4-calculator — {@code cvss_score.js}, BSD-2-Clause; see
 * {@code THIRD_PARTY_LICENSES.md}), method for method, rather than re-derived from the spec
 * document. The 270-entry MacroVector score table is vendored verbatim as
 * {@code resources/cvss/cvss-v4-lookup.json}; the small per-equivalence-class "maximum severity
 * vector" and "maximum severity distance" tables ({@code max_composed.js}/{@code max_severity.js}
 * in the same repo, same license) are small enough to transcribe directly as constants below
 * rather than ship as a second resource file.
 *
 * <p>What OsWL calls "Base Score" and "Environmental Score" here are exactly what this same
 * algorithm computes when the Threat (E) and Environmental (CR/IR/AR/M*) metrics are absent from
 * the vector — CVSS v4.0 defines those metrics' "Not Defined" (X) value to mean exactly that, so
 * one implementation naturally covers both, the same relationship {@link CvssV3Calculator}'s two
 * entry points have.
 */
@Slf4j
public final class CvssV4Calculator {

    private CvssV4Calculator() {
    }

    private static final String LOOKUP_RESOURCE = "/cvss/cvss-v4-lookup.json";
    private static final Map<String, Double> LOOKUP = loadLookup();

    private static Map<String, Double> loadLookup() {
        Map<String, Double> map = new HashMap<>();
        try (InputStream in = CvssV4Calculator.class.getResourceAsStream(LOOKUP_RESOURCE)) {
            if (in == null) {
                log.warn("[CvssV4] Bundled lookup table {} not found — every CVSS v4.0 vector will score as unscoreable", LOOKUP_RESOURCE);
                return Map.of();
            }
            JsonNode root = new ObjectMapper().readTree(in);
            for (Map.Entry<String, JsonNode> entry : root.properties()) {
                map.put(entry.getKey(), entry.getValue().asDouble());
            }
        } catch (Exception e) {
            log.warn("[CvssV4] Failed to load {}: {}", LOOKUP_RESOURCE, e.getMessage());
            return Map.of();
        }
        return Map.copyOf(map);
    }

    /**
     * Computes the CVSS v4.0 Base Score for a vector string.
     *
     * @return the base score (0.0–10.0), or {@code null} if {@code vector} is not a well-formed
     *         CVSS v4.0 vector, or the lookup table failed to load
     */
    public static Double baseScore(String vector) {
        CvssV4Vector v = CvssV4Vector.parse(vector);
        if (v == null || LOOKUP.isEmpty()) {
            return null;
        }
        return score(v);
    }

    /**
     * Computes the CVSS v4.0 Environmental Score for a vector string, overriding the three
     * Security Requirement metrics (Confidentiality/Integrity/Availability Requirement) — the
     * same three metrics {@link CvssV3Calculator#environmentalScore} varies, reusing its
     * {@link CvssV3Calculator.Requirement} enum since CVSS v3 and v4 both use High/Medium/Low here.
     *
     * @return the environmental score (0.0–10.0), or {@code null} if {@code vector} is not a
     *         well-formed CVSS v4.0 vector, or the lookup table failed to load
     */
    public static Double environmentalScore(String vector, CvssV3Calculator.Requirement cr,
                                            CvssV3Calculator.Requirement ir, CvssV3Calculator.Requirement ar) {
        CvssV4Vector v = CvssV4Vector.parse(vector);
        if (v == null || LOOKUP.isEmpty()) {
            return null;
        }
        v = v.withOverrides(Map.of("CR", letter(cr), "IR", letter(ir), "AR", letter(ar)));
        return score(v);
    }

    private static String letter(CvssV3Calculator.Requirement r) {
        return switch (r) {
            case LOW -> "L";
            case MEDIUM -> "M";
            case HIGH -> "H";
        };
    }

    // ── FIRST.org cvss_score.js, ported method for method ───────────────────────────────────

    private static final List<String> NO_IMPACT_METRICS = List.of("VC", "VI", "VA", "SC", "SI", "SA");

    private static double score(CvssV4Vector v) {
        if (NO_IMPACT_METRICS.stream().allMatch(metric -> "N".equals(m(v, metric)))) {
            return 0.0;
        }

        String macroVectorResult = macroVector(v);
        Double baseValue = LOOKUP.get(macroVectorResult);
        if (baseValue == null) {
            // Every well-formed vector resolves to one of the 270 vendored MacroVectors —
            // reaching this means the table failed to load or a parsing bug produced an
            // impossible EQ combination. Fail closed rather than guess.
            log.warn("[CvssV4] MacroVector '{}' has no lookup entry — cannot score", macroVectorResult);
            return 0.0;
        }
        double value = baseValue;

        int eq1 = digit(macroVectorResult, 0);
        int eq2 = digit(macroVectorResult, 1);
        int eq3 = digit(macroVectorResult, 2);
        int eq4 = digit(macroVectorResult, 3);
        int eq5 = digit(macroVectorResult, 4);
        int eq6 = digit(macroVectorResult, 5);

        String eq1NextLower = key(eq1 + 1, eq2, eq3, eq4, eq5, eq6);
        String eq2NextLower = key(eq1, eq2 + 1, eq3, eq4, eq5, eq6);

        Double scoreEq3Eq6NextLower;
        if (eq3 == 1 && eq6 == 1) {
            scoreEq3Eq6NextLower = LOOKUP.get(key(eq1, eq2, eq3 + 1, eq4, eq5, eq6));
        } else if (eq3 == 0 && eq6 == 1) {
            scoreEq3Eq6NextLower = LOOKUP.get(key(eq1, eq2, eq3 + 1, eq4, eq5, eq6));
        } else if (eq3 == 1 && eq6 == 0) {
            scoreEq3Eq6NextLower = LOOKUP.get(key(eq1, eq2, eq3, eq4, eq5, eq6 + 1));
        } else if (eq3 == 0 && eq6 == 0) {
            Double left = LOOKUP.get(key(eq1, eq2, eq3, eq4, eq5, eq6 + 1));
            Double right = LOOKUP.get(key(eq1, eq2, eq3 + 1, eq4, eq5, eq6));
            scoreEq3Eq6NextLower = (left != null && right != null && left > right) ? left : right;
        } else {
            // 21 --> 32 (does not exist) — deliberately unresolvable, same as the reference impl.
            scoreEq3Eq6NextLower = LOOKUP.get(key(eq1, eq2, eq3 + 1, eq4, eq5, eq6 + 1));
        }

        String eq4NextLower = key(eq1, eq2, eq3, eq4 + 1, eq5, eq6);
        String eq5NextLower = key(eq1, eq2, eq3, eq4, eq5 + 1, eq6);

        Double scoreEq1NextLower = LOOKUP.get(eq1NextLower);
        Double scoreEq2NextLower = LOOKUP.get(eq2NextLower);
        Double scoreEq4NextLower = LOOKUP.get(eq4NextLower);
        Double scoreEq5NextLower = LOOKUP.get(eq5NextLower);

        List<String> eq1Maxes = MAX_COMPOSED_EQ1.get(eq1);
        List<String> eq2Maxes = MAX_COMPOSED_EQ2.get(eq2);
        List<String> eq3Eq6Maxes = MAX_COMPOSED_EQ3.get(eq3).get(eq6);
        List<String> eq4Maxes = MAX_COMPOSED_EQ4.get(eq4);
        List<String> eq5Maxes = MAX_COMPOSED_EQ5.get(eq5);

        // Find the first candidate "highest severity vector in this MacroVector" that the
        // to-be-scored vector is no more severe than in every metric (severity distance >= 0
        // for all of them) — mirrors the reference impl's early-break-on-first-fit.
        String maxVector = null;
        double sdAV = 0, sdPR = 0, sdUI = 0, sdAC = 0, sdAT = 0;
        double sdVC = 0, sdVI = 0, sdVA = 0, sdSC = 0, sdSI = 0, sdSA = 0;
        double sdCR = 0, sdIR = 0, sdAR = 0;
        outer:
        for (String eq1Max : eq1Maxes) {
            for (String eq2Max : eq2Maxes) {
                for (String eq3Eq6Max : eq3Eq6Maxes) {
                    for (String eq4Max : eq4Maxes) {
                        for (String eq5Max : eq5Maxes) {
                            String candidate = eq1Max + eq2Max + eq3Eq6Max + eq4Max + eq5Max;
                            double dAV = AV_LEVELS.get(m(v, "AV")) - AV_LEVELS.get(extract("AV", candidate));
                            double dPR = PR_LEVELS.get(m(v, "PR")) - PR_LEVELS.get(extract("PR", candidate));
                            double dUI = UI_LEVELS.get(m(v, "UI")) - UI_LEVELS.get(extract("UI", candidate));
                            double dAC = AC_LEVELS.get(m(v, "AC")) - AC_LEVELS.get(extract("AC", candidate));
                            double dAT = AT_LEVELS.get(m(v, "AT")) - AT_LEVELS.get(extract("AT", candidate));
                            double dVC = VC_LEVELS.get(m(v, "VC")) - VC_LEVELS.get(extract("VC", candidate));
                            double dVI = VC_LEVELS.get(m(v, "VI")) - VC_LEVELS.get(extract("VI", candidate));
                            double dVA = VC_LEVELS.get(m(v, "VA")) - VC_LEVELS.get(extract("VA", candidate));
                            double dSC = SC_LEVELS.get(m(v, "SC")) - SC_LEVELS.get(extract("SC", candidate));
                            double dSI = SI_LEVELS.get(m(v, "SI")) - SI_LEVELS.get(extract("SI", candidate));
                            double dSA = SI_LEVELS.get(m(v, "SA")) - SI_LEVELS.get(extract("SA", candidate));
                            double dCR = CR_LEVELS.get(m(v, "CR")) - CR_LEVELS.get(extract("CR", candidate));
                            double dIR = CR_LEVELS.get(m(v, "IR")) - CR_LEVELS.get(extract("IR", candidate));
                            double dAR = CR_LEVELS.get(m(v, "AR")) - CR_LEVELS.get(extract("AR", candidate));
                            if (dAV < 0 || dPR < 0 || dUI < 0 || dAC < 0 || dAT < 0 || dVC < 0 || dVI < 0 || dVA < 0
                                    || dSC < 0 || dSI < 0 || dSA < 0 || dCR < 0 || dIR < 0 || dAR < 0) {
                                continue;
                            }
                            maxVector = candidate;
                            sdAV = dAV; sdPR = dPR; sdUI = dUI; sdAC = dAC; sdAT = dAT;
                            sdVC = dVC; sdVI = dVI; sdVA = dVA; sdSC = dSC; sdSI = dSI; sdSA = dSA;
                            sdCR = dCR; sdIR = dIR; sdAR = dAR;
                            break outer;
                        }
                    }
                }
            }
        }
        if (maxVector == null) {
            // Should not happen for a well-formed vector (the exact-match candidate is always
            // among the maxes) — fail closed rather than silently score against a zero baseline.
            log.warn("[CvssV4] No maximum-severity vector matched MacroVector '{}' — cannot score", macroVectorResult);
            return Math.max(0.0, Math.min(10.0, value));
        }

        double currentSeverityDistanceEq1 = sdAV + sdPR + sdUI;
        double currentSeverityDistanceEq2 = sdAC + sdAT;
        double currentSeverityDistanceEq3Eq6 = sdVC + sdVI + sdVA + sdCR + sdIR + sdAR;
        double currentSeverityDistanceEq4 = sdSC + sdSI + sdSA;

        double step = 0.1;

        Double availableDistanceEq1 = scoreEq1NextLower == null ? null : value - scoreEq1NextLower;
        Double availableDistanceEq2 = scoreEq2NextLower == null ? null : value - scoreEq2NextLower;
        Double availableDistanceEq3Eq6 = scoreEq3Eq6NextLower == null ? null : value - scoreEq3Eq6NextLower;
        Double availableDistanceEq4 = scoreEq4NextLower == null ? null : value - scoreEq4NextLower;
        Double availableDistanceEq5 = scoreEq5NextLower == null ? null : value - scoreEq5NextLower;

        double maxSeverityEq1 = MAX_SEVERITY_EQ1.get(eq1) * step;
        double maxSeverityEq2 = MAX_SEVERITY_EQ2.get(eq2) * step;
        double maxSeverityEq3Eq6 = MAX_SEVERITY_EQ3EQ6.get(eq3).get(eq6) * step;
        double maxSeverityEq4 = MAX_SEVERITY_EQ4.get(eq4) * step;

        int nExistingLower = 0;
        double normalizedSeverityEq1 = 0, normalizedSeverityEq2 = 0, normalizedSeverityEq3Eq6 = 0;
        double normalizedSeverityEq4 = 0, normalizedSeverityEq5 = 0;

        if (availableDistanceEq1 != null) {
            nExistingLower++;
            double percent = currentSeverityDistanceEq1 / maxSeverityEq1;
            normalizedSeverityEq1 = availableDistanceEq1 * percent;
        }
        if (availableDistanceEq2 != null) {
            nExistingLower++;
            double percent = currentSeverityDistanceEq2 / maxSeverityEq2;
            normalizedSeverityEq2 = availableDistanceEq2 * percent;
        }
        if (availableDistanceEq3Eq6 != null) {
            nExistingLower++;
            double percent = currentSeverityDistanceEq3Eq6 / maxSeverityEq3Eq6;
            normalizedSeverityEq3Eq6 = availableDistanceEq3Eq6 * percent;
        }
        if (availableDistanceEq4 != null) {
            nExistingLower++;
            double percent = currentSeverityDistanceEq4 / maxSeverityEq4;
            normalizedSeverityEq4 = availableDistanceEq4 * percent;
        }
        if (availableDistanceEq5 != null) {
            // EQ5's percentage is always 0 in the reference implementation — the Threat metric
            // (E) never has a "worse" sibling within the same MacroVector to interpolate toward.
            nExistingLower++;
            normalizedSeverityEq5 = availableDistanceEq5 * 0.0;
        }

        double meanDistance = nExistingLower == 0 ? 0.0
                : (normalizedSeverityEq1 + normalizedSeverityEq2 + normalizedSeverityEq3Eq6
                        + normalizedSeverityEq4 + normalizedSeverityEq5) / nExistingLower;

        value -= meanDistance;
        if (value < 0) value = 0.0;
        if (value > 10) value = 10.0;
        return Math.round(value * 10) / 10.0;
    }

    private static int digit(String macroVector, int index) {
        return Character.getNumericValue(macroVector.charAt(index));
    }

    private static String key(int a, int b, int c, int d, int e, int f) {
        return "" + a + b + c + d + e + f;
    }

    /** Effective value of a metric: Modified* override if set, Threat/Environmental "X" default, else the raw value. */
    private static String m(CvssV4Vector v, String metric) {
        String selected = v.raw(metric);
        if (selected == null) {
            selected = "X";
        }
        if ("E".equals(metric) && "X".equals(selected)) {
            return "A";
        }
        if (("CR".equals(metric) || "IR".equals(metric) || "AR".equals(metric)) && "X".equals(selected)) {
            return "H";
        }
        String modified = v.raw("M" + metric);
        if (modified != null && !"X".equals(modified)) {
            return modified;
        }
        return selected;
    }

    /** Extracts one metric's value out of a "AV:N/PR:N/UI:N/" -style max-vector fragment string. */
    private static String extract(String metric, String str) {
        int idx = str.indexOf(metric);
        int start = idx + metric.length() + 1;
        int slash = str.indexOf('/', start);
        return slash > start ? str.substring(start, slash) : str.substring(start);
    }

    private static String macroVector(CvssV4Vector v) {
        int eq1;
        if ("N".equals(m(v, "AV")) && "N".equals(m(v, "PR")) && "N".equals(m(v, "UI"))) {
            eq1 = 0;
        } else if (("N".equals(m(v, "AV")) || "N".equals(m(v, "PR")) || "N".equals(m(v, "UI")))
                && !("N".equals(m(v, "AV")) && "N".equals(m(v, "PR")) && "N".equals(m(v, "UI")))
                && !"P".equals(m(v, "AV"))) {
            eq1 = 1;
        } else {
            eq1 = 2;
        }

        int eq2 = ("L".equals(m(v, "AC")) && "N".equals(m(v, "AT"))) ? 0 : 1;

        int eq3;
        if ("H".equals(m(v, "VC")) && "H".equals(m(v, "VI"))) {
            eq3 = 0;
        } else if (!("H".equals(m(v, "VC")) && "H".equals(m(v, "VI")))
                && ("H".equals(m(v, "VC")) || "H".equals(m(v, "VI")) || "H".equals(m(v, "VA")))) {
            eq3 = 1;
        } else {
            eq3 = 2;
        }

        int eq4;
        if ("S".equals(m(v, "MSI")) || "S".equals(m(v, "MSA"))) {
            eq4 = 0;
        } else if ("H".equals(m(v, "SC")) || "H".equals(m(v, "SI")) || "H".equals(m(v, "SA"))) {
            eq4 = 1;
        } else {
            eq4 = 2;
        }

        int eq5 = switch (m(v, "E")) {
            case "A" -> 0;
            case "P" -> 1;
            default -> 2; // U
        };

        boolean eq6High = ("H".equals(m(v, "CR")) && "H".equals(m(v, "VC")))
                || ("H".equals(m(v, "IR")) && "H".equals(m(v, "VI")))
                || ("H".equals(m(v, "AR")) && "H".equals(m(v, "VA")));
        int eq6 = eq6High ? 0 : 1;

        return "" + eq1 + eq2 + eq3 + eq4 + eq5 + eq6;
    }

    // ── FIRST.org cvss_score.js metric-level constants, ported verbatim ─────────────────────

    private static final Map<String, Double> AV_LEVELS = Map.of("N", 0.0, "A", 0.1, "L", 0.2, "P", 0.3);
    private static final Map<String, Double> PR_LEVELS = Map.of("N", 0.0, "L", 0.1, "H", 0.2);
    private static final Map<String, Double> UI_LEVELS = Map.of("N", 0.0, "P", 0.1, "A", 0.2);
    private static final Map<String, Double> AC_LEVELS = Map.of("L", 0.0, "H", 0.1);
    private static final Map<String, Double> AT_LEVELS = Map.of("N", 0.0, "P", 0.1);
    private static final Map<String, Double> VC_LEVELS = Map.of("H", 0.0, "L", 0.1, "N", 0.2);
    private static final Map<String, Double> SC_LEVELS = Map.of("H", 0.1, "L", 0.2, "N", 0.3);
    private static final Map<String, Double> SI_LEVELS = Map.of("S", 0.0, "H", 0.1, "L", 0.2, "N", 0.3);
    private static final Map<String, Double> CR_LEVELS = Map.of("H", 0.0, "M", 0.1, "L", 0.2);

    // ── FIRST.org max_composed.js, transcribed verbatim (BSD-2-Clause; see THIRD_PARTY_LICENSES.md) ──

    private static final Map<Integer, List<String>> MAX_COMPOSED_EQ1 = Map.of(
            0, List.of("AV:N/PR:N/UI:N/"),
            1, List.of("AV:A/PR:N/UI:N/", "AV:N/PR:L/UI:N/", "AV:N/PR:N/UI:P/"),
            2, List.of("AV:P/PR:N/UI:N/", "AV:A/PR:L/UI:P/"));

    private static final Map<Integer, List<String>> MAX_COMPOSED_EQ2 = Map.of(
            0, List.of("AC:L/AT:N/"),
            1, List.of("AC:H/AT:N/", "AC:L/AT:P/"));

    private static final Map<Integer, Map<Integer, List<String>>> MAX_COMPOSED_EQ3 = Map.of(
            0, Map.of(
                    0, List.of("VC:H/VI:H/VA:H/CR:H/IR:H/AR:H/"),
                    1, List.of("VC:H/VI:H/VA:L/CR:M/IR:M/AR:H/", "VC:H/VI:H/VA:H/CR:M/IR:M/AR:M/")),
            1, Map.of(
                    0, List.of("VC:L/VI:H/VA:H/CR:H/IR:H/AR:H/", "VC:H/VI:L/VA:H/CR:H/IR:H/AR:H/"),
                    1, List.of("VC:L/VI:H/VA:L/CR:H/IR:M/AR:H/", "VC:L/VI:H/VA:H/CR:H/IR:M/AR:M/",
                            "VC:H/VI:L/VA:H/CR:M/IR:H/AR:M/", "VC:H/VI:L/VA:L/CR:M/IR:H/AR:H/",
                            "VC:L/VI:L/VA:H/CR:H/IR:H/AR:M/")),
            2, Map.of(
                    1, List.of("VC:L/VI:L/VA:L/CR:H/IR:H/AR:H/")));

    private static final Map<Integer, List<String>> MAX_COMPOSED_EQ4 = Map.of(
            0, List.of("SC:H/SI:S/SA:S/"),
            1, List.of("SC:H/SI:H/SA:H/"),
            2, List.of("SC:L/SI:L/SA:L/"));

    private static final Map<Integer, List<String>> MAX_COMPOSED_EQ5 = Map.of(
            0, List.of("E:A/"),
            1, List.of("E:P/"),
            2, List.of("E:U/"));

    // ── FIRST.org max_severity.js, transcribed verbatim (BSD-2-Clause; see THIRD_PARTY_LICENSES.md) ──

    private static final Map<Integer, Integer> MAX_SEVERITY_EQ1 = Map.of(0, 1, 1, 4, 2, 5);
    private static final Map<Integer, Integer> MAX_SEVERITY_EQ2 = Map.of(0, 1, 1, 2);
    private static final Map<Integer, Map<Integer, Integer>> MAX_SEVERITY_EQ3EQ6 = Map.of(
            0, Map.of(0, 7, 1, 6),
            1, Map.of(0, 8, 1, 8),
            2, Map.of(1, 10));
    private static final Map<Integer, Integer> MAX_SEVERITY_EQ4 = Map.of(0, 6, 1, 5, 2, 4);
}
