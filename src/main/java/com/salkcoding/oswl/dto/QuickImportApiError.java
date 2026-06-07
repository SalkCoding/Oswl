package com.salkcoding.oswl.dto;

import java.util.List;
import java.util.Map;

/** JSON body for Quick Import API errors — localized on the client via {@code errorKey}. */
public final class QuickImportApiError {

    private QuickImportApiError() {}

    public static Map<String, Object> body(String errorKey, List<String> errorArgs) {
        return Map.of(
                "errorKey", errorKey,
                "errorArgs", errorArgs != null ? errorArgs : List.of()
        );
    }
}
