package com.salkcoding.oswl.aop;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.service.AuditLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditAspect 단위 테스트")
class AuditAspectTest {

    @Mock AuditLogService auditLogService;
    @InjectMocks AuditAspect auditAspect;

    // Helper: build a mock ProceedingJoinPoint for a method on a dummy class.
    // getMethod() is intentionally not stubbed: getParameterNames() returns a
    // non-empty array, so AuditAspect never falls back to NAME_DISCOVERER.
    private ProceedingJoinPoint buildPjp(Object returnValue, Object... args) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getParameterNames()).thenReturn(new String[]{"name", "id"});
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn(returnValue);
        return pjp;
    }

    // ── Annotation helpers ────────────────────────────────────────────────────
    private Auditable auditableAfter(String action, String targetType) {
        return new Auditable() {
            public Class<? extends java.lang.annotation.Annotation> annotationType() { return Auditable.class; }
            public String action()       { return action; }
            public String actionExpr()   { return ""; }
            public String targetType()   { return targetType; }
            public String targetIdExpr() { return ""; }
            public String targetNameExpr() { return ""; }
            public String detailExpr()   { return ""; }
            public When when()           { return When.AFTER; }
        };
    }

    private Auditable auditableBefore(String action, String targetType) {
        return new Auditable() {
            public Class<? extends java.lang.annotation.Annotation> annotationType() { return Auditable.class; }
            public String action()       { return action; }
            public String actionExpr()   { return ""; }
            public String targetType()   { return targetType; }
            public String targetIdExpr() { return "#id.toString()"; }
            public String targetNameExpr() { return "#name"; }
            public String detailExpr()   { return ""; }
            public When when()           { return When.BEFORE; }
        };
    }

    private Auditable auditableWithExpressions(String action, String targetType,
                                                String idExpr, String nameExpr, String detailExpr) {
        return new Auditable() {
            public Class<? extends java.lang.annotation.Annotation> annotationType() { return Auditable.class; }
            public String action()         { return action; }
            public String actionExpr()     { return ""; }
            public String targetType()     { return targetType; }
            public String targetIdExpr()   { return idExpr; }
            public String targetNameExpr() { return nameExpr; }
            public String detailExpr()     { return detailExpr; }
            public When when()             { return When.AFTER; }
        };
    }

    // ── AFTER mode ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("around (AFTER): 메서드가 성공하면 감사 로그를 기록한다")
    void around_afterMode_methodSucceeds_logsAudit() throws Throwable {
        ProceedingJoinPoint pjp = buildPjp("ok", "Alice", 1L);
        Auditable a = auditableAfter("USER.UPDATE", "USER");

        auditAspect.around(pjp, a);

        verify(auditLogService).log(eq("USER.UPDATE"), eq("USER"), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("around (AFTER): 메서드가 예외를 던지면 감사 로그를 기록하지 않는다")
    void around_afterMode_methodThrows_noAuditLog() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenThrow(new RuntimeException("business error"));
        Auditable a = auditableAfter("USER.UPDATE", "USER");

        assertThatThrownBy(() -> auditAspect.around(pjp, a))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("business error");
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("around (AFTER): SpEL 표현식으로 targetId와 targetName을 추출한다")
    void around_afterMode_spelExpressionsResolveCorrectly() throws Throwable {
        ProceedingJoinPoint pjp = buildPjp("ignored", "Bob", 42L);
        Auditable a = auditableWithExpressions("USER.UPDATE", "USER", "#id.toString()", "#name", "");

        auditAspect.around(pjp, a);

        verify(auditLogService).log("USER.UPDATE", "USER", "42", "Bob", null);
    }

    @Test
    @DisplayName("around (AFTER): #result SpEL 표현식을 처리한다")
    void around_afterMode_resultSpelExpression() throws Throwable {
        ProceedingJoinPoint pjp = buildPjp("returnedValue", "Alice", 1L);
        Auditable a = auditableWithExpressions("USER.UPDATE", "USER", "", "", "#result");

        auditAspect.around(pjp, a);

        verify(auditLogService).log("USER.UPDATE", "USER", null, null, "returnedValue");
    }

    // ── BEFORE mode ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("around (BEFORE): 메서드 실행 전 SpEL 컨텍스트를 구성하고 감사 로그를 기록한다")
    void around_beforeMode_logsAuditWithPreExecutionContext() throws Throwable {
        ProceedingJoinPoint pjp = buildPjp("afterResult", "Charlie", 7L);
        Auditable a = auditableBefore("USER.DELETE", "USER");

        auditAspect.around(pjp, a);

        verify(auditLogService).log(eq("USER.DELETE"), eq("USER"), eq("7"), eq("Charlie"), isNull());
    }

    // ── SpEL error handling ──────────────────────────────────────────────────

    @Test
    @DisplayName("around: SpEL 평가 실패 시 예외를 삼키고 비즈니스 로직은 정상 완료된다")
    void around_spelError_doesNotPropagateException() throws Throwable {
        ProceedingJoinPoint pjp = buildPjp("ok", "Dave", 5L);
        // Bad SpEL: reference to a variable that doesn't exist but produces an error
        Auditable a = auditableWithExpressions("USER.UPDATE", "USER",
                "#nonexistentMethod()", "", "");

        // Should not throw
        Object result = auditAspect.around(pjp, a);

        assertThat(result).isEqualTo("ok");
    }
}
