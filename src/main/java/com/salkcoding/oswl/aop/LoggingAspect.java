package com.salkcoding.oswl.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * 서비스 레이어 대상 AOP 기반 로깅 애스펙트.
 *
 * 동작:
 *  - 모든 service 패키지 메서드의 실행 시간을 측정
 *  - 실행 시간 < 1000ms  → DEBUG (평소 콘솔에 안 뜸)
 *  - 실행 시간 >= 1000ms → INFO  (슬로우 콜 알림)
 *  - 실행 시간 >= 5000ms→ WARN  (너무 느린 콜 경고)
 *  - 예외 발생 시       → ERROR (스택 트레이스 없이 요약만; 스택은 최상위에서)
 */
@Slf4j
@Aspect
@Component
public class LoggingAspect {

    private static final long SLOW_THRESHOLD_MS  = 1_000;
    private static final long WARN_THRESHOLD_MS  = 5_000;

    // ── Service 레이어 전체 포인트컷 ─────────────────────────────────────────
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
            log.error("[AOP] {}.{}() 실패 ({}ms) — {}: {}",
                    className, methodName, elapsed,
                    e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    private void logElapsed(String className, String methodName, long elapsed) {
        if (elapsed >= WARN_THRESHOLD_MS) {
            log.warn("[AOP] {}.{}() {}ms — 매우 느린 호출", className, methodName, elapsed);
        } else if (elapsed >= SLOW_THRESHOLD_MS) {
            log.info("[AOP] {}.{}() {}ms — 느린 호출", className, methodName, elapsed);
        } else {
            log.debug("[AOP] {}.{}() {}ms", className, methodName, elapsed);
        }
    }
}
