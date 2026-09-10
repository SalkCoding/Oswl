package com.salkcoding.oswl.dto.policy;

import com.salkcoding.oswl.domain.enums.PolicyExceptionStatus;
import com.salkcoding.oswl.domain.enums.PolicyExceptionTargetType;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class PolicyExceptionDto {

    Long id;
    Long projectId;
    Long requesterUserId;
    String requesterName;
    Long approverUserId;
    String approverName;
    String reason;
    LocalDateTime expiry;
    PolicyExceptionStatus status;
    PolicyExceptionTargetType targetType;
    String targetId;
    String componentCoordinate;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    LocalDateTime approvedAt;
    LocalDateTime revokedAt;
}
