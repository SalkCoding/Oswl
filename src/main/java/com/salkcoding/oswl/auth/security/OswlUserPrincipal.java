package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.enums.UserThemeMode;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.Set;

/**
 * Custom UserDetails carrying OsWL-specific identity data
 * (userId, displayName, isSystemAdmin, permissions).
 */
@Getter
public class OswlUserPrincipal extends User {

    private final Long userId;
    private final String displayName;
    private final boolean systemAdmin;
    private final Set<Long> roleTemplateIds;
    private final Set<Permission> permissions;
    private final boolean mustChangePassword;
    private final UserThemeMode theme;

    /**
     * Backward-compatible constructor; defaults the UI theme to SYSTEM so existing
     * call sites (including tests) keep compiling without changes.
     */
    public OswlUserPrincipal(Long userId,
                             String email,
                             String passwordHash,
                             String displayName,
                             boolean systemAdmin,
                             boolean enabled,
                             Collection<? extends GrantedAuthority> authorities,
                             Set<Long> roleTemplateIds,
                             Set<Permission> permissions,
                             boolean mustChangePassword) {
        this(userId, email, passwordHash, displayName, systemAdmin, enabled,
                authorities, roleTemplateIds, permissions, mustChangePassword, UserThemeMode.SYSTEM);
    }

    public OswlUserPrincipal(Long userId,
                             String email,
                             String passwordHash,
                             String displayName,
                             boolean systemAdmin,
                             boolean enabled,
                             Collection<? extends GrantedAuthority> authorities,
                             Set<Long> roleTemplateIds,
                             Set<Permission> permissions,
                             boolean mustChangePassword,
                             UserThemeMode theme) {
        super(email, passwordHash, enabled, true, true, true, authorities);
        this.userId = userId;
        this.displayName = displayName;
        this.systemAdmin = systemAdmin;
        this.roleTemplateIds = roleTemplateIds;
        this.permissions = permissions;
        this.mustChangePassword = mustChangePassword;
        this.theme = theme != null ? theme : UserThemeMode.SYSTEM;
    }

    public boolean hasPermission(Permission permission) {
        return systemAdmin || permissions.contains(permission);
    }
}
