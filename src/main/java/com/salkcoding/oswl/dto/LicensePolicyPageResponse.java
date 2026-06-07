package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class LicensePolicyPageResponse {
    List<LicensePolicyEntryDto> items;
    int page;
    int size;
    long total;
    boolean hasMore;
}
