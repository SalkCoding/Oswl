package com.salkcoding.oswl.dto.scim;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class ScimPatchRequest {

    @JsonProperty("schemas")
    private List<String> schemas;

    private List<ScimPatchOperation> operations;
}
