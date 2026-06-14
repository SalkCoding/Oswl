package com.salkcoding.oswl.service;

import com.salkcoding.oswl.exception.TooManyRequestsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ScanApiCredentialThrottleService unit tests")
class ScanApiCredentialThrottleServiceTest {

  @Test
  @DisplayName("credential failures over limit throw TooManyRequestsException")
  void credentialFailures_rateLimited() {
    ScanApiCredentialThrottleService throttle =
            new ScanApiCredentialThrottleService(3, 900, 100);

    for (int i = 0; i < 3; i++) {
      throttle.recordCredentialFailure(1L, "dev@test.com");
    }

    assertThatThrownBy(() -> throttle.recordCredentialFailure(1L, "dev@test.com"))
            .isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  @DisplayName("success clears failure state for project+email")
  void success_clearsFailures() {
    ScanApiCredentialThrottleService throttle =
            new ScanApiCredentialThrottleService(2, 900, 100);

    throttle.recordCredentialFailure(1L, "dev@test.com");
    throttle.recordCredentialSuccess(1L, "dev@test.com");
    throttle.recordCredentialFailure(1L, "dev@test.com");

    assertThatCode(() -> throttle.recordCredentialFailure(1L, "dev@test.com"))
            .doesNotThrowAnyException();
  }
}
