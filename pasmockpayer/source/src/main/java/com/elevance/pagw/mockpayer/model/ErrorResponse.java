package com.elevance.pagw.mockpayer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    private String error;
    private String errorCode;
    private String message;
    private String details;
    private String errorId;
    private String timestamp;
    private Boolean retryable;
    private Integer suggestedRetryAfterSeconds;
}
