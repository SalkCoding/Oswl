package com.salkcoding.oswl.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("LoggingAspect 단위 테스트")
class LoggingAspectTest {

    private final LoggingAspect aspect = new LoggingAspect();

    private ProceedingJoinPoint buildPjp(Object returnValue) throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getDeclaringType()).thenAnswer(inv -> DummyService.class);
        when(sig.getName()).thenReturn("process");
        when(pjp.proceed()).thenReturn(returnValue);
        return pjp;
    }

    private ProceedingJoinPoint buildThrowingPjp() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(sig.getDeclaringType()).thenAnswer(inv -> DummyService.class);
        when(sig.getName()).thenReturn("process");
        when(pjp.proceed()).thenThrow(new IllegalStateException("service failure"));
        return pjp;
    }

    static class DummyService {}

    @Test
    @DisplayName("aroundService: 메서드 성공 시 반환값을 그대로 전달한다")
    void aroundService_success_returnsResult() throws Throwable {
        ProceedingJoinPoint pjp = buildPjp("data");

        Object result = aspect.aroundService(pjp);

        assertThat(result).isEqualTo("data");
    }

    @Test
    @DisplayName("aroundService: 메서드 성공 시 null 반환값도 그대로 전달한다")
    void aroundService_successWithNull_returnsNull() throws Throwable {
        ProceedingJoinPoint pjp = buildPjp(null);

        Object result = aspect.aroundService(pjp);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("aroundService: 메서드가 예외를 던지면 동일한 예외를 재던진다")
    void aroundService_methodThrows_rethrowsException() throws Throwable {
        ProceedingJoinPoint pjp = buildThrowingPjp();

        assertThatThrownBy(() -> aspect.aroundService(pjp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("service failure");
    }

    @Test
    @DisplayName("aroundService: proceed()가 호출된다")
    void aroundService_callsProceed() throws Throwable {
        ProceedingJoinPoint pjp = buildPjp("ok");

        aspect.aroundService(pjp);

        verify(pjp).proceed();
    }
}
