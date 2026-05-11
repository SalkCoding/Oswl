package com.salkcoding.oswl.aop;

import java.lang.annotation.*;

/**
 * 메서드에 붙이면 AuditAspect가 자동으로 감사 로그를 기록한다.
 *
 * SpEL 표현식에서 사용 가능한 변수:
 *   #paramName  — 메서드 파라미터 (이름 그대로)
 *   #result     — 반환값 (when=AFTER일 때만 유효)
 *
 * 예시:
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

    /** 고정 action 문자열 (예: "USER.CREATE"). actionExpr 가 있으면 무시된다. */
    String action() default "";

    /** 동적 action을 계산하는 SpEL 표현식 (예: "#enabled ? 'USER.ACTIVATE' : 'USER.DEACTIVATE'"). */
    String actionExpr() default "";

    String targetType();

    /** targetId를 계산하는 SpEL 표현식. */
    String targetIdExpr() default "";

    /** targetName을 계산하는 SpEL 표현식. */
    String targetNameExpr() default "";

    /** detail을 계산하는 SpEL 표현식. */
    String detailExpr() default "";

    /**
     * 감사 로그를 언제 기록할지.
     * AFTER  = 메서드 정상 반환 후 (#result 사용 가능, 기본값)
     * BEFORE = 메서드 실행 전 (삭제처럼 실행 후 데이터가 사라지는 경우)
     */
    When when() default When.AFTER;

    enum When { BEFORE, AFTER }
}
