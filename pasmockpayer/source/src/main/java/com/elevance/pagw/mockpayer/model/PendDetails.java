package com.elevance.pagw.mockpayer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendDetails {
    private String reviewType;
    private Integer estimatedCompletionHours;
    private String estimatedCompletionTime;
}
