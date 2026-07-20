package com.salkcoding.oswl.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ClientIpResolver unit tests")
class ClientIpResolverTest {

    private static HttpServletRequest request(String remoteAddr, String xff) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Forwarded-For")).thenReturn(xff);
        return request;
    }

    @Test
    @DisplayName("default (no trusted proxies) ignores X-Forwarded-For and uses the remote address")
    void default_ignoresXff() {
        ClientIpResolver resolver = new ClientIpResolver("");

        assertThat(resolver.resolve(request("10.0.0.1", "1.2.3.4"))).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("untrusted peer sending X-Forwarded-For is ignored")
    void untrustedPeer_ignoresXff() {
        ClientIpResolver resolver = new ClientIpResolver("192.168.0.0/16");

        assertThat(resolver.resolve(request("10.1.2.3", "1.2.3.4"))).isEqualTo("10.1.2.3");
    }

    @Test
    @DisplayName("trusted proxy (exact IP): first X-Forwarded-For value is used")
    void trustedExactIp_usesFirstXff() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.1");

        assertThat(resolver.resolve(request("10.0.0.1", "1.2.3.4, 10.0.0.1"))).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("trusted proxy (CIDR range): first X-Forwarded-For value is used")
    void trustedCidr_usesXff() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8, 172.16.0.5");

        assertThat(resolver.resolve(request("10.9.8.7", "5.6.7.8"))).isEqualTo("5.6.7.8");
    }

    @Test
    @DisplayName("trusted proxy without X-Forwarded-For falls back to the remote address")
    void trustedProxy_noXff_usesRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.1");

        assertThat(resolver.resolve(request("10.0.0.1", null))).isEqualTo("10.0.0.1");
        assertThat(resolver.resolve(request("10.0.0.1", "  "))).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("invalid trusted-proxy entries are ignored, valid ones still apply")
    void invalidEntry_ignored() {
        ClientIpResolver resolver = new ClientIpResolver("not-an-ip, 10.0.0.1");

        assertThat(resolver.resolve(request("10.0.0.1", "1.2.3.4"))).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("IPv6 loopback is normalised to 127.0.0.1")
    void ipv6Loopback_normalised() {
        ClientIpResolver resolver = new ClientIpResolver("");

        assertThat(resolver.resolve(request("::1", null))).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 XFF value from a trusted proxy is normalised")
    void ipv6MappedXff_normalised() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.1");

        assertThat(resolver.resolve(request("10.0.0.1", "::ffff:192.168.1.1"))).isEqualTo("192.168.1.1");
    }
}
