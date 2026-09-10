package com.salkcoding.oswl.service.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salkcoding.oswl.domain.enums.WebhookProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds Slack/Teams incoming-webhook payloads. Messages contain a short summary
 * plus a deep link back to OsWL when {@code oswl.public-url} is configured.
 */
@Component
@RequiredArgsConstructor
public class WebhookMessageBuilder {

    private static final int MAX_SUMMARY_LINES = 10;
    private static final int MAX_LINE_LENGTH = 300;

    private final ObjectMapper objectMapper;

    @Value("${oswl.public-url:}")
    private String publicUrl;

    /**
     * Builds a provider-specific JSON payload.
     */
    public String buildPayload(WebhookProvider provider, String title, List<String> summaryLines,
                               String deepLinkPath) {
        String deepLink = buildDeepLink(deepLinkPath);
        return switch (provider) {
            case SLACK -> buildSlackPayload(title, summaryLines, deepLink);
            case TEAMS -> buildTeamsPayload(title, summaryLines, deepLink);
        };
    }

    private String buildSlackPayload(String title, List<String> summaryLines, String deepLink) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("text", title);

        ArrayNode blocks = root.putArray("blocks");
        ObjectNode header = blocks.addObject();
        header.put("type", "header");
        ObjectNode headerText = header.putObject("text");
        headerText.put("type", "plain_text");
        headerText.put("text", title);
        headerText.put("emoji", true);

        StringBuilder body = new StringBuilder();
        int count = 0;
        for (String line : summaryLines) {
            if (count >= MAX_SUMMARY_LINES) {
                body.append("…");
                break;
            }
            String trimmed = line.length() > MAX_LINE_LENGTH
                    ? line.substring(0, MAX_LINE_LENGTH) + "…"
                    : line;
            body.append(trimmed).append("\n");
            count++;
        }

        ObjectNode section = blocks.addObject();
        section.put("type", "section");
        ObjectNode sectionText = section.putObject("text");
        sectionText.put("type", "mrkdwn");
        sectionText.put("text", body.toString().stripTrailing());

        if (deepLink != null && !deepLink.isBlank()) {
            ObjectNode actions = blocks.addObject();
            actions.put("type", "section");
            ObjectNode linkText = actions.putObject("text");
            linkText.put("type", "mrkdwn");
            linkText.put("text", "<" + deepLink + "|Open in OsWL>");
        }

        return root.toString();
    }

    private String buildTeamsPayload(String title, List<String> summaryLines, String deepLink) {
        ObjectNode card = objectMapper.createObjectNode();
        card.put("@type", "MessageCard");
        card.put("@context", "https://schema.org/extensions");
        card.put("themeColor", "D94638");
        card.put("summary", title);

        ArrayNode sections = card.putArray("sections");
        ObjectNode mainSection = sections.addObject();
        mainSection.put("activityTitle", title);

        StringBuilder facts = new StringBuilder();
        int count = 0;
        for (String line : summaryLines) {
            if (count >= MAX_SUMMARY_LINES) {
                facts.append("…");
                break;
            }
            String trimmed = line.length() > MAX_LINE_LENGTH
                    ? line.substring(0, MAX_LINE_LENGTH) + "…"
                    : line;
            facts.append(trimmed).append("\n\n");
            count++;
        }
        mainSection.put("text", facts.toString().stripTrailing());

        if (deepLink != null && !deepLink.isBlank()) {
            ArrayNode potentialAction = card.putArray("potentialAction");
            ObjectNode action = potentialAction.addObject();
            action.put("@type", "OpenUri");
            action.put("name", "Open in OsWL");
            ArrayNode targets = action.putArray("targets");
            ObjectNode target = targets.addObject();
            target.put("os", "default");
            target.put("uri", deepLink);
        }

        return card.toString();
    }

    private String buildDeepLink(String path) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return null;
        }
        String base = publicUrl.replaceAll("/+$", "");
        String p = path != null ? path : "";
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        return base + "/" + p;
    }
}
