package com.salkcoding.oswl.domain.entity.scan;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "custom_scan_rules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomRuleConfiguration {
    @Id private Long id;
    @Version private Long revision;
    @Column(name = "rules_json", nullable = false, columnDefinition = "TEXT")
    private String rulesJson;
    public static CustomRuleConfiguration create(String json) {
        var config = new CustomRuleConfiguration(); config.id = 1L; config.rulesJson = json; return config;
    }
    public void replace(String json) { rulesJson = json; }
}
