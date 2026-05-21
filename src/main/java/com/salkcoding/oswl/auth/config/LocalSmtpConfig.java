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
 * 로컈 프로파일에서만 실행되는 설정으로, 임베디드 GreenMail SMTP 서버를 기동하고
 * security_settings가 해당 서버를 가리키도록 자동 설정한다.
 *
 * <p>기동 시:
 * <ul>
 *   <li>GreenMail은 localhost:3025에서 대기한다 (인증 없음).</li>
 *   <li>security_settings.mail_mode가 SMTP(host=localhost, port=3025)로 설정된다.</li>
 * </ul>
 * 수신된 메일은 3초마다 로그에 출력되며 실제 메일 클라이언트 없이도 OTP 코드를 확인할 수 있다.
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

    // ── 라이프사이클 ─────────────────────────────────────────────────────────

    @Bean
    public GreenMail greenMail() {
        ServerSetup setup = new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);
        setup.setServerStartupTimeout(5_000);
        greenMail = new GreenMail(setup);
        greenMail.start();
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║  [LocalSMTP] GreenMail started on localhost:{}      ║", SMTP_PORT);
        log.info("║  모든 송신 메일은 여기서 가로채집됩니다 — 실제 SMTP 미사용. ║");
        log.info("╚══════════════════════════════════════════════════════╝");
        return greenMail;
    }

    @PreDestroy
    public void stopGreenMail() {
        if (greenMail != null) {
            greenMail.stop();
            log.info("[LocalSMTP] GreenMail 정지.");
        }
    }

    /**
     * Spring 컨텍스트가 완전히 실행된 후 security_settings가 GreenMail을 가리키도록 패치한다.
     * Settings UI에서 SMTP 세부정보를 매번 입력하지 않아도 Email OTP를 활성화할 수 있다.
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

        log.info("[LocalSMTP] security_settings 패치 → SMTP localhost:{} (인증 없음)", SMTP_PORT);
    }

    // ── 메일 감시 ─────────────────────────────────────────────────────

    /**
     * 3초마다 GreenMail을 폴링하여 새로 수신된 메일을 로깅한다.
     * 전체 원시 메시지가 출력되도록 제목근, 수신자, HTML 본문(OTP 코드 포함)이 도시된다.
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

                // 제목에서 OTP 코드 추출 (예: "[OsWL] Your verification code: 123456")
                String subject = msgs[i].getSubject();
                if (subject != null && subject.contains("verification code:")) {
                    String code = subject.substring(subject.lastIndexOf(":") + 1).trim();
                    log.info("│");
                    log.info("│  *** OTP CODE: {}  ***", code);
                    log.info("│");
                }

                // 로그 가독성을 위해 매우 긴 HTML 본문을 잘라냄
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                msgs[i].writeTo(baos);
                String raw = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
                // 로그 가독성을 위해 너무 긴 HTML 본문 잘라냄
                if (raw.length() > 3000) raw = raw.substring(0, 3000) + "\n... (truncated)";
                log.info("│  Raw message:\n{}", raw);
                log.info("└────────────────────────────────────────────────────────");
            } catch (Exception e) {
                log.warn("[LocalSMTP] 메시지 #{} 읽기 실패: {}", i + 1, e.getMessage());
            }
        }
        lastSeenCount = msgs.length;
    }
}
