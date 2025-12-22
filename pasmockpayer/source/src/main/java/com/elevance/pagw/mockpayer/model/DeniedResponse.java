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
public class DeniedResponse {
    private String responseCode;
    private String responseStatus;
    private String message;
    private List<DenialReason> denialReasons;
    private AppealInstructions appealInstructions;
    private String providerNpi;
    private String payerName;
    private String payerId;
    private String processedAt;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class DenialReason {
    private String code;
    private String reason;
    private String description;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class AppealInstructions {
    private String deadline;
    private String method;
    private String faxNumber;
    private String address;
}
