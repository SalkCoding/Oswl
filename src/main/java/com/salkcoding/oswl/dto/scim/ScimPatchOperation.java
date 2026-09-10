package com.salkcoding.oswl.dto.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class ScimPatchOperation {
    private String op;
    private String path;
    private Object value;
}
