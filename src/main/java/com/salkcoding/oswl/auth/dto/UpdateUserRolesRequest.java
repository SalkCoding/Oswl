package com.salkcoding.oswl.auth.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateUserRolesRequest {
    private List<Long> templateIds;
}
