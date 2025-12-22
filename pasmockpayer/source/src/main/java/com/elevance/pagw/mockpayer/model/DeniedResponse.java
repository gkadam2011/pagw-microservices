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
