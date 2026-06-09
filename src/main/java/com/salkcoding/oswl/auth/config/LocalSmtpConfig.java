package com.salkcoding.oswl.auth.config;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.enums.MailMode;
import com.salkcoding.oswl.auth.repository.SecuritySettingRepository;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.ByteArrayOutputStream;

/**
 * Configuration that runs only in the local profile, starts an embedded GreenMail SMTP server,
 * and automatically points security_settings to that server.
 *
 * <p>On startup:
 * <ul>
 *   <li>GreenMail listens on localhost:3025 (no authentication).</li>
 *   <li>security_settings.mail_mode is set to SMTP(host=localhost, port=3025).</li>
 * </ul>
 * Received mail is logged every 3 seconds so OTP codes can be checked without a real mail client.
 */
@Slf4j
@Profile("local")
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class LocalSmtpConfig implements ApplicationRunner {

    static final int SMTP_PORT = 3025;

    private final SecuritySettingRepository settingRepository;

    private GreenMail greenMail;
    private int lastSeenCount = 0;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Bean
    public GreenMail greenMail() {
        ServerSetup setup = new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);
        setup.setServerStartupTimeout(5_000);
        greenMail = new GreenMail(setup);
        greenMail.start();
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║  [LocalSMTP] GreenMail started on localhost:{}      ║", SMTP_PORT);
        log.info("║  All outbound mail is intercepted here — no real SMTP used. ║");
        log.info("╚══════════════════════════════════════════════════════╝");
        return greenMail;
    }

    @PreDestroy
    public void stopGreenMail() {
        if (greenMail != null) {
            greenMail.stop();
            log.info("[LocalSMTP] GreenMail stopped.");
        }
    }

    /**
     * Patches security_settings to point at GreenMail after the Spring context has fully started.
     * Skipped if an external SMTP host is already configured (allows testing real SMTP in local profile).
     */
    @Override
    public void run(ApplicationArguments args) {
        SecuritySetting s = settingRepository.findById(1L)
                .orElseGet(() -> SecuritySetting.builder().id(1L).build());

        String existingHost = s.getMailHost();
        if (existingHost != null && !existingHost.isBlank() && !existingHost.equals("localhost")) {
            log.info("[LocalSMTP] External SMTP host '{}' already configured — skipping GreenMail patch.", existingHost);
            return;
        }

        s.setMailMode(MailMode.SMTP);
        s.setMailHost("localhost");
        s.setMailPort(SMTP_PORT);
        s.setMailEncryption("NONE");
        s.setMailUsername(null);
        s.setMailPassword(null);
        s.setMailSenderAddress("oswl-local@localhost");
        s.setMailSenderName("OsWL (local)");
        settingRepository.save(s);

        log.info("[LocalSMTP] Patched security_settings → SMTP localhost:{} (no authentication)", SMTP_PORT);
    }

    // ── Mail monitoring ───────────────────────────────────────────────

    /**
     * Polls GreenMail every 3 seconds and logs newly received mail.
     * Subject, recipients, and HTML body (including OTP code) are shown so the full raw message is visible.
     */
    @Scheduled(fixedDelay = 3_000)
    public void printNewMails() {
        if (greenMail == null) return;
        MimeMessage[] msgs = greenMail.getReceivedMessages();
        if (msgs.length <= lastSeenCount) return;

        for (int i = lastSeenCount; i < msgs.length; i++) {
            try {
                log.info("┌─ [LocalSMTP] New mail #{} ─────────────────────────────", i + 1);
                log.info("│  Subject : {}", msgs[i].getSubject());
                log.info("│  To      : {}", msgs[i].getAllRecipients() != null
                        ? java.util.Arrays.toString(msgs[i].getAllRecipients()) : "(none)");
                log.info("│  From    : {}", msgs[i].getFrom() != null
                        ? java.util.Arrays.toString(msgs[i].getFrom()) : "(none)");

                // Extract OTP code from subject (example: "[OsWL] Your verification code: 123456")
                String subject = msgs[i].getSubject();
                if (subject != null && subject.contains("verification code:")) {
                    String code = subject.substring(subject.lastIndexOf(":") + 1).trim();
                    log.info("│");
                    log.info("│  *** OTP CODE: {}  ***", code);
                    log.info("│");
                }

                // Trim very long HTML bodies for log readability
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                msgs[i].writeTo(baos);
                String raw = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
                // Trim overly long HTML bodies for log readability
                if (raw.length() > 3000) raw = raw.substring(0, 3000) + "\n... (truncated)";
                log.info("│  Raw message:\n{}", raw);
                log.info("└────────────────────────────────────────────────────────");
            } catch (Exception e) {
                log.warn("[LocalSMTP] Failed to read message #{}: {}", i + 1, e.getMessage());
            }
        }
        lastSeenCount = msgs.length;
    }
}
