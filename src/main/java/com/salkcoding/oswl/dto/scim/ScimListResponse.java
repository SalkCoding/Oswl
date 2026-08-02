package com.salkcoding.oswl.dto.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Builder
public class ScimListResponse<T> {

    @JsonProperty("schemas")
    private List<String> schemas;

    private int totalResults;
    private int startIndex;
    private int itemsPerPage;

    private List<T> resources;
}
