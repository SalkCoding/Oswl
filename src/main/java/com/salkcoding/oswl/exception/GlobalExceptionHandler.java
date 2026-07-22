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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.security.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;
    private final ClientIpResolver clientIpResolver;

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound() {
        return "error/404";
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public Object handleTooManyRequests(TooManyRequestsException ex, HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json")) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", ex.getMessage(), "status", 429));
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
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
        String ip = clientIpResolver.resolve(request);
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

    /**
     * Bean Validation failure on a {@code @RequestBody} (e.g. a required field is null).
     * Validation failures are client errors — mapped to 400, never to the generic 500
     * catch-all or the 404 used for "not found" IllegalArgumentExceptions.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Object handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        if (message.isBlank()) {
            message = "Request validation failed";
        }
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", message, "status", 400));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(InvalidRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage(), "status", 400));
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

    @ExceptionHandler(AiSummaryException.class)
    public ResponseEntity<Map<String, Object>> handleAiSummary(AiSummaryException ex, Locale locale) {
        String msg = messageSource.getMessage(ex.getMessageKey(), ex.getArgs(), locale);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "code", ex.getCode().name(),
                        "message", msg,
                        "status", 400));
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
}
