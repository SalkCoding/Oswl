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
 * Local-profile-only config that spins up an embedded GreenMail SMTP server
 * and auto-configures security_settings to point at it.
 *
 * <p>On startup:
 * <ul>
 *   <li>GreenMail listens on localhost:3025 (no auth).</li>
 *   <li>security_settings.mail_mode is set to SMTP with host=localhost, port=3025.</li>
 * </ul>
 * Received mails are printed to the log every 3 seconds so you can see the OTP
 * code (and HTML content) without any real mail client.
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
        log.info("║  All outgoing mail is captured here — no real SMTP. ║");
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
     * After Spring context is fully up, patch security_settings so the mail
     * server always points to GreenMail. This lets you enable Email OTP from
     * the Settings UI without manually entering SMTP details every time.
     */
    @Override
    public void run(ApplicationArguments args) {
        SecuritySetting s = settingRepository.findById(1L)
                .orElseGet(() -> SecuritySetting.builder().id(1L).build());

        s.setMailMode(MailMode.SMTP);
        s.setMailHost("localhost");
        s.setMailPort(SMTP_PORT);
        s.setMailEncryption("NONE");
        s.setMailUsername(null);
        s.setMailPassword(null);
        s.setMailSenderAddress("oswl-local@localhost");
        s.setMailSenderName("OsWL (local)");
        settingRepository.save(s);

        log.info("[LocalSMTP] security_settings patched → SMTP localhost:{} (no auth)", SMTP_PORT);
    }

    // ── Mail watcher ─────────────────────────────────────────────────────

    /**
     * Polls GreenMail every 3 s and logs any newly received messages.
     * The full raw message is printed so you can see Subject, To, and the
     * HTML body (including the OTP code).
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

                // Extract plain-text OTP from subject (e.g. "[OsWL] Your verification code: 123456")
                String subject = msgs[i].getSubject();
                if (subject != null && subject.contains("verification code:")) {
                    String code = subject.substring(subject.lastIndexOf(":") + 1).trim();
                    log.info("│");
                    log.info("│  *** OTP CODE: {}  ***", code);
                    log.info("│");
                }

                // Dump raw message for full HTML inspection
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                msgs[i].writeTo(baos);
                String raw = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
                // Truncate very long HTML bodies to keep log readable
                if (raw.length() > 3000) raw = raw.substring(0, 3000) + "\n... (truncated)";
                log.info("│  Raw message:\n{}", raw);
                log.info("└────────────────────────────────────────────────────────");
            } catch (Exception e) {
                log.warn("[LocalSMTP] Could not read message #{}: {}", i + 1, e.getMessage());
            }
        }
        lastSeenCount = msgs.length;
    }
}
