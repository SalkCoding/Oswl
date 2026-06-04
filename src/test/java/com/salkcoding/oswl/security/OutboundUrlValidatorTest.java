package com.salkcoding.oswl.security;

import com.salkcoding.oswl.exception.OutboundUrlBlockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.net.InetAddress;
import java.util.Locale;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OutboundUrlValidator unit tests")
class OutboundUrlValidatorTest {

    @Mock MessageSource messageSource;

    private OutboundUrlValidator validator;

    @BeforeEach
    void setUp() {
        lenient().when(messageSource.getMessage(anyString(), isNull(), anyString(), any(Locale.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        validator = new OutboundUrlValidator(messageSource);
    }

    @Test
    @DisplayName("allows public https URL (literal public IP)")
    void allowsPublicUrl() {
        assertThatCode(() -> validator.validateHttpUrl("https://1.1.1.1"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("blocks file scheme")
    void blocksFileScheme() {
        assertThatThrownBy(() -> validator.validateHttpUrl("file:///etc/passwd"))
                .isInstanceOf(OutboundUrlBlockedException.class);
    }

    @Test
    @DisplayName("blocks 127.0.0.1")
    void blocksLoopbackIp() {
        assertThatThrownBy(() -> validator.validateHttpUrl("http://127.0.0.1"))
                .isInstanceOf(OutboundUrlBlockedException.class);
    }

    @Test
    @DisplayName("blocks 169.254.169.254 metadata IP")
    void blocksMetadataIp() {
        assertThatThrownBy(() -> validator.validateHttpUrl("http://169.254.169.254/latest/meta-data"))
                .isInstanceOf(OutboundUrlBlockedException.class);
    }

    @Test
    @DisplayName("blocks localhost hostname")
    void blocksLocalhost() {
        assertThatThrownBy(() -> validator.validateHttpUrl("https://localhost:8080"))
                .isInstanceOf(OutboundUrlBlockedException.class);
    }

    @Test
    @DisplayName("isBlockedAddress detects RFC1918")
    void isBlockedAddress_privateIpv4() throws Exception {
        assertThat(OutboundUrlValidator.isBlockedAddress(InetAddress.getByName("10.0.0.1"))).isTrue();
        assertThat(OutboundUrlValidator.isBlockedAddress(InetAddress.getByName("192.168.1.1"))).isTrue();
        assertThat(OutboundUrlValidator.isBlockedAddress(InetAddress.getByName("8.8.8.8"))).isFalse();
    }
}
