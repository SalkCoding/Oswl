package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.demo.DemoImportCatalog;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectMemberRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.service.ApiKeyService;
import com.salkcoding.oswl.service.QuickImportService;
import com.salkcoding.oswl.auth.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * Development-only endpoints ({@code src/local/java} — not in production bootJar).
 *
 * <p>{@code GET /data/test} clears existing scan data and queues Quick Import jobs for every
 * public demo repository in {@link DemoImportCatalog} (real clone + parse + scan pipeline).
 */
@Slf4j
@Controller
@RequestMapping("/data")
@RequiredArgsConstructor
@Profile({"local", "test"})
public class TestDataController {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final LibraryRepository libraryRepository;
    private final QuickImportService quickImportService;
    private final ApiKeyService apiKeyService;
    private final MailService mailService;
    private final UserRepository userRepository;

    @GetMapping("/mail-preview")
    @ResponseBody
    public ResponseEntity<String> mailPreview(
            @RequestParam(name = "name", defaultValue = "test") String name,
            @RequestParam(name = "ai", defaultValue = "true") boolean ai) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(mailService.buildOtpEmailPreview(name, ai));
    }

    @GetMapping("/test-api-key")
    @Transactional
    @ResponseBody
    public ResponseEntity<String> issueTestApiKey() {
        return projectRepository.findAll().stream().findFirst()
                .map(p -> {
                    var key = apiKeyService.issue(p.getId(), "cli-qa", null).plainToken();
                    return ResponseEntity.ok(
                            "projectId=" + p.getId() + "\n" +
                            "projectName=" + p.getName() + "\n" +
                            "token=" + key + "\n");
                })
                .orElseGet(() -> ResponseEntity.status(404)
                        .body("No project yet. Run GET /data/test and wait for imports to finish.\n"));
    }

    @GetMapping("/test")
    @Transactional
    public String seedDemoImports() {
        Long userId = resolveDemoUserId();
        clearScanData();
        List<String> jobIds = quickImportService.startBatchImport(DemoImportCatalog.repoUrls(), userId);
        log.info("[DemoImport] Queued {} Quick Import jobs for userId={}", jobIds.size(), userId);
        return "redirect:/projects";
    }

    private void clearScanData() {
        projectMemberRepository.deleteAll();
        projectMemberRepository.flush();
        List<Project> projects = projectRepository.findAll();
        projectRepository.deleteAll(projects);
        projectRepository.flush();
        libraryRepository.deleteAll();
        libraryRepository.flush();
    }

    private Long resolveDemoUserId() {
        return userRepository.findAll().stream()
                .filter(User::isSystemAdmin)
                .findFirst()
                .or(() -> userRepository.findAll().stream().findFirst())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "No users in database. Complete /setup first, then call GET /data/test again."));
    }
}
