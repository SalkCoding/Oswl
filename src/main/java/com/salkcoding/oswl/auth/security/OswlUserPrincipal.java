package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.enums.Permission;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Set;

/**
 * Custom UserDetails carrying OsWL-specific identity data
 * (userId, displayName, isSuperAdmin, permissions).
 */
@Getter
public class OswlUserPrincipal extends User {

    private final Long userId;
    private final String displayName;
    private final boolean superAdmin;
    private final Set<Long> roleTemplateIds;
    private final Set<Permission> permissions;

    public OswlUserPrincipal(Long userId,
                             String email,
                             String passwordHash,
                             String displayName,
                             boolean superAdmin,
                             boolean enabled,
                             Collection<? extends GrantedAuthority> authorities,
                             Set<Long> roleTemplateIds,
                             Set<Permission> permissions) {
        super(email, passwordHash, enabled, true, true, true, authorities);
        this.userId = userId;
        this.displayName = displayName;
        this.superAdmin = superAdmin;
        this.roleTemplateIds = roleTemplateIds;
        this.permissions = permissions;
    }

    public boolean hasPermission(Permission permission) {
        return superAdmin || permissions.contains(permission);
    }
}
