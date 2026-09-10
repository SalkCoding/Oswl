package com.salkcoding.oswl.dto.policy;

import com.salkcoding.oswl.domain.enums.PolicyExceptionTargetType;

import java.time.LocalDateTime;

/**
 * Request a new policy exception (waiver).
 */
public record PolicyExceptionRequest(
        Long projectId,
        String reason,
        LocalDateTime expiry,
        PolicyExceptionTargetType targetType,
        String targetId,
        String componentCoordinate
) {}
