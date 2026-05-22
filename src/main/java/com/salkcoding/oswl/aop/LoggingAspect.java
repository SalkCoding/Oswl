package com.salkcoding.oswl.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * AOP-based logging aspect targeting the service layer.
 *
 * Behavior:
 *  - Measures execution time of all methods in service packages
 *  - Execution time < 1000ms  → DEBUG (normally hidden from the console)
 *  - Execution time >= 1000ms → INFO  (slow-call notice)
 *  - Execution time >= 5000ms → WARN  (very slow-call warning)
 *  - On exception             → ERROR (summary only, no stack trace; stack is logged at the top level)
 */
@Slf4j
@Aspect
@Component
public class LoggingAspect {

    private static final long SLOW_THRESHOLD_MS  = 1_000;
    private static final long WARN_THRESHOLD_MS  = 5_000;

    // ── Entire service-layer pointcut ────────────────────────────────────
    @Around("execution(* com.salkcoding.oswl.service..*(..))")
    public Object aroundService(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String className  = sig.getDeclaringType().getSimpleName();
        String methodName = sig.getName();

        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;
            logElapsed(className, methodName, elapsed);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[AOP] {}.{}() failed ({}ms) — {}: {}", 
                    className, methodName, elapsed,
                    e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    private void logElapsed(String className, String methodName, long elapsed) {
        if (elapsed >= WARN_THRESHOLD_MS) {
            log.warn("[AOP] {}.{}() {}ms — very slow call", className, methodName, elapsed);
        } else if (elapsed >= SLOW_THRESHOLD_MS) {
            log.info("[AOP] {}.{}() {}ms — slow call", className, methodName, elapsed);
        } else {
            log.debug("[AOP] {}.{}() {}ms", className, methodName, elapsed);
        }
    }
}
