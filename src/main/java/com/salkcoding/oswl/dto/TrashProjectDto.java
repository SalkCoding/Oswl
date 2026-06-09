package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TrashProjectDto {
    private Long id;
    private String name;
    /** Formatted as "yyyy.MM.dd" */
    private String deletedAt;
    /** Days remaining until permanent deletion (30-day window). */
    private int daysLeft;
    /** "red" (≤7), "orange" (≤15), "yellow" (>15) */
    private String urgencyColor;
}
