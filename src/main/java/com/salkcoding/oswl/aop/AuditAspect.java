package com.salkcoding.oswl.aop;

import com.salkcoding.oswl.auth.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * @Auditable 어노테이션이 붙은 메서드를 인터셉트해 AuditLogService.log()를 호출한다.
 *
 * 메서드가 예외를 던지면 감사 로그를 남기지 않는다 (롤백된 작업은 기록하지 않음).
 * SpEL 평가 실패 시 WARN 로그만 남기고 비즈니스 흐름에는 영향을 주지 않는다.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditLogService auditLogService;

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    @Around("@annotation(auditable)")
    public Object around(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        if (auditable.when() == Auditable.When.BEFORE) {
            // BEFORE: SpEL 컨텍스트를 메서드 실행 전에 빌드 (삭제 등)
            EvaluationContext ctx = buildContext(pjp, null);
            Object result = pjp.proceed();
            fire(auditable, ctx);
            return result;
        }

        // AFTER (기본): 메서드가 정상 반환된 경우에만 로그
        Object result = pjp.proceed();
        EvaluationContext ctx = buildContext(pjp, result);
        fire(auditable, ctx);
        return result;
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private void fire(Auditable a, EvaluationContext ctx) {
        try {
            String action     = a.actionExpr().isBlank() ? a.action()    : eval(a.actionExpr(), ctx);
            String targetId   = eval(a.targetIdExpr(), ctx);
            String targetName = eval(a.targetNameExpr(), ctx);
            String detail     = eval(a.detailExpr(), ctx);
            auditLogService.log(action, a.targetType(), targetId, targetName, detail);
        } catch (Exception e) {
            log.warn("[AuditAspect] 감사 로그 기록 실패 action={} (비즈니스 흐름에 영향 없음): {}", a.action(), e.getMessage(), e);
        }
    }

    private EvaluationContext buildContext(ProceedingJoinPoint pjp, Object result) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        ctx.setVariable("result", result);

        MethodSignature sig   = (MethodSignature) pjp.getSignature();
        String[]        names = sig.getParameterNames();
        // -parameters 플래그 없는 환경을 위한 폴백
        if (names == null || names.length == 0) {
            names = NAME_DISCOVERER.getParameterNames(sig.getMethod());
        }
        Object[] args = pjp.getArgs();

        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                ctx.setVariable(names[i], args[i]);
            }
        }
        return ctx;
    }

    private String eval(String expr, EvaluationContext ctx) {
        if (expr == null || expr.isBlank()) return null;
        Object val = PARSER.parseExpression(expr).getValue(ctx);
        return val != null ? val.toString() : null;
    }
}
