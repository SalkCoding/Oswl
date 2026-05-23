package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RoleTemplateBootstrapService {

    private final RoleTemplateRepository roleTemplateRepository;

    @Transactional
    public void ensureBuiltInTemplates() {
        upsert("Admin", "Full administrative permissions",
                EnumSet.allOf(Permission.class));

        upsert("Developer", "Project viewing/submission and analysis screen access",
                EnumSet.of(
                        Permission.PROJECT_VIEW,
                        Permission.PROJECT_CREATE,
                        Permission.SCAN_SUBMIT,
                        Permission.SCAN_VIEW,
                        Permission.SCAN_HISTORY_VIEW,
                        Permission.SECURITY_CENTER_VIEW,
                        Permission.SECURITY_CENTER_UPDATE_STATUS,
                        Permission.LICENSE_VIEW,
                        Permission.COMPONENT_DETAIL_VIEW,
                        Permission.VERSION_DIFF_VIEW,
                        Permission.RISK_TREND_VIEW,
                        Permission.SETTINGS_VCS_MANAGE,
                        Permission.SETTINGS_CLI_KEY_MANAGE
                ));

        upsert("Viewer", "Read-only",
                EnumSet.of(
                        Permission.PROJECT_VIEW,
                        Permission.SCAN_VIEW,
                        Permission.SCAN_HISTORY_VIEW,
                        Permission.SECURITY_CENTER_VIEW,
                        Permission.LICENSE_VIEW,
                        Permission.COMPONENT_DETAIL_VIEW,
                        Permission.VERSION_DIFF_VIEW,
                        Permission.RISK_TREND_VIEW
                ));
    }

    private void upsert(String name, String description, Set<Permission> permissions) {
        if (roleTemplateRepository.existsByName(name)) return;
        roleTemplateRepository.save(RoleTemplate.builder()
                .name(name)
                .description(description)
                .isBuiltIn(true)
                .permissions(EnumSet.copyOf(permissions))
                .build());
    }
}
