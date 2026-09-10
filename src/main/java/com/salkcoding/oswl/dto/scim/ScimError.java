package com.salkcoding.oswl.dto.scim;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ScimError {

    @JsonProperty("schemas")
    private List<String> schemas;

    private String detail;
    private String status;
}
