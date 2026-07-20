package com.salkcoding.oswl.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the real client IP for audit logs, throttling, and security logging.
 *
 * X-Forwarded-For is only honoured when the direct peer ({@code getRemoteAddr()}) matches
 * {@code oswl.security.trusted-proxies} (comma-separated IPs or CIDR ranges).
 * The default (empty) trusts no proxy: the header is ignored and {@code getRemoteAddr()}
 * is used, so clients cannot spoof their IP by sending X-Forwarded-For directly.
 */
@Slf4j
@Component
public class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(@Value("${oswl.security.trusted-proxies:}") String trustedProxies) {
        this.trustedProxies = parse(trustedProxies);
    }

    /** Returns the client IP, using the first X-Forwarded-For entry only behind a trusted proxy. */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String ip = remoteAddr;
        if (isTrustedProxy(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                ip = xff.split(",")[0].trim();
            }
        }
        return normalizeIp(ip);
    }

    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        String normalized = normalizeIp(remoteAddr);
        for (IpAddressMatcher matcher : trustedProxies) {
            if (matcher.matches(remoteAddr) || matcher.matches(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static List<IpAddressMatcher> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String entry : csv.split(",")) {
            String candidate = entry.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            try {
                matchers.add(new IpAddressMatcher(candidate));
            } catch (IllegalArgumentException e) {
                log.warn("[ClientIpResolver] Ignoring invalid trusted proxy entry '{}': {}", candidate, e.getMessage());
            }
        }
        return List.copyOf(matchers);
    }

    /**
     * IPv6-mapped IPv4 addresses (::ffff:x.x.x.x) and the IPv6 loopback (::1) are
     * normalised to their IPv4 equivalents so logs are consistent.
     */
    static String normalizeIp(String ip) {
        if (ip == null) return "unknown";
        // IPv6 loopback → IPv4 loopback
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return "127.0.0.1";
        // IPv6-mapped IPv4 address: ::ffff:192.168.1.1
        if (ip.startsWith("::ffff:") || ip.startsWith("::FFFF:")) return ip.substring(7);
        if (ip.startsWith("0:0:0:0:0:ffff:") || ip.startsWith("0:0:0:0:0:FFFF:")) return ip.substring(15);
        return ip;
    }
}
