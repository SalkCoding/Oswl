package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.SecuritySettingUpdateRequest;
import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.enums.MailMode;
import com.salkcoding.oswl.auth.enums.TwoFaMode;
import com.salkcoding.oswl.auth.repository.SecuritySettingRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecuritySettingService 단위 테스트")
class SecuritySettingServiceTest {

    @Mock SecuritySettingRepository repository;
    @Mock EncryptionService         encryptionService;

    @InjectMocks SecuritySettingService securitySettingService;

    // ── getOrCreate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrCreate: 기존 설정이 있으면 반환한다")
    void getOrCreate_existing_returnsExisting() {
        SecuritySetting existing = SecuritySetting.builder().id(1L).build();
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        SecuritySetting result = securitySettingService.getOrCreate();

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("getOrCreate: 설정이 없으면 새로 저장하고 반환한다")
    void getOrCreate_absent_createsNew() {
        SecuritySetting created = SecuritySetting.builder().id(1L).build();
        when(repository.findById(1L)).thenReturn(Optional.empty());
        when(repository.save(any(SecuritySetting.class))).thenReturn(created);

        SecuritySetting result = securitySettingService.getOrCreate();

        assertThat(result).isSameAs(created);
        verify(repository).save(any(SecuritySetting.class));
    }

    // ── update ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("update: mailMode가 SMTP로 변경된다")
    void update_mailMode_changedToSmtp() {
        SecuritySetting setting = SecuritySetting.builder().id(1L).build();
        when(repository.findById(1L)).thenReturn(Optional.of(setting));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SecuritySettingUpdateRequest req = new SecuritySettingUpdateRequest();
        req.setMailMode("SMTP");

        securitySettingService.update(req);

        assertThat(setting.getMailMode()).isEqualTo(MailMode.SMTP);
    }

    @Test
    @DisplayName("update: twoFaMode가 EMAIL_OTP로 변경된다")
    void update_twoFaMode_changedToEmailOtp() {
        SecuritySetting setting = SecuritySetting.builder().id(1L).build();
        when(repository.findById(1L)).thenReturn(Optional.of(setting));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SecuritySettingUpdateRequest req = new SecuritySettingUpdateRequest();
        req.setTwoFaMode("EMAIL_OTP");

        securitySettingService.update(req);

        assertThat(setting.getTwoFaMode()).isEqualTo(TwoFaMode.EMAIL_OTP);
    }

    @Test
    @DisplayName("update: mail DTO 값들이 설정에 반영된다")
    void update_mailDto_fieldsApplied() {
        SecuritySetting setting = SecuritySetting.builder().id(1L).build();
        when(repository.findById(1L)).thenReturn(Optional.of(setting));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SecuritySettingUpdateRequest.MailDto mail = new SecuritySettingUpdateRequest.MailDto();
        mail.setHost("smtp.example.com");
        mail.setPort(587);
        mail.setEncryption("STARTTLS");
        mail.setUsername("sender@example.com");
        mail.setSenderName("OsWL Alerts");
        mail.setSenderAddress("noreply@example.com");

        SecuritySettingUpdateRequest req = new SecuritySettingUpdateRequest();
        req.setMail(mail);

        securitySettingService.update(req);

        assertThat(setting.getMailHost()).isEqualTo("smtp.example.com");
        assertThat(setting.getMailPort()).isEqualTo(587);
        assertThat(setting.getMailEncryption()).isEqualTo("STARTTLS");
        assertThat(setting.getMailUsername()).isEqualTo("sender@example.com");
        assertThat(setting.getMailSenderName()).isEqualTo("OsWL Alerts");
        assertThat(setting.getMailSenderAddress()).isEqualTo("noreply@example.com");
    }

    @Test
    @DisplayName("update: 비밀번호가 제공되면 암호화되어 저장된다")
    void update_passwordProvided_isEncrypted() {
        SecuritySetting setting = SecuritySetting.builder().id(1L).build();
        when(repository.findById(1L)).thenReturn(Optional.of(setting));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt("plain-secret")).thenReturn("enc:encrypted");

        SecuritySettingUpdateRequest.MailDto mail = new SecuritySettingUpdateRequest.MailDto();
        mail.setPassword("plain-secret");

        SecuritySettingUpdateRequest req = new SecuritySettingUpdateRequest();
        req.setMail(mail);

        securitySettingService.update(req);

        assertThat(setting.getMailPassword()).isEqualTo("enc:encrypted");
        verify(encryptionService).encrypt("plain-secret");
    }

    @Test
    @DisplayName("update: 빈 비밀번호는 암호화하지 않는다")
    void update_blankPassword_notEncrypted() {
        SecuritySetting setting = SecuritySetting.builder().id(1L).build();
        when(repository.findById(1L)).thenReturn(Optional.of(setting));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SecuritySettingUpdateRequest.MailDto mail = new SecuritySettingUpdateRequest.MailDto();
        mail.setPassword("   ");  // blank

        SecuritySettingUpdateRequest req = new SecuritySettingUpdateRequest();
        req.setMail(mail);

        securitySettingService.update(req);

        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    @DisplayName("update: null 필드는 기존 값을 유지한다")
    void update_nullFields_keepsExistingValues() {
        SecuritySetting setting = SecuritySetting.builder()
                .id(1L)
                .mailHost("original.host")
                .build();
        when(repository.findById(1L)).thenReturn(Optional.of(setting));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Request with no mail DTO at all
        SecuritySettingUpdateRequest req = new SecuritySettingUpdateRequest();

        securitySettingService.update(req);

        assertThat(setting.getMailHost()).isEqualTo("original.host");
    }

    // ── toResponse ───────────────────────────────────────────────────────

    @Test
    @DisplayName("toResponse: SecuritySetting을 올바르게 DTO로 변환한다")
    void toResponse_mapsAllFields() {
        SecuritySetting s = SecuritySetting.builder()
                .id(1L)
                .mailMode(MailMode.SMTP)
                .mailHost("smtp.example.com")
                .mailPort(465)
                .mailEncryption("SSL_TLS")
                .mailUsername("user@example.com")
                .mailSenderName("OsWL")
                .mailSenderAddress("oswl@example.com")
                .twoFaMode(TwoFaMode.EMAIL_OTP)
                .minPasswordLength(12)
                .build();

        var response = securitySettingService.toResponse(s);

        assertThat(response.getMailMode()).isEqualTo("SMTP");
        assertThat(response.getMail().getHost()).isEqualTo("smtp.example.com");
        assertThat(response.getMail().getPort()).isEqualTo(465);
        assertThat(response.getTwoFaMode()).isEqualTo("EMAIL_OTP");
        assertThat(response.getMinPasswordLength()).isEqualTo(12);
    }

    // ── testMailConnection ────────────────────────────────────────────────

    @Test
    @DisplayName("testMailConnection: STARTTLS 설정 → 연결 실패 시 MessagingException 발생")
    void testMailConnection_starttls_throwsMessagingException() {
        com.salkcoding.oswl.auth.dto.MailTestRequest req = new com.salkcoding.oswl.auth.dto.MailTestRequest();
        req.setHost("localhost");
        req.setPort(587);
        req.setEncryption("STARTTLS");
        req.setUsername("user@example.com");
        req.setPassword("secret");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> securitySettingService.testMailConnection(req))
                .isInstanceOf(jakarta.mail.MessagingException.class);
    }

    @Test
    @DisplayName("testMailConnection: SSL_TLS 설정 → 연결 실패 시 MessagingException 발생")
    void testMailConnection_sslTls_throwsMessagingException() {
        com.salkcoding.oswl.auth.dto.MailTestRequest req = new com.salkcoding.oswl.auth.dto.MailTestRequest();
        req.setHost("localhost");
        req.setPort(465);
        req.setEncryption("SSL_TLS");
        req.setUsername("user@example.com");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> securitySettingService.testMailConnection(req))
                .isInstanceOf(jakarta.mail.MessagingException.class);
    }

    @Test
    @DisplayName("testMailConnection: NONE 설정 + 인증 → 연결 실패 시 MessagingException")
    void testMailConnection_none_withAuth_throwsMessagingException() {
        com.salkcoding.oswl.auth.dto.MailTestRequest req = new com.salkcoding.oswl.auth.dto.MailTestRequest();
        req.setHost("localhost");
        req.setPort(25);
        req.setEncryption("NONE");
        req.setUsername("relay@example.com");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> securitySettingService.testMailConnection(req))
                .isInstanceOf(jakarta.mail.MessagingException.class);
    }

    @Test
    @DisplayName("testMailConnection: 포트 null이면 기본값 587 사용, MessagingException 발생")
    void testMailConnection_nullPort_usesDefault587() {
        com.salkcoding.oswl.auth.dto.MailTestRequest req = new com.salkcoding.oswl.auth.dto.MailTestRequest();
        req.setHost("localhost");
        req.setPort(null);
        req.setEncryption("NONE");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> securitySettingService.testMailConnection(req))
                .isInstanceOf(jakarta.mail.MessagingException.class);
    }

    @Test
    @DisplayName("testMailConnection: 폼 비밀번호가 비어 있으면 저장된 암호화 비밀번호를 복호화해 사용한다")
    void testMailConnection_blankPassword_usesStoredEncryptedPassword() throws Exception {
        SecuritySetting stored = SecuritySetting.builder()
                .id(1L)
                .mailHost("smtp.gmail.com")
                .mailPort(587)
                .mailEncryption("STARTTLS")
                .mailUsername("user@gmail.com")
                .mailPassword("enc-pw")
                .build();
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(stored));
        when(encryptionService.decrypt("enc-pw")).thenReturn("app-password");

        com.salkcoding.oswl.auth.dto.MailTestRequest req = new com.salkcoding.oswl.auth.dto.MailTestRequest();
        req.setHost("smtp.gmail.com");
        req.setPort(587);
        req.setEncryption("STARTTLS");
        req.setUsername("user@gmail.com");
        // password intentionally blank (UI after save)

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> securitySettingService.testMailConnection(req))
                .isInstanceOf(jakarta.mail.MessagingException.class);
        verify(encryptionService).decrypt("enc-pw");
    }
}
