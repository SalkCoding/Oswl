package com.salkcoding.oswl.vdb;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * FIRST.org EPSS bulk scores CSV (gzip), fetched in full (~350k rows as of 2026) — not scoped to
 * a wanted-list; this is the practical alternative to the query API used by
 * the live {@code EpssClient}, which is per-CVE and unsuitable for a bulk builder.
 *
 * <p><b>Reconfirmed at implementation time</b> (the plan's own instruction — feeds move):
 * {@code https://epss.empiricalsecurity.com/epss_scores-current.csv.gz} 302-redirects to a
 * dated file; format is {@code #model_version:...,score_date:<ISO8601>} then a
 * {@code cve,epss,percentile} header then data rows.
 */
final class EpssSource {

    private static final String EPSS_BULK_URL = "https://epss.empiricalsecurity.com/epss_scores-current.csv.gz";

    record Result(Map<String, Double> scores, LocalDate asOf) {}

    Result fetch(HttpCache cache) throws IOException, InterruptedException {
        byte[] gz = cache.getOrFetch("epss_scores-current.csv.gz", EPSS_BULK_URL);
        Map<String, Double> scores = new LinkedHashMap<>();
        LocalDate asOf = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(gz)), StandardCharsets.UTF_8))) {
            String line;
            boolean headerSeen = false;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (line.startsWith("#")) {
                    asOf = parseScoreDate(line, asOf);
                    continue;
                }
                if (!headerSeen) {
                    if (!line.strip().equals("cve,epss,percentile")) throw new IOException("Unexpected EPSS CSV header");
                    headerSeen = true;
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length != 3) throw new IOException("Malformed EPSS row; source coverage is unknown");
                try {
                    double score = Double.parseDouble(parts[1].strip());
                    if (!Double.isFinite(score) || score < 0 || score > 1)
                        throw new NumberFormatException("Invalid EPSS probability");
                    scores.put(parts[0].strip().toUpperCase(Locale.ROOT), score);
                } catch (NumberFormatException invalid) {
                    throw new IOException("Invalid EPSS score; source coverage is unknown", invalid);
                }
            }
        }
        if (asOf == null || scores.isEmpty()) throw new IOException("EPSS source has no dated score data");
        return new Result(scores, asOf);
    }

    private static LocalDate parseScoreDate(String commentLine, LocalDate previous) throws IOException {
        int idx = commentLine.indexOf("score_date:");
        if (idx < 0) return previous;
        String rest = commentLine.substring(idx + "score_date:".length());
        int end = rest.indexOf(',');
        String dateStr = (end >= 0 ? rest.substring(0, end) : rest).strip();
        try {
            LocalDate date = dateStr.length() == 10 ? LocalDate.parse(dateStr)
                    : java.time.OffsetDateTime.parse(dateStr).toLocalDate();
            if (date.isAfter(LocalDate.now()) || (previous != null && !previous.equals(date)))
                throw new IOException("Future or conflicting EPSS score date");
            return date;
        } catch (java.time.DateTimeException e) {
            throw new IOException("Invalid EPSS score date", e);
        }
    }
}
