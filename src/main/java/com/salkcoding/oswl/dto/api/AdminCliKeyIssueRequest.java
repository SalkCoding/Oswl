package com.salkcoding.oswl.dto.api;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminCliKeyIssueRequest {
    private Long   projectId;
    private String label;
}
