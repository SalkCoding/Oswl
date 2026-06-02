package com.salkcoding.oswl.exception;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound() {
        return "error/404";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Object handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = "anonymous";
        String displayName = "-";
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof OswlUserPrincipal p) {
            email = p.getUsername();
            displayName = p.getDisplayName();
        } else if (auth != null && auth.isAuthenticated()) {
            email = auth.getName();
        }
        String ip = resolveClientIp(request);
        log.warn("[Security] Access denied — user='{}' name='{}' ip='{}' {} {}",
                email, displayName, ip, request.getMethod(), request.getRequestURI());
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access Denied", "status", 403));
        }
        return new ModelAndView("error/403");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", ex.getMessage(), "status", 404));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(ConflictException.class)
    public Object handleConflict(ConflictException ex, HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", ex.getMessage(), "status", 409));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(IllegalStateException.class)
    public Object handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", ex.getMessage(), "status", 400));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    /**
     * Handles HttpMessageNotWritableException — most commonly caused by the client
     * disconnecting while the server is writing the response body (client abort).
     *
     * Client-abort or already-committed: silently logged at DEBUG, no further rendering.
     * Genuine serialization failure: logs at ERROR and returns a localized 500 response.
     */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public Object handleMessageNotWritable(
            HttpMessageNotWritableException ex,
            HttpServletRequest request,
            HttpServletResponse response,
            Locale locale) {

        if (isClientAbort(ex) || response.isCommitted()) {
            log.debug("[GlobalExceptionHandler] Client disconnected during response write ({}): {}",
                    ex.getClass().getSimpleName(), summarizeMessage(ex));
            ModelAndView mv = new ModelAndView();
            mv.clear();
            return mv;
        }

        log.error("[GlobalExceptionHandler] Failed to serialize HTTP response", ex);
        try {
            response.reset();
        } catch (IllegalStateException committed) {
            log.debug("[GlobalExceptionHandler] Response already committed — cannot recover from write failure.");
            ModelAndView mv = new ModelAndView();
            mv.clear();
            return mv;
        }

        String msg = messageSource.getMessage("error.api.internal", null, locale);
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", msg, "status", 500));
        }
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return new ModelAndView("error/500");
    }

    @ExceptionHandler(Exception.class)
    public Object handleUnexpected(Exception ex, HttpServletRequest request, HttpServletResponse response, Locale locale) {
        try {
            // reset() clears any previously obtained OutputStream/Writer and the output buffer.
            // If the response is already committed (bytes sent to client), it throws IllegalStateException.
            response.reset();
            log.error("An unhandled exception reached GlobalExceptionHandler", ex);
            String msg = messageSource.getMessage("error.api.internal", null, locale);
            String accept = request.getHeader("Accept");
            if (accept != null && accept.contains("application/json")) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", msg, "status", 500));
            }
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            return new ModelAndView("error/500");
        } catch (IllegalStateException committed) {
            // Response already committed (e.g. client disconnected mid-download).
            // Cannot render a view — return a cleared ModelAndView so DispatcherServlet skips rendering.
            log.debug("[GlobalExceptionHandler] Exception after committed response ({}): {}",
                    ex.getClass().getSimpleName(), summarizeMessage(ex));
            ModelAndView mv = new ModelAndView();
            mv.clear();
            return mv;
        }
    }

    /** Returns true when the exception chain contains an IOException that matches known client-abort patterns. */
    private static boolean isClientAbort(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                String msg = t.getMessage();
                if (msg != null) {
                    String lower = msg.toLowerCase(Locale.ROOT);
                    if (lower.contains("broken pipe")
                            || lower.contains("connection reset")
                            || lower.contains("forcibly closed")
                            || lower.contains("aborted")
                            || lower.contains("연결은")) {   // Korean Windows OS-level abort message
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Returns a concise single-line summary of an exception message (without stack trace noise). */
    private static String summarizeMessage(Throwable ex) {
        String msg = ex.getMessage();
        if (msg == null) return ex.getClass().getSimpleName();
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl) : msg;
    }

    /** Resolves the real client IP, honouring X-Forwarded-For when behind a proxy.
     *  IPv6-mapped IPv4 addresses (::ffff:x.x.x.x) and the IPv6 loopback (::1) are
     *  normalised to their IPv4 equivalents so logs are consistent. */
    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String raw = (xff != null && !xff.isBlank())
                ? xff.split(",")[0].trim()
                : request.getRemoteAddr();
        return normalizeIp(raw);
    }

    private static String normalizeIp(String ip) {
        if (ip == null) return "unknown";
        // IPv6 loopback → IPv4 loopback
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return "127.0.0.1";
        // IPv6-mapped IPv4 address: ::ffff:192.168.1.1
        if (ip.startsWith("::ffff:") || ip.startsWith("::FFFF:")) return ip.substring(7);
        if (ip.startsWith("0:0:0:0:0:ffff:") || ip.startsWith("0:0:0:0:0:FFFF:")) return ip.substring(15);
        return ip;
    }
}
