package com.salkcoding.oswl.uitest;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for headless-browser UI/accessibility tests (ROADMAP v1.0.5.1 — V1).
 *
 * <p>Boots the real application on a random port and drives it with headless Chromium via
 * Playwright, giving these tests things curl-based smoke checks structurally cannot see:
 * whether Alpine.js actually initializes, whether a click fires the expected fetch/DOM update,
 * and whether keyboard-only navigation and axe-core accessibility audits pass on the real
 * rendered DOM.
 *
 * <p>Runs against an isolated in-memory H2 (profile {@code uitest}, see
 * {@code application-uitest.yaml}) — never the developer's file-based local dev database.
 * Two-factor auth is left at its fresh-instance default ({@code TwoFaMode.DISABLED}), so the
 * seeded admin logs in through the real {@code /login} form without an OTP step.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("uitest")
public abstract class UiTestBase {

    protected static final String TEST_EMAIL = "test@test.com";
    protected static final String TEST_PASSWORD = "1q2w3e4r";
    protected static final String TEST_DISPLAY_NAME = "test";

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static Playwright playwright;

    protected Browser browser;
    protected BrowserContext context;
    protected Page page;

    @BeforeAll
    static void launchPlaywright() {
        playwright = Playwright.create();
    }

    @AfterAll
    static void closePlaywright() {
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
    }

    @BeforeEach
    void setUpBrowserAndAdmin() {
        ensureTestAdmin();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void tearDownBrowser() {
        if (context != null) {
            context.close();
        }
        if (browser != null) {
            browser.close();
        }
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected String url(String path) {
        return baseUrl() + path;
    }

    /**
     * Logs in through the real /login form. Assumes TwoFaMode.DISABLED (fresh-instance default).
     * A directly-seeded admin (never having been through /setup) lands on /onboarding rather than
     * /projects — {@link LoginCompletionService#resolvePostLoginDestination} sends any system admin
     * there until the onboarding wizard is marked complete — so this only waits for navigation away
     * from /login; callers that need /projects specifically should navigate there afterward.
     */
    protected void loginAsTestAdmin() {
        page.navigate(url("/login"));
        page.fill("#login-email", TEST_EMAIL);
        page.fill("#login-password", TEST_PASSWORD);
        page.click("button[type=submit]");
        page.waitForURL(navigatedUrl -> !navigatedUrl.contains("/login"));
    }

    /** Runs an axe-core accessibility scan of the current page. */
    protected AxeResults runAxeScan() {
        return new AxeBuilder(page).analyze();
    }

    private void ensureTestAdmin() {
        if (userRepository.existsByEmail(TEST_EMAIL)) {
            return;
        }
        userRepository.save(User.builder()
                .email(TEST_EMAIL)
                .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                .displayName(TEST_DISPLAY_NAME)
                .isSystemAdmin(true)
                .enabled(true)
                .build());
    }
}
