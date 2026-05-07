package com.salkcoding.oswl.auth.dto;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class VcsConnectionDto {
    private Long id;
    private VcsProvider provider;
    private String serverUrl;
    private String vcsUsername;
    private LocalDateTime createdAt;
}
