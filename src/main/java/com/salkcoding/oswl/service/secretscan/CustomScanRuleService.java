package com.salkcoding.oswl.service.secretscan;

import com.salkcoding.oswl.dto.scan.CustomRuleSet;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.re2j.Pattern;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.scan.CustomRuleConfiguration;
import com.salkcoding.oswl.dto.scan.CustomScanRule;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.scan.CustomRuleConfigurationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CustomScanRuleService {
    private final CustomRuleConfigurationRepository repository;
    private final AuditLogService audit;
    private final ObjectMapper mapper = new ObjectMapper();
    public record CompiledRule(CustomScanRule rule, Pattern pattern, long revision) {}

    @Transactional(readOnly = true)
    public CustomRuleSet read() { requireAdmin(); return current(); }

    @Transactional
    public CustomRuleSet publish(CustomRuleSet request) {
        requireAdmin();
        validate(request.rules());
        var existing = repository.findById(1L);
        long revision = existing.map(CustomRuleConfiguration::getRevision).orElse(-1L);
        if (request.revision() != revision) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Rule configuration changed; reload before publishing");
        try {
            String json = mapper.writeValueAsString(request.rules());
            var config = existing.orElseGet(() -> CustomRuleConfiguration.create(json));
            config.replace(json);
            var saved = repository.saveAndFlush(config);
            audit.log("SCAN_RULES.PUBLISH", "SCAN_RULES", "1", null, "revision=" + saved.getRevision() + ", rules=" + request.rules().size());
            return new CustomRuleSet(saved.getRevision(), List.copyOf(request.rules()));
        } catch (org.springframework.dao.OptimisticLockingFailureException | org.springframework.dao.DataIntegrityViolationException e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Rule configuration changed; reload before publishing");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new InvalidRequestException("Invalid rule configuration"); }
    }

    @Transactional(readOnly = true)
    public List<CompiledRule> compiled() {
        var set = current(); validate(set.rules());
        return set.rules().stream().map(r -> new CompiledRule(r, Pattern.compile(r.regex()), set.revision())).toList();
    }

    private CustomRuleSet current() {
        return repository.findById(1L).map(c -> {
            try { return new CustomRuleSet(c.getRevision(), mapper.readValue(c.getRulesJson(), new TypeReference<List<CustomScanRule>>() {})); }
            catch (Exception e) { throw new IllegalStateException("Stored custom rule configuration is invalid"); }
        }).orElse(new CustomRuleSet(-1, List.of()));
    }

    static void validate(List<CustomScanRule> rules) {
        if (rules == null || rules.size() > 32) throw new InvalidRequestException("At most 32 custom rules are allowed");
        Set<String> ids = new HashSet<>();
        for (var r : rules) {
            if (r == null || r.id() == null || !r.id().matches("[a-z0-9-]{1,60}") || !ids.add(r.id())
                    || r.type() == null || r.severity() == null || r.description() == null || r.description().isBlank()
                    || r.description().length() > 400 || r.regex() == null || r.regex().isBlank() || r.regex().length() > 256
                    || r.fileSuffix() == null || !r.fileSuffix().matches("[A-Za-z0-9_.-]{1,30}"))
                throw new InvalidRequestException("Invalid custom rule fields or duplicate rule id");
            try {
                Pattern pattern = Pattern.compile(r.regex());
                if (pattern.programSize() > 2048 || pattern.matcher("").find()) throw new IllegalArgumentException();
            } catch (Exception e) { throw new InvalidRequestException("Rule regex is invalid, too complex or matches empty text"); }
        }
    }

    private static void requireAdmin() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN")))
            throw new AccessDeniedException("System administrator required");
    }
}
