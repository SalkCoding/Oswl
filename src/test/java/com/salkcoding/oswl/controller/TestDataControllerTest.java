package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.demo.DemoImportCatalog;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.repository.project.ProjectMemberRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.service.ingest.QuickImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("TestDataController demo Quick Import")
class TestDataControllerTest {

    @Autowired TestDataController testDataController;
    @Autowired ProjectRepository projectRepository;
    @Autowired LibraryRepository libraryRepository;
    @Autowired QuickImportService quickImportService;
    @Autowired UserRepository userRepository;
    @Autowired ProjectMemberRepository projectMemberRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void ensureSystemAdmin() {
        if (!userRepository.existsByIsSystemAdminTrue()) {
            userRepository.save(User.builder()
                    .email("admin@oswl.local")
                    .passwordHash(passwordEncoder.encode("Admin!Test1234"))
                    .displayName("Test Admin")
                    .isSystemAdmin(true)
                    .enabled(true)
                    .build());
        }
    }

    @Test
    @DisplayName("/data/test clears projects and queues 11 demo Quick Import jobs")
    void seedDemoImports_queuesJobs() {
        assertThat(DemoImportCatalog.REPOS).hasSize(11);

        String view = testDataController.seedDemoImports();

        assertThat(view).isEqualTo("redirect:/projects");
        assertThat(projectRepository.count()).isZero();
        assertThat(libraryRepository.count()).isZero();

        User admin = userRepository.findAll().stream().filter(User::isSystemAdmin).findFirst().orElseThrow();
        assertThat(quickImportService.listJobsForUser(admin.getId())).hasSize(11);
    }
}
