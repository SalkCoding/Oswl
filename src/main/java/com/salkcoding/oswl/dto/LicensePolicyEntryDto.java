package com.salkcoding.oswl.dto;

import com.salkcoding.oswl.domain.enums.LicenseStatus;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LicensePolicyEntryDto {
    Long id;
    String spdxId;
    LicenseStatus status;
    String reason;
    boolean builtIn;
}
