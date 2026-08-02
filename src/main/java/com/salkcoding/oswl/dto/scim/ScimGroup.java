package com.salkcoding.oswl.dto.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Builder
public class ScimGroup {

    private String id;
    private String externalId;
    private String displayName;
    private List<ScimMember> members;

    @JsonProperty("schemas")
    private List<String> schemas;

    private ScimMeta meta;
}
