package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.MailTestRequest;
import com.salkcoding.oswl.auth.dto.SecuritySettingResponse;
import com.salkcoding.oswl.auth.dto.SecuritySettingUpdateRequest;
import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.enums.MailMode;
import com.salkcoding.oswl.auth.enums.TwoFaMode;
import com.salkcoding.oswl.auth.repository.SecuritySettingRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Properties;

@Service
@RequiredArgsConstructor
public class SecuritySettingService {

    private static final long SETTINGS_ID = 1L;

    private final SecuritySettingRepository repository;
    private final EncryptionService encryptionService;

    // ── 읽기 ─────────────────────────────────────────────────────────────

    @Transactional
    public SecuritySetting getOrCreate() {
        return repository.findById(SETTINGS_ID)
                .orElseGet(() -> repository.save(
                        SecuritySetting.builder().id(SETTINGS_ID).build()));
    }

    // ── 쓰기 ────────────────────────────────────────────────────────────

    @Transactional
    public SecuritySetting update(SecuritySettingUpdateRequest req) {
        SecuritySetting s = getOrCreate();

        if (req.getMailMode() != null) {
            s.setMailMode(MailMode.valueOf(req.getMailMode()));
        }

        SecuritySettingUpdateRequest.MailDto m = req.getMail();
        if (m != null) {
            if (m.getHost() != null)          s.setMailHost(m.getHost());
            if (m.getPort() != null)          s.setMailPort(m.getPort());
            if (m.getEncryption() != null)    s.setMailEncryption(m.getEncryption());
            if (m.getUsername() != null)      s.setMailUsername(m.getUsername());
            if (m.getSenderName() != null)    s.setMailSenderName(m.getSenderName());
            if (m.getSenderAddress() != null) s.setMailSenderAddress(m.getSenderAddress());
            // 저장 전에 새 비밀번호를 암호화; 비어 있으면 기존 비밀번호 유지
            if (m.getPassword() != null && !m.getPassword().isBlank()) {
                s.setMailPassword(encryptionService.encrypt(m.getPassword()));
            }
        }

        if (req.getTwoFaMode() != null) {
            s.setTwoFaMode(TwoFaMode.valueOf(req.getTwoFaMode()));
        }

        return repository.save(s);
    }

    // ── 메일 연결 테스트 ──────────────────────────────────────────────

    /**
     * 제공된 파라미터를 사용하여 SMTP 세션을 여는 것을 시도한다.
     * 연결에 실패하면 {@link MessagingException}을 던진다.
     */
    public void testMailConnection(MailTestRequest req) throws MessagingException {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(req.getHost() != null ? req.getHost() : "");
        sender.setPort(req.getPort() != null ? req.getPort() : 587);

        if (req.getUsername() != null) sender.setUsername(req.getUsername());
        if (req.getPassword() != null) sender.setPassword(req.getPassword());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout",           "5000");
        props.put("mail.smtp.writetimeout",      "5000");

        String enc = req.getEncryption();
        if ("STARTTLS".equalsIgnoreCase(enc)) {
            props.put("mail.smtp.auth",               "true");
            props.put("mail.smtp.starttls.enable",    "true");
            props.put("mail.smtp.starttls.required",  "true");
        } else if ("SSL_TLS".equalsIgnoreCase(enc)) {
            sender.setProtocol("smtps");
            props.put("mail.smtp.auth",               "true");
            props.put("mail.smtp.ssl.enable",         "true");
        } else {
            // NONE — 인증 없는 릴레이 허용
            props.put("mail.smtp.auth", req.getUsername() != null && !req.getUsername().isBlank() ? "true" : "false");
        }

        sender.testConnection();
    }

    // ── 응답 매핑 ──────────────────────────────────────────────────

    public SecuritySettingResponse toResponse(SecuritySetting s) {
        return SecuritySettingResponse.builder()
                .mailMode(s.getMailMode().name())
                .mail(SecuritySettingResponse.MailDto.builder()
                        .host(s.getMailHost())
                        .port(s.getMailPort())
                        .encryption(s.getMailEncryption())
                        .username(s.getMailUsername())
                        .senderName(s.getMailSenderName())
                        .senderAddress(s.getMailSenderAddress())
                        .build())
                .twoFaMode(s.getTwoFaMode().name())
                .minPasswordLength(s.getMinPasswordLength())
                .build();
    }
}
