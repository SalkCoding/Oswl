package com.salkcoding.oswl.service.secretscan;

import com.salkcoding.oswl.domain.enums.RiskLevel;

import java.util.regex.Pattern;

/** One compiled entry of {@code secretscan/secret-rules.json}. */
public record SecretRule(String id, String description, RiskLevel severity, Pattern pattern) {

    /** Jackson-deserializable shape of a rule as it appears in the JSON resource, pre-compilation. */
    public record RawRule(String id, String description, String severity, String regex) {
        SecretRule compile() {
            return new SecretRule(id, description, RiskLevel.valueOf(severity.toUpperCase()), Pattern.compile(regex));
        }
    }
}
