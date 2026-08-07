package com.salkcoding.oswl.service.iacscan;

import com.salkcoding.oswl.domain.enums.RiskLevel;

import java.util.regex.Pattern;

/** One line-matched IaC misconfiguration rule, scoped to a single file kind. */
record IacRule(String id, String description, RiskLevel severity, IacTargetKind targetKind, Pattern pattern) {
}
