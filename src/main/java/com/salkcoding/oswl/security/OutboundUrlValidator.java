package com.salkcoding.oswl.security;

import com.salkcoding.oswl.exception.OutboundUrlBlockedException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Blocks outbound HTTP(S) calls to private, link-local, loopback, and cloud metadata targets.
 */
@Component
@RequiredArgsConstructor
public class OutboundUrlValidator {

    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost",
            "metadata.google.internal",
            "metadata",
            "kubernetes.default.svc"
    );

    private final MessageSource messageSource;

    /**
     * Validates a user-supplied base URL (VCS server, custom cloud AI endpoint, etc.).
     * Blocks loopback and private-network targets (SSRF mitigation).
     * No-op when {@code rawUrl} is null or blank.
     */
    public void validateHttpUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return;
        }
        URI uri = parseRequiredHttpUri(rawUrl);
        validateHost(uri.getHost());
    }

    /**
     * Validates a LOCAL AI provider base URL (Ollama, vLLM, LM Studio, etc.).
     * Loopback and private-network hosts are allowed — that is the intended deployment model.
     * Cloud metadata and link-local metadata addresses remain blocked.
     */
    public void validateLocalAiBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return;
        }
        URI uri = parseRequiredHttpUri(rawUrl);
        validateLocalAiHost(uri.getHost());
    }

    private URI parseRequiredHttpUri(String rawUrl) {
        URI uri = parseUri(rawUrl.trim());
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw blocked("security.outboundUrl.error.scheme");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw blocked("security.outboundUrl.error.invalid");
        }
        return uri;
    }

    private URI parseUri(String raw) {
        try {
            URI uri = new URI(raw);
            if (uri.getHost() == null && !raw.contains("://")) {
                uri = new URI("https://" + raw);
            }
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw blocked("security.outboundUrl.error.invalid");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw blocked("security.outboundUrl.error.invalid");
        }
    }

    private void validateHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTNAMES.contains(normalized)
                || normalized.endsWith(".localhost")
                || normalized.endsWith(".local")) {
            throw blocked("security.outboundUrl.error.private");
        }

        if (isMetadataHostname(normalized)) {
            throw blocked("security.outboundUrl.error.metadata");
        }

        if (isLiteralBlockedIp(normalized)) {
            throw blocked(resolveIpBlockMessage(normalized));
        }

        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(address)) {
                    throw blocked(resolveAddressBlockMessage(address));
                }
            }
        } catch (UnknownHostException e) {
            throw blocked("security.outboundUrl.error.unresolvable");
        }
    }

    private void validateLocalAiHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (isMetadataHostname(normalized)) {
            throw blocked("security.outboundUrl.error.metadata");
        }
        if (isLiteralMetadataIp(normalized)) {
            throw blocked("security.outboundUrl.error.metadata");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isMetadataAddress(address)) {
                    throw blocked("security.outboundUrl.error.metadata");
                }
            }
        } catch (UnknownHostException e) {
            throw blocked("security.outboundUrl.error.unresolvable");
        }
    }

    private static boolean isLiteralMetadataIp(String host) {
        return host.equals("169.254.169.254");
    }

    private static boolean isMetadataAddress(InetAddress address) {
        byte[] octets = address.getAddress();
        return octets.length == 4 && (octets[0] & 0xff) == 169 && (octets[1] & 0xff) == 254;
    }

    private static boolean isMetadataHostname(String host) {
        return host.equals("169.254.169.254")
                || host.startsWith("metadata.")
                || host.contains(".metadata.");
    }

    private static boolean isLiteralBlockedIp(String host) {
        try {
            return isBlockedAddress(InetAddress.getByName(host));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet6Address v6) {
            byte[] bytes = v6.getAddress();
            // Unique local (fc00::/7)
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;
            }
        }
        byte[] octets = address.getAddress();
        if (octets.length == 4) {
            int b0 = octets[0] & 0xff;
            int b1 = octets[1] & 0xff;
            // 0.0.0.0/8, 10.0.0.0/8, 127.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16
            if (b0 == 0 || b0 == 10 || b0 == 127) {
                return true;
            }
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            if (b0 == 172 && b1 >= 16 && b1 <= 31) {
                return true;
            }
            if (b0 == 192 && b1 == 168) {
                return true;
            }
        }
        return false;
    }

    private String resolveIpBlockMessage(String host) {
        if (host.startsWith("169.254.")) {
            return "security.outboundUrl.error.metadata";
        }
        return "security.outboundUrl.error.private";
    }

    private String resolveAddressBlockMessage(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return "security.outboundUrl.error.loopback";
        }
        byte[] octets = address.getAddress();
        if (octets.length == 4 && (octets[0] & 0xff) == 169 && (octets[1] & 0xff) == 254) {
            return "security.outboundUrl.error.metadata";
        }
        return "security.outboundUrl.error.private";
    }

    private OutboundUrlBlockedException blocked(String messageKey) {
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage(messageKey, null, messageKey, locale);
        return new OutboundUrlBlockedException(message);
    }
}
