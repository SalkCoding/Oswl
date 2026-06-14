package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.MailService;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectMemberRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("ScanNotificationService")
class ScanNotificationServiceTest {

    @Mock NotificationSettingService notificationSettingService;
    @Mock ScanResultRepository scanResultRepository;
    @Mock LibraryRepository libraryRepository;
    @Mock ProjectMemberRepository projectMemberRepository;
    @Mock UserRepository userRepository;
    @Mock MailService mailService;
    @Mock OutboundUrlValidator outboundUrlValidator;
    @InjectMocks ScanNotificationService service;

    @Test
    @DisplayName("postWebhook rejects blocked outbound URLs (SSRF guard)")
    void postWebhook_blocksSsrf() {
        String blocked = "http://169.254.169.254/latest/meta-data";
        doThrow(new IllegalArgumentException("blocked")).when(outboundUrlValidator).validateHttpUrl(blocked);

        ReflectionTestUtils.invokeMethod(service, "postWebhook", blocked, "alert");

        verify(outboundUrlValidator).validateHttpUrl(blocked);
    }
}
