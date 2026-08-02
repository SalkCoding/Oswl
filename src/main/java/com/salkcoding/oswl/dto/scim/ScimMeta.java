package com.salkcoding.oswl.dto.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Builder
public class ScimMeta {
    @JsonProperty("resourceType")
    private String resourceType;
    private String created;
    private String lastModified;
    private String location;
    private String version;
}
