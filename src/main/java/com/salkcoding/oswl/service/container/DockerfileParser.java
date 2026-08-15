package com.salkcoding.oswl.service.container;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a {@code Dockerfile} for OS-package vulnerability inventory.
 *
 * <p><b>Scope, deliberately kept narrow:</b> a Dockerfile alone cannot tell you the actual
 * installed-package inventory of an image — that depends on the base image's own layers, which
 * this parser never inspects (that would require pulling/extracting the image, out of scope
 * here). What a Dockerfile <em>can</em> tell you reliably is:
 * <ol>
 *   <li>the base image's distro + version, from the final {@code FROM} instruction, and</li>
 *   <li>any package explicitly installed with a <b>pinned version</b> in a {@code RUN} instruction
 *       (e.g. {@code apt-get install -y openssl=1.1.1n-0+deb11u4}).</li>
 * </ol>
 * Packages installed without a pinned version are skipped rather than guessed at, and unrecognized
 * base images are skipped rather than inventoried with an unknown/wrong distro — both would
 * otherwise silently misreport results, the exact class of bug was written to fix.
 */
@Slf4j
public final class DockerfileParser {

    private static final Pattern FROM = Pattern.compile(
            "^\\s*FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)(?:\\s+[Aa][Ss]\\s+(\\S+))?\\s*$");
    private static final Pattern RUN = Pattern.compile("^\\s*RUN\\s+(.*)$", Pattern.DOTALL);

    /** {@code apt-get install ...<pkg>=<version>...} / {@code apt install ...} */
    private static final Pattern APT_INSTALL = Pattern.compile(
            "\\bapt(?:-get)?\\s+(?:-\\S+\\s+)*install\\b");
    /** {@code apk add ...<pkg>=<version>...} */
    private static final Pattern APK_ADD = Pattern.compile("\\bapk\\s+(?:--\\S+\\s+)*add\\b");
    /** A single {@code name=version} token (apt and apk both use this syntax for pinning). */
    private static final Pattern PINNED_PACKAGE = Pattern.compile("^([a-zA-Z0-9][a-zA-Z0-9+.-]*)=([a-zA-Z0-9:.+~_-]+)$");

    public DockerfileParser() {
    }

    /**
     * @param content    raw Dockerfile text
     * @param repoName   for logging only
     * @return components for every explicitly version-pinned OS package found, or an empty list
     *         if the base image isn't a recognized distro release or no pinned packages were found
     */
    public List<ScanPayload.ComponentPayload> parse(String content, String repoName) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String ecosystem = resolveBaseImageEcosystem(content, repoName);
        if (ecosystem == null) {
            return List.of();
        }

        List<ScanPayload.ComponentPayload> components = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : joinLineContinuations(content)) {
            Matcher runMatcher = RUN.matcher(line);
            if (!runMatcher.matches()) {
                continue;
            }
            String instruction = runMatcher.group(1);
            if (APT_INSTALL.matcher(instruction).find() || APK_ADD.matcher(instruction).find()) {
                for (String token : instruction.split("\\s+")) {
                    Matcher pinned = PINNED_PACKAGE.matcher(token.replaceAll("[,\\\\]+$", ""));
                    if (pinned.matches() && seen.add(pinned.group(1))) {
                        components.add(ScanPayload.ComponentPayload.create(
                                pinned.group(1), pinned.group(2), ecosystem, "Direct (Dockerfile)", List.of()));
                    }
                }
            }
        }

        if (!components.isEmpty()) {
            log.info("[DockerfileParser] '{}': base image ecosystem={} pinned OS package(s)={}",
                    repoName, ecosystem, components.size());
        }
        return components;
    }

    /**
     * Resolves the ecosystem of the image that actually ships: the last {@code FROM} instruction
     * whose image isn't a reference to an earlier build stage (multi-stage builds name their
     * final stage's base image last, by Docker convention).
     */
    private String resolveBaseImageEcosystem(String content, String repoName) {
        Set<String> stageAliases = new HashSet<>();
        String lastExternalImage = null;

        for (String line : joinLineContinuations(content)) {
            Matcher m = FROM.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String image = m.group(1);
            String alias = m.group(2);
            if (!stageAliases.contains(image.toLowerCase(java.util.Locale.ROOT))) {
                lastExternalImage = image;
            }
            if (alias != null) {
                stageAliases.add(alias.toLowerCase(java.util.Locale.ROOT));
            }
        }

        if (lastExternalImage == null) {
            return null;
        }
        String[] repoAndTag = splitRepoTag(lastExternalImage);
        String ecosystem = BaseImageEcosystemMapper.map(repoAndTag[0], repoAndTag[1]);
        if (ecosystem == null) {
            log.debug("[DockerfileParser] '{}': base image '{}' is not a recognized, version-pinned "
                    + "distro release — skipping OS package inventory for this Dockerfile",
                    repoName, lastExternalImage);
        }
        return ecosystem;
    }

    /** Splits {@code repo:tag} (or {@code repo} with no tag) — ignores a digest suffix if present. */
    private static String[] splitRepoTag(String image) {
        String withoutDigest = image.split("@", 2)[0];
        int colon = withoutDigest.lastIndexOf(':');
        // A colon before the last '/' is a registry port (e.g. localhost:5000/debian), not a tag.
        int lastSlash = withoutDigest.lastIndexOf('/');
        if (colon > lastSlash) {
            return new String[] {withoutDigest.substring(0, colon), withoutDigest.substring(colon + 1)};
        }
        return new String[] {withoutDigest, null};
    }

    /** Joins {@code \}-terminated line continuations so multi-line RUN instructions parse as one line. */
    private static List<String> joinLineContinuations(String content) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.stripTrailing();
            if (line.endsWith("\\")) {
                current.append(line, 0, line.length() - 1).append(' ');
            } else {
                current.append(line);
                lines.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }
}
