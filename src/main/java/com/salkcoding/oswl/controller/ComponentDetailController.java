package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.controller.spec.ComponentDetailControllerSpec;
import com.salkcoding.oswl.dto.CreatePrRequest;
import com.salkcoding.oswl.dto.DeferralRequest;
import com.salkcoding.oswl.service.ComponentDetailService;
import com.salkcoding.oswl.service.SessionCipherService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@RequestMapping("/projects/{projectId}/components/{componentId}")
@PreAuthorize("hasPermission(null, 'COMPONENT_DETAIL_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class ComponentDetailController implements ComponentDetailControllerSpec {

    private static final String SESSION_GITHUB_TOKENS = "githubTokens";

    private final ComponentDetailService componentDetailService;
    private final SessionCipherService   sessionCipher;

    @GetMapping
    public String detail(@PathVariable Long projectId,
                         @PathVariable Long componentId,
                         Model model,
                         HttpServletRequest request) {
        componentDetailService.populateModel(projectId, componentId, model);

        if ("true".equals(request.getHeader("HX-Request"))) {
            return "component-detail/fragments/detail-content :: content";
        }
        return "component-detail/index";
    }

    @PostMapping("/defer")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'SECURITY_CENTER_UPDATE_STATUS') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> defer(@PathVariable Long projectId,
                                      @PathVariable Long componentId,
                                      @RequestBody DeferralRequest req) {
        componentDetailService.defer(projectId, componentId, req);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/create-pr")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'SECURITY_CENTER_UPDATE_STATUS') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Object>> createPr(@PathVariable Long projectId,
                                                         @PathVariable Long componentId,
                                                         @RequestBody CreatePrRequest req,
                                                         HttpSession session,
                                                         @AuthenticationPrincipal OswlUserPrincipal principal) {
        // GitHub token comes from session; GitLab/Bitbucket tokens are resolved from DB in the service
        String githubToken = getDecryptedGithubToken(session);
        Long userId = principal != null ? principal.getUserId() : null;
        try {
            Map<String, Object> result = componentDetailService.createPullRequest(
                    projectId, componentId, req, userId, githubToken);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "VCS error: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private String getDecryptedGithubToken(HttpSession session) {
        Object obj = session.getAttribute(SESSION_GITHUB_TOKENS);
        if (!(obj instanceof Map<?, ?> tokens) || tokens.isEmpty()) return null;
        String encrypted = ((Map<String, String>) tokens).values().iterator().next();
        return sessionCipher.decrypt(encrypted);
    }
}
