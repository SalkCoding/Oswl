package com.salkcoding.oswl.dto.scan;
import java.util.List;
public record CustomRuleSet(long revision, List<CustomScanRule> rules) {}
