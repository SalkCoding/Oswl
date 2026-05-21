package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.enums.MailMode;
import com.salkcoding.oswl.auth.security.EncryptionService;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Properties;

/**
 * 저장된 SecuritySetting에서 동적 JavaMailSender를 생성하고
 * 트랜잭션 이메일(OTP 코드, 알림)을 전송한다.
 *
 * SecuritySetting에 저장된 메일 비밀번호는 AES-256-GCM으로 암호화되어 있으며;
 * 이 서비스는 사용 전에 복호화하며 외부에 노입하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final SecuritySettingService securitySettingService;
    private final EncryptionService encryptionService;
    private final TemplateEngine templateEngine;

    @Value("classpath:static/icon/icon-logo.svg")
    private Resource logoSvgResource;
    private String logoDataUri = "";

    @PostConstruct
    void init() {
        try {
            byte[] bytes = logoSvgResource.getInputStream().readAllBytes();
            logoDataUri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(bytes);
            log.debug("[Mail] 로고 SVG 로드 완료 ({} bytes)", bytes.length);
        } catch (Exception e) {
            log.warn("[Mail] 로고 SVG 로드 실패 — 이메일 헤더에 로고가 표시되지 않습니다: {}", e.getMessage());
        }
    }

    // ── 공개 API ────────────────────────────────────────────────────────

    /**
     * 주어진 주소로 6자리 OTP 이메일을 전송한다.
     *
     * @param toAddress   수신자 이메일
     * @param displayName 수신자 표시 이름 (인사말에 사용)
     * @param otp         6자리 OTP 코드
     * @throws RuntimeException 메일이 DISABLED이거나 SMTP 전송이 실패한 경우
     */
    public void sendOtp(String toAddress, String displayName, String otp) {
        SecuritySetting settings = securitySettingService.getOrCreate();

        if (settings.getMailMode() == MailMode.DISABLED) {
            log.warn("[Mail] SecuritySetting에서 메일 DISABLED — '{}'에 OTP 전송 안 됨. " +
                     "Settings → Security에서 SMTP를 활성화하세요.", toAddress);
            return;
        }

        if (settings.getMailHost() == null || settings.getMailHost().isBlank()) {
            log.error("[Mail] SMTP 호스트 미설정 — '{}'에 OTP 전송 불가.", toAddress);
            throw new IllegalStateException("SMTP 호스트가 설정되지 않았습니다. Settings → Security에서 설정하세요.");
        }

        JavaMailSenderImpl sender = buildSender(settings);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String fromAddress = settings.getMailSenderAddress() != null
                    ? settings.getMailSenderAddress()
                    : settings.getMailUsername();
            String fromName = settings.getMailSenderName() != null
                    ? settings.getMailSenderName()
                    : "OsWL";

            helper.setFrom(fromAddress, fromName);
            helper.setTo(toAddress);
            helper.setSubject("[OsWL] Your verification code: " + otp);
            helper.setText(buildOtpHtml(displayName, otp, null), true);

            sender.send(message);
            log.info("[Mail] '{}'에 OTP 전송 완료", toAddress);
        } catch (MessagingException e) {
            log.error("[Mail] '{}'에 OTP 전송 실패: {}", toAddress, e.getMessage());
            throw new RuntimeException("인증 이메일 전송 실패: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[Mail] '{}'에 OTP 전송 중 예상치 못한 오류: {}", toAddress, e.getMessage());
            throw new RuntimeException("인증 이메일 전송 실패", e);
        }
    }

    // ── 비공개 헬퍼 ───────────────────────────────────────────────────

    private JavaMailSenderImpl buildSender(SecuritySetting s) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(s.getMailHost());
        sender.setPort(s.getMailPort() != null ? s.getMailPort() : 587);

        if (s.getMailUsername() != null) sender.setUsername(s.getMailUsername());

        if (s.getMailPassword() != null && !s.getMailPassword().isBlank()) {
            try {
                sender.setPassword(encryptionService.decrypt(s.getMailPassword()));
            } catch (Exception e) {
                log.warn("[Mail] 메일 비밀번호 복호화 실패; 평문텍스트로 시도합니다 (레거시 지원).");
                sender.setPassword(s.getMailPassword());
            }
        }

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        String enc = s.getMailEncryption();
        if ("STARTTLS".equalsIgnoreCase(enc)) {
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        } else if ("SSL_TLS".equalsIgnoreCase(enc)) {
            sender.setProtocol("smtps");
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            boolean hasAuth = s.getMailUsername() != null && !s.getMailUsername().isBlank();
            props.put("mail.smtp.auth", String.valueOf(hasAuth));
        }

        return sender;
    }

    private String buildOtpHtml(String displayName, String otp, String aiMessage) {
        String name = (displayName != null && !displayName.isBlank()) ? displayName : "User";
        Context ctx = new Context();
        ctx.setVariable("name", name);
        ctx.setVariable("otp", otp);
        ctx.setVariable("logoDataUri", logoDataUri);
        ctx.setVariable("aiMessage", aiMessage);
        return templateEngine.process("mail/otp", ctx);
    }

    /** 로컬 개발용 — OTP 이메일 템플릿을 더미 데이터로 렌더링해 반환한다. */
    public String buildOtpEmailPreview(String name, boolean withAi) {
        String ai = withAi
                ? "This sign-in request originates from a device and location consistent with your previous sessions."
                  + " No suspicious activity or anomalies were detected."
                  + " If this was not you, please change your password immediately."
                : null;
        return buildOtpHtml(name, "123456", ai);
    }
}
