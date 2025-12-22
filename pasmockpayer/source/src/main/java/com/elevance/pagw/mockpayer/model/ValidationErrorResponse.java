package com.elevance.pagw.mockpayer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationErrorResponse {
    private String error;
    private String errorCode;
    private String message;
    private List<ValidationError> validationErrors;
    private String timestamp;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class ValidationError {
    private String field;
    private String code;
    private String message;
}
