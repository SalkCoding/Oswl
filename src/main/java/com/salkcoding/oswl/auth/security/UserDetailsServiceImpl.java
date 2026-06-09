package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        Set<GrantedAuthority> authorities = new HashSet<>();
        if (user.isSystemAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
        }
        user.getRoleTemplates().forEach(rt ->
                rt.getPermissions().forEach(p ->
                        authorities.add(new SimpleGrantedAuthority("PERMISSION_" + p.name()))
                )
        );

        return new OswlUserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.getDisplayName(),
                user.isSystemAdmin(),
                user.isEnabled(),
                authorities,
                user.getRoleTemplates().stream().map(rt -> rt.getId()).collect(java.util.stream.Collectors.toSet()),
                collectPermissions(user),
                user.isMustChangePassword()
        );
    }

    private Set<Permission> collectPermissions(User user) {
        Set<Permission> all = new HashSet<>();
        user.getRoleTemplates().forEach(rt -> all.addAll(rt.getPermissions()));
        return all;
    }
}
