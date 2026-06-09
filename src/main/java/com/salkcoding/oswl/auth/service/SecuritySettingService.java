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

    // ── Read ────────────────────────────────────────────────────────────

    @Transactional
    public SecuritySetting getOrCreate() {
        return repository.findById(SETTINGS_ID)
                .orElseGet(() -> repository.save(
                        SecuritySetting.builder().id(SETTINGS_ID).build()));
    }

    // ── Write ───────────────────────────────────────────────────────────

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
            // Encrypt the new password before saving; keep the existing password if blank
            if (m.getPassword() != null && !m.getPassword().isBlank()) {
                s.setMailPassword(encryptionService.encrypt(m.getPassword()));
            }
        }

        if (req.getTwoFaMode() != null) {
            s.setTwoFaMode(TwoFaMode.valueOf(req.getTwoFaMode()));
        }

        return repository.save(s);
    }

    // ── Mail connection test ───────────────────────────────────────────

    /**
     * Attempts to open an SMTP session using the provided parameters.
     * Throws {@link MessagingException} if the connection fails.
     */
    public void testMailConnection(MailTestRequest req) throws MessagingException {
        MailTestRequest effective = enrichMailTestRequest(req);
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(effective.getHost() != null ? effective.getHost() : "");
        sender.setPort(effective.getPort() != null ? effective.getPort() : 587);

        if (effective.getUsername() != null) sender.setUsername(effective.getUsername());
        if (effective.getPassword() != null) sender.setPassword(effective.getPassword());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout",           "5000");
        props.put("mail.smtp.writetimeout",      "5000");

        String enc = effective.getEncryption();
        if ("STARTTLS".equalsIgnoreCase(enc)) {
            props.put("mail.smtp.auth",               "true");
            props.put("mail.smtp.starttls.enable",    "true");
            props.put("mail.smtp.starttls.required",  "true");
        } else if ("SSL_TLS".equalsIgnoreCase(enc)) {
            sender.setProtocol("smtps");
            props.put("mail.smtp.auth",               "true");
            props.put("mail.smtp.ssl.enable",         "true");
        } else {
            // NONE — allow unauthenticated relay
            props.put("mail.smtp.auth", effective.getUsername() != null && !effective.getUsername().isBlank()
                    ? "true" : "false");
        }

        sender.testConnection();
    }

    /**
     * UI does not reload SMTP passwords after save; merge host/port/username from the request
     * and use the stored encrypted password when the test form leaves password blank.
     */
    private MailTestRequest enrichMailTestRequest(MailTestRequest req) {
        if (req.getPassword() != null && !req.getPassword().isBlank()) {
            return req;
        }
        SecuritySetting stored = repository.findById(SETTINGS_ID).orElse(null);
        if (stored == null || stored.getMailPassword() == null || stored.getMailPassword().isBlank()) {
            return req;
        }
        try {
            MailTestRequest merged = new MailTestRequest();
            merged.setHost(req.getHost() != null ? req.getHost() : stored.getMailHost());
            merged.setPort(req.getPort() != null ? req.getPort() : stored.getMailPort());
            merged.setEncryption(req.getEncryption() != null ? req.getEncryption() : stored.getMailEncryption());
            merged.setUsername(req.getUsername() != null ? req.getUsername() : stored.getMailUsername());
            merged.setPassword(encryptionService.decrypt(stored.getMailPassword()));
            merged.setSenderName(req.getSenderName());
            merged.setSenderAddress(req.getSenderAddress());
            return merged;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "SMTP password could not be decrypted. Re-save mail settings in Security.", e);
        }
    }

    // ── Response mapping ─────────────────────────────────────────────

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
