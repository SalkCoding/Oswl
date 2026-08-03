package com.salkcoding.oswl.service.scim;
import com.salkcoding.oswl.service.org.TeamService;

import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.org.Team;
import com.salkcoding.oswl.domain.entity.org.TeamMember;
import com.salkcoding.oswl.domain.enums.ScimGroupMapping;
import com.salkcoding.oswl.domain.enums.TeamMemberRole;
import com.salkcoding.oswl.dto.TeamSummaryDto;
import com.salkcoding.oswl.dto.scim.*;
import com.salkcoding.oswl.repository.org.TeamMemberRepository;
import com.salkcoding.oswl.repository.org.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SCIM 2.0 user and group provisioning service.
 *
 * SCIM groups are mapped either to {@link Team} or {@link RoleTemplate} based on
 * {@code oswl.scim.group-mapping}. Group membership changes are reflected immediately
 * in the chosen OsWL authorization model.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScimProvisioningService {

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    private static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    private static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";

    private final UserRepository userRepository;
    private final RoleTemplateRepository roleTemplateRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final TeamService teamService;
    private final AuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;

    @Value("${oswl.scim.group-mapping:TEAM}")
    private ScimGroupMapping groupMapping;

    // ── Users ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ScimUser getUser(Long id) {
        User user = findUserOrThrow(id);
        return toScimUser(user);
    }

    @Transactional(readOnly = true)
    public ScimListResponse<ScimUser> listUsers(String filter, int startIndex, int count) {
        List<User> users = filterUsers(filter);
        int total = users.size();
        int from = Math.max(0, startIndex - 1);
        int to = Math.min(users.size(), from + count);
        List<ScimUser> resources = users.subList(from, to).stream()
                .map(this::toScimUser)
                .collect(Collectors.toList());
        return ScimListResponse.<ScimUser>builder()
                .schemas(List.of(LIST_SCHEMA))
                .totalResults(total)
                .startIndex(startIndex)
                .itemsPerPage(resources.size())
                .resources(resources)
                .build();
    }

    @Transactional
    public ScimUser createUser(ScimUser request) {
        String email = normalizeEmail(request);
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("User already exists: " + email);
        }
        String displayName = request.getDisplayName() != null ? request.getDisplayName()
                : (request.getName() != null && request.getName().getFormatted() != null)
                        ? request.getName().getFormatted()
                        : email.substring(0, email.indexOf('@'));
        boolean active = request.getActive() != null && request.getActive();
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("scim-provisioned-" + System.currentTimeMillis()))
                .displayName(displayName)
                .isSystemAdmin(false)
                .enabled(active)
                .mustChangePassword(false)
                .build();
        User saved = userRepository.save(user);
        log.info("[SCIM] Created user id={} email='{}' active={}", saved.getId(), email, active);
        auditLogService.log("SCIM.USER_CREATE", "USER", saved.getId().toString(), email,
                "active=" + active);
        return toScimUser(saved);
    }

    @Transactional
    public ScimUser updateUser(Long id, ScimUser request) {
        User user = findUserOrThrow(id);
        String email = normalizeEmail(request);
        if (!email.equals(user.getEmail()) && userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already in use: " + email);
        }
        user.setEmail(email);
        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        } else if (request.getName() != null && request.getName().getFormatted() != null) {
            user.setDisplayName(request.getName().getFormatted());
        }
        if (request.getActive() != null) {
            user.setEnabled(request.getActive());
        }
        log.info("[SCIM] Updated user id={} email='{}'", user.getId(), email);
        auditLogService.log("SCIM.USER_UPDATE", "USER", id.toString(), email, "active=" + user.isEnabled());
        return toScimUser(user);
    }

    @Transactional
    public ScimUser patchUser(Long id, ScimPatchRequest request) {
        User user = findUserOrThrow(id);
        for (ScimPatchOperation op : request.getOperations()) {
            applyUserPatch(user, op);
        }
        log.info("[SCIM] Patched user id={} active={}", id, user.isEnabled());
        auditLogService.log("SCIM.USER_UPDATE", "USER", id.toString(), user.getEmail(), "patched");
        return toScimUser(user);
    }

    @Transactional
    public void deactivateUser(Long id) {
        User user = findUserOrThrow(id);
        if (user.isSystemAdmin()) {
            throw new IllegalStateException("System administrator cannot be deactivated via SCIM.");
        }
        user.setEnabled(false);
        log.info("[SCIM] Deactivated user id={} email='{}'", id, user.getEmail());
        auditLogService.log("SCIM.USER_DEACTIVATE", "USER", id.toString(), user.getEmail(), "source=SCIM");
    }

    // ── Groups ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ScimGroup getGroup(Long id) {
        if (groupMapping == ScimGroupMapping.ROLE_TEMPLATE) {
            RoleTemplate template = roleTemplateRepository.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("Group not found: " + id));
            return toScimGroup(template);
        }
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Group not found: " + id));
        return toScimGroup(team);
    }

    @Transactional(readOnly = true)
    public ScimListResponse<ScimGroup> listGroups() {
        List<ScimGroup> resources;
        if (groupMapping == ScimGroupMapping.ROLE_TEMPLATE) {
            resources = roleTemplateRepository.findAll().stream()
                    .map(this::toScimGroup)
                    .collect(Collectors.toList());
        } else {
            resources = teamRepository.findAllByOrderByNameAsc().stream()
                    .map(this::toScimGroup)
                    .collect(Collectors.toList());
        }
        return ScimListResponse.<ScimGroup>builder()
                .schemas(List.of(LIST_SCHEMA))
                .totalResults(resources.size())
                .startIndex(1)
                .itemsPerPage(resources.size())
                .resources(resources)
                .build();
    }

    @Transactional
    public ScimGroup createGroup(ScimGroup request) {
        String name = request.getDisplayName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group displayName is required.");
        }
        if (groupMapping == ScimGroupMapping.ROLE_TEMPLATE) {
            if (roleTemplateRepository.existsByName(name)) {
                throw new IllegalArgumentException("Role template already exists: " + name);
            }
            RoleTemplate template = RoleTemplate.builder()
                    .name(name)
                    .description("SCIM-provisioned role template")
                    .isBuiltIn(false)
                    .permissions(EnumSet.noneOf(Permission.class))
                    .build();
            RoleTemplate saved = roleTemplateRepository.save(template);
            syncRoleTemplateMembers(saved, request.getMembers());
            log.info("[SCIM] Created role-template group id={} name='{}'", saved.getId(), name);
            auditLogService.log("SCIM.GROUP_CREATE", "ROLE_TEMPLATE", saved.getId().toString(), name, "source=SCIM");
            return toScimGroup(saved);
        }
        TeamSummaryDto created = teamService.createTeam(name, "SCIM-provisioned team", null);
        Team team = teamRepository.findById(created.getId())
                .orElseThrow(() -> new IllegalStateException("Created team not found: " + created.getId()));
        syncTeamMembers(team, request.getMembers());
        log.info("[SCIM] Created team group id={} name='{}'", team.getId(), name);
        auditLogService.log("SCIM.GROUP_CREATE", "TEAM", team.getId().toString(), name, "source=SCIM");
        return toScimGroup(team);
    }

    @Transactional
    public ScimGroup updateGroup(Long id, ScimGroup request) {
        if (groupMapping == ScimGroupMapping.ROLE_TEMPLATE) {
            RoleTemplate template = roleTemplateRepository.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("Group not found: " + id));
            if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
                template.setName(request.getDisplayName());
            }
            syncRoleTemplateMembers(template, request.getMembers());
            log.info("[SCIM] Updated role-template group id={}", id);
            auditLogService.log("SCIM.GROUP_UPDATE", "ROLE_TEMPLATE", id.toString(), template.getName(), "source=SCIM");
            return toScimGroup(template);
        }
        Team team = teamRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Group not found: " + id));
        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()) {
            teamService.updateTeam(id, request.getDisplayName(), team.getDescription(), null);
            team = teamRepository.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("Group not found: " + id));
        }
        syncTeamMembers(team, request.getMembers());
        log.info("[SCIM] Updated team group id={}", id);
        auditLogService.log("SCIM.GROUP_UPDATE", "TEAM", id.toString(), team.getName(), "source=SCIM");
        return toScimGroup(team);
    }

    @Transactional
    public ScimGroup patchGroup(Long id, ScimPatchRequest request) {
        ScimGroup current = getGroup(id);
        for (ScimPatchOperation op : request.getOperations()) {
            if ("replace".equalsIgnoreCase(op.getOp()) && "members".equals(op.getPath())) {
                current.setMembers(parseMembers(op.getValue()));
            } else if ("add".equalsIgnoreCase(op.getOp()) && op.getPath() == null && op.getValue() instanceof Map<?, ?> map) {
                Object members = map.get("members");
                if (members instanceof List<?> list) {
                    List<ScimMember> additions = parseMembers(list);
                    List<ScimMember> merged = new ArrayList<>(current.getMembers() != null ? current.getMembers() : List.of());
                    merged.addAll(additions);
                    current.setMembers(merged);
                }
            }
        }
        return updateGroup(id, current);
    }

    @Transactional
    public void deleteGroup(Long id) {
        if (groupMapping == ScimGroupMapping.ROLE_TEMPLATE) {
            RoleTemplate template = roleTemplateRepository.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("Group not found: " + id));
            if (template.isBuiltIn()) {
                throw new IllegalStateException("Built-in role templates cannot be deleted via SCIM.");
            }
            roleTemplateRepository.delete(template);
            log.info("[SCIM] Deleted role-template group id={} name='{}'", id, template.getName());
            auditLogService.log("SCIM.GROUP_DELETE", "ROLE_TEMPLATE", id.toString(), template.getName(), "source=SCIM");
        } else {
            Team team = teamRepository.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("Group not found: " + id));
            teamService.deleteTeam(id);
            log.info("[SCIM] Deleted team group id={} name='{}'", id, team.getName());
            auditLogService.log("SCIM.GROUP_DELETE", "TEAM", id.toString(), team.getName(), "source=SCIM");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User findUserOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
    }

    private String normalizeEmail(ScimUser request) {
        String userName = request.getUserName();
        if (userName == null || userName.isBlank()) {
            if (request.getEmails() != null && !request.getEmails().isEmpty()) {
                userName = request.getEmails().get(0).getValue();
            }
        }
        if (userName == null || userName.isBlank() || !userName.contains("@")) {
            throw new IllegalArgumentException("userName or email is required.");
        }
        return userName.trim().toLowerCase();
    }

    private List<User> filterUsers(String filter) {
        List<User> all = userRepository.findAll();
        if (filter == null || filter.isBlank()) {
            return all;
        }
        // Support simple filter: userName eq "value"
        String lower = filter.toLowerCase();
        if (lower.startsWith("username eq ")) {
            String value = stripQuotes(filter.substring("username eq ".length()).trim());
            return all.stream()
                    .filter(u -> u.getEmail().equalsIgnoreCase(value))
                    .collect(Collectors.toList());
        }
        return all;
    }

    private String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void applyUserPatch(User user, ScimPatchOperation op) {
        if (!"replace".equalsIgnoreCase(op.getOp())) {
            return;
        }
        if ("active".equals(op.getPath())) {
            setUserActive(user, op.getValue());
        } else if (op.getPath() == null && op.getValue() instanceof Map<?, ?> map) {
            Object active = map.get("active");
            if (active != null) {
                setUserActive(user, active);
            }
        }
    }

    private void setUserActive(User user, Object value) {
        if (value instanceof Boolean b) {
            user.setEnabled(b);
        } else if (value instanceof String s) {
            user.setEnabled(Boolean.parseBoolean(s));
        }
    }

    private void syncTeamMembers(Team team, List<ScimMember> members) {
        if (members == null) {
            return;
        }
        Set<Long> desiredUserIds = members.stream()
                .map(m -> parseUserId(m.getValue()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<TeamMember> existing = teamMemberRepository.findByTeamIdOrderByCreatedAtAsc(team.getId());
        Set<Long> existingUserIds = existing.stream().map(TeamMember::getUserId).collect(Collectors.toSet());

        for (Long userId : desiredUserIds) {
            if (!existingUserIds.contains(userId)) {
                teamMemberRepository.save(TeamMember.builder()
                        .team(team)
                        .userId(userId)
                        .role(TeamMemberRole.MEMBER)
                        .build());
                auditLogService.log("SCIM.GROUP_MEMBER_ADD", "TEAM", team.getId().toString(),
                        team.getName(), "userId=" + userId);
            }
        }
        for (TeamMember member : existing) {
            if (!desiredUserIds.contains(member.getUserId())) {
                teamMemberRepository.delete(member);
                auditLogService.log("SCIM.GROUP_MEMBER_REMOVE", "TEAM", team.getId().toString(),
                        team.getName(), "userId=" + member.getUserId());
            }
        }
    }

    private void syncRoleTemplateMembers(RoleTemplate template, List<ScimMember> members) {
        if (members == null) {
            return;
        }
        Set<Long> desiredUserIds = members.stream()
                .map(m -> parseUserId(m.getValue()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<User> existing = roleTemplateRepository.findUserIdsByTemplateId(template.getId()).stream()
                .map(userRepository::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
        Set<Long> existingUserIds = existing.stream().map(User::getId).collect(Collectors.toSet());

        for (Long userId : desiredUserIds) {
            if (!existingUserIds.contains(userId)) {
                User user = userRepository.findById(userId)
                        .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
                user.getRoleTemplates().add(template);
                auditLogService.log("SCIM.GROUP_MEMBER_ADD", "ROLE_TEMPLATE", template.getId().toString(),
                        template.getName(), "userId=" + userId);
            }
        }
        for (User user : existing) {
            if (!desiredUserIds.contains(user.getId())) {
                user.getRoleTemplates().remove(template);
                auditLogService.log("SCIM.GROUP_MEMBER_REMOVE", "ROLE_TEMPLATE", template.getId().toString(),
                        template.getName(), "userId=" + user.getId());
            }
        }
    }

    private Long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<ScimMember> parseMembers(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(m -> m instanceof Map)
                    .map(m -> (Map<String, Object>) m)
                    .map(m -> ScimMember.builder()
                            .value(String.valueOf(m.get("value")))
                            .display(m.get("display") != null ? String.valueOf(m.get("display")) : null)
                            .build())
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private ScimUser toScimUser(User user) {
        return ScimUser.builder()
                .id(user.getId().toString())
                .userName(user.getEmail())
                .displayName(user.getDisplayName())
                .active(user.isEnabled())
                .emails(List.of(ScimEmail.builder()
                        .value(user.getEmail())
                        .type("work")
                        .primary(true)
                        .build()))
                .schemas(List.of(USER_SCHEMA))
                .meta(ScimMeta.builder()
                        .resourceType("User")
                        .created(formatInstant(user.getCreatedAt()))
                        .lastModified(formatInstant(user.getUpdatedAt()))
                        .location("/scim/v2/Users/" + user.getId())
                        .build())
                .build();
    }

    private ScimGroup toScimGroup(Team team) {
        List<TeamMember> members = teamMemberRepository.findByTeamIdOrderByCreatedAtAsc(team.getId());
        List<ScimMember> scimMembers = members.stream()
                .map(m -> ScimMember.builder()
                        .value(m.getUserId().toString())
                        .type("User")
                        .build())
                .collect(Collectors.toList());
        return ScimGroup.builder()
                .id(team.getId().toString())
                .displayName(team.getName())
                .members(scimMembers)
                .schemas(List.of(GROUP_SCHEMA))
                .meta(ScimMeta.builder()
                        .resourceType("Group")
                        .created(formatInstant(team.getCreatedAt()))
                        .location("/scim/v2/Groups/" + team.getId())
                        .build())
                .build();
    }

    private ScimGroup toScimGroup(RoleTemplate template) {
        List<Long> userIds = roleTemplateRepository.findUserIdsByTemplateId(template.getId());
        List<ScimMember> scimMembers = userIds.stream()
                .map(id -> ScimMember.builder()
                        .value(id.toString())
                        .type("User")
                        .build())
                .collect(Collectors.toList());
        return ScimGroup.builder()
                .id(template.getId().toString())
                .displayName(template.getName())
                .members(scimMembers)
                .schemas(List.of(GROUP_SCHEMA))
                .meta(ScimMeta.builder()
                        .resourceType("Group")
                        .created(formatInstant(template.getCreatedAt()))
                        .lastModified(formatInstant(template.getUpdatedAt()))
                        .location("/scim/v2/Groups/" + template.getId())
                        .build())
                .build();
    }

    private String formatInstant(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
    }
}
