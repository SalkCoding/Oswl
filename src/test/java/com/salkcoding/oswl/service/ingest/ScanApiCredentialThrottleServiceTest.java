package com.salkcoding.oswl.service.ingest;
import com.salkcoding.oswl.service.ingest.ScanApiCredentialThrottleService;

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

  @Test
  @DisplayName("API key pre-check is read-only and never consumes the failure window")
  void apiKeyPreCheck_readOnly() {
    ScanApiCredentialThrottleService throttle =
            new ScanApiCredentialThrottleService(3, 900, 100);

    for (int i = 0; i < 10; i++) {
      assertThatCode(() -> throttle.assertApiKeyCheckAllowed("10.0.0.1"))
              .doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("API key pre-check throws once recorded failures reach the limit")
  void apiKeyPreCheck_throwsAfterMaxFailures() {
    ScanApiCredentialThrottleService throttle =
            new ScanApiCredentialThrottleService(3, 900, 100);

    for (int i = 0; i < 3; i++) {
      throttle.recordApiKeyFailure("10.0.0.1");
    }

    assertThatThrownBy(() -> throttle.assertApiKeyCheckAllowed("10.0.0.1"))
            .isInstanceOf(TooManyRequestsException.class);
  }
}
