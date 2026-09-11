package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;

/** Source withdrawal state; malformed metadata must not establish a clean lookup. */
public enum OsvWithdrawal {
    ACTIVE, WITHDRAWN, UNKNOWN;

    public static OsvWithdrawal from(JsonNode advisory) {
        if (advisory == null || !advisory.isObject()) return UNKNOWN;
        if (!advisory.has("withdrawn")) return ACTIVE;
        JsonNode value = advisory.path("withdrawn");
        if (!value.isTextual() || !value.asText().endsWith("Z")) return UNKNOWN;
        return OsvRevision.isCurrent(value.asText()) ? WITHDRAWN : UNKNOWN;
    }
}
