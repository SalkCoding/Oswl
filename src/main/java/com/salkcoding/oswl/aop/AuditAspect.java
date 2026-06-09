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
 * Intercepts methods annotated with @Auditable and calls AuditLogService.log().
 *
 * If the method throws an exception, no audit log is recorded (rolled-back work is not logged).
 * If SpEL evaluation fails, only a WARN log is written and the business flow is not affected.
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
            // BEFORE: build the SpEL context before method execution (for deletes, etc.)
            EvaluationContext ctx = buildContext(pjp, null);
            Object result = pjp.proceed();
            fire(auditable, ctx);
            return result;
        }

        // AFTER (default): log only when the method returns successfully
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
            log.warn("[AuditAspect] Failed to record audit log action={} (business flow unaffected): {}", a.action(), e.getMessage(), e);
        }
    }

    private EvaluationContext buildContext(ProceedingJoinPoint pjp, Object result) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        ctx.setVariable("result", result);

        MethodSignature sig   = (MethodSignature) pjp.getSignature();
        String[]        names = sig.getParameterNames();
        // Fallback for environments without the -parameters flag
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
