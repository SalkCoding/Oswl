package com.salkcoding.oswl.dto.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Builder
public class ScimUser {

    private String id;
    private String externalId;
    private String userName;
    private ScimName name;
    private String displayName;
    private Boolean active;
    private List<ScimEmail> emails;
    private Set<String> groups;

    @JsonProperty("schemas")
    private List<String> schemas;

    private ScimMeta meta;
}
