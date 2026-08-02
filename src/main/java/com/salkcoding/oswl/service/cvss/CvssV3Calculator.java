package com.salkcoding.oswl.service.cvss;

/**
 * CVSS v3.0/v3.1 Base and Environmental score calculator, implemented directly from the
 * FIRST.org specification (no external dependency, no lookup table — v3's formulas are
 * closed-form).
 *
 * <p><b>CVSS v4.0 is intentionally not supported here.</b> Unlike v3, v4.0 scoring is not a
 * closed-form formula — the official algorithm resolves a vector to a "MacroVector" and looks
 * up its score in a ~1,300-row table published by FIRST. Reproducing that table by hand risks
 * silently wrong scores, which is worse than not scoring at all for a security tool, and no
 * data source wired into OsWL currently supplies v4.0 vectors anyway (deps.dev, NVD, and GitHub
 * Advisory all report CVSS 3.x today). {@link CvssVectorVersion#detect} still recognizes v4.0
 * vectors so callers can distinguish "not a CVSS vector" from "CVSS v4.0, not yet scorable."
 *
 * <p>OsWL does not collect CVSS Temporal metrics (Exploit Code Maturity / Remediation Level /
 * Report Confidence) or the attacker-facing Modified* Base metrics from any data source, so the
 * Environmental score calculation only varies the three Security Requirement metrics
 * (Confidentiality/Integrity/Availability Requirement) — Modified* metrics are always equal to
 * the corresponding Base metric, and the three Temporal multipliers are always 1.0 ("Not
 * Defined"), exactly as CVSS itself defines a metric's neutral value.
 */
public final class CvssV3Calculator {

    private CvssV3Calculator() {
    }

    /** A CVSS Security Requirement (Confidentiality/Integrity/Availability Requirement) value. */
    public enum Requirement {
        LOW(0.5), MEDIUM(1.0), HIGH(1.5);

        private final double multiplier;

        Requirement(double multiplier) {
            this.multiplier = multiplier;
        }
    }

    /**
     * Computes the CVSS v3.0/v3.1 Base Score for a vector string.
     *
     * @return the base score (0.0–10.0), or {@code null} if {@code vector} is not a well-formed
     *         CVSS v3.0/v3.1 base vector
     */
    public static Double baseScore(String vector) {
        CvssV3Vector v = CvssV3Vector.parse(vector);
        if (v == null) {
            return null;
        }
        double impact = impact(iscBase(v), v.scopeChanged());
        double exploitability = exploitability(v);
        return combine(impact, exploitability, v.scopeChanged());
    }

    /**
     * Computes the CVSS v3.0/v3.1 Environmental Score for a vector string, varying only the
     * Security Requirement metrics (see class Javadoc for what's held at its neutral value).
     *
     * @return the environmental score (0.0–10.0), or {@code null} if {@code vector} is not a
     *         well-formed CVSS v3.0/v3.1 base vector
     */
    public static Double environmentalScore(String vector, Requirement cr, Requirement ir, Requirement ar) {
        CvssV3Vector v = CvssV3Vector.parse(vector);
        if (v == null) {
            return null;
        }
        double miss = Math.min(modifiedIscBase(v, cr, ir, ar), 0.915);
        double modifiedImpact = impact(miss, v.scopeChanged());
        double modifiedExploitability = exploitability(v);
        // The spec's full formula is roundup(roundup(min(...)) * E * RL * RC); the outer
        // roundup/multiplication is skipped here because the three Temporal multipliers
        // (Exploit Code Maturity / Remediation Level / Report Confidence) are always 1.0 —
        // see class Javadoc — making it a no-op on the already-rounded inner value.
        return combine(modifiedImpact, modifiedExploitability, v.scopeChanged());
    }

    // ── Shared v3.0/v3.1 formula (FIRST.org CVSS v3.1 Specification Document, §7–8) ──────────

    private static double iscBase(CvssV3Vector v) {
        double c = cia(v.c());
        double i = cia(v.i());
        double a = cia(v.a());
        return 1 - ((1 - c) * (1 - i) * (1 - a));
    }

    private static double modifiedIscBase(CvssV3Vector v, Requirement cr, Requirement ir, Requirement ar) {
        // No per-metric cap here — CIA's max weight (0.56) times a Requirement's max
        // multiplier (1.5) is 0.84, so (1 - MC*CR) etc. can never go negative on its own.
        // The spec applies a single cap of 0.915 to the combined result (done by the caller).
        double c = cia(v.c()) * cr.multiplier;
        double i = cia(v.i()) * ir.multiplier;
        double a = cia(v.a()) * ar.multiplier;
        return 1 - ((1 - c) * (1 - i) * (1 - a));
    }

    private static double impact(double iscBase, boolean scopeChanged) {
        if (iscBase <= 0) {
            return 0;
        }
        return scopeChanged
                ? 7.52 * (iscBase - 0.029) - 3.25 * Math.pow(iscBase - 0.02, 15)
                : 6.42 * iscBase;
    }

    private static double exploitability(CvssV3Vector v) {
        return 8.22 * av(v.av()) * ac(v.ac()) * pr(v.pr(), v.scopeChanged()) * ui(v.ui());
    }

    private static double combine(double impact, double exploitability, boolean scopeChanged) {
        if (impact <= 0) {
            return 0;
        }
        double sum = impact + exploitability;
        return scopeChanged ? roundUp(Math.min(1.08 * sum, 10)) : roundUp(Math.min(sum, 10));
    }

    /**
     * The CVSS spec's "round up to one decimal" — implemented on scaled integers, exactly as
     * the spec's own reference pseudocode does, to avoid binary floating-point rounding bugs
     * (e.g. a naive {@code Math.ceil(x * 10) / 10.0} misrounds values like 4.020 due to how
     * 4.02 is represented in double precision).
     */
    private static double roundUp(double input) {
        long scaled = Math.round(input * 100_000);
        if (scaled % 10_000 == 0) {
            return scaled / 100_000.0;
        }
        return (Math.floorDiv(scaled, 10_000) + 1) / 10.0;
    }

    // ── Metric → numeric weight tables (FIRST.org CVSS v3.1 Specification Document, §7.4) ───

    private static double av(String value) {
        return switch (value) {
            case "N" -> 0.85;
            case "A" -> 0.62;
            case "L" -> 0.55;
            case "P" -> 0.2;
            default -> throw new IllegalStateException("Unreachable — validated by CvssV3Vector");
        };
    }

    private static double ac(String value) {
        return "L".equals(value) ? 0.77 : 0.44;
    }

    private static double pr(String value, boolean scopeChanged) {
        return switch (value) {
            case "N" -> 0.85;
            case "L" -> scopeChanged ? 0.68 : 0.62;
            case "H" -> scopeChanged ? 0.50 : 0.27;
            default -> throw new IllegalStateException("Unreachable — validated by CvssV3Vector");
        };
    }

    private static double ui(String value) {
        return "N".equals(value) ? 0.85 : 0.62;
    }

    private static double cia(String value) {
        return switch (value) {
            case "N" -> 0.0;
            case "L" -> 0.22;
            case "H" -> 0.56;
            default -> throw new IllegalStateException("Unreachable — validated by CvssV3Vector");
        };
    }
}
