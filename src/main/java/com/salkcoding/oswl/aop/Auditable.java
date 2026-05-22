package com.salkcoding.oswl.aop;

import java.lang.annotation.*;

/**
 * When applied to a method, AuditAspect automatically records an audit log.
 *
 * Variables available in SpEL expressions:
 *   #paramName  — method parameter (using its original name)
 *   #result     — return value (valid only when when=AFTER)
 *
 * Examples:
 *   @Auditable(action="USER.CREATE", targetType="USER",
 *              targetIdExpr="#result.id.toString()", targetNameExpr="#result.email")
 *
 *   @Auditable(action="PROJECT.DELETE", targetType="PROJECT",
 *              targetIdExpr="#id.toString()", when=Auditable.When.BEFORE)
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Auditable {

    /** Fixed action string (for example, "USER.CREATE"). Ignored if actionExpr is present. */
    String action() default "";

    /** SpEL expression that computes a dynamic action (for example, "#enabled ? 'USER.ACTIVATE' : 'USER.DEACTIVATE'"). */
    String actionExpr() default "";

    String targetType();

    /** SpEL expression that computes targetId. */
    String targetIdExpr() default "";

    /** SpEL expression that computes targetName. */
    String targetNameExpr() default "";

    /** SpEL expression that computes detail. */
    String detailExpr() default "";

    /**
     * Controls when the audit log should be recorded.
     * AFTER  = after the method returns successfully (#result available, default)
     * BEFORE = before method execution (for cases like deletes where data disappears afterward)
     */
    When when() default When.AFTER;

    enum When { BEFORE, AFTER }
}
