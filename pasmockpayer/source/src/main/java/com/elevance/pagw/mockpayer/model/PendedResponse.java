package com.elevance.pagw.mockpayer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendedResponse {
    private String responseCode;
    private String responseStatus;
    private String message;
    private String pendReason;
    private PendDetails pendDetails;
    private StatusCheckInfo statusCheckInfo;
    private CallbackInfo callbackInfo;
    private String providerNpi;
    private String payerName;
    private String payerId;
    private String receivedAt;
    private String processedAt;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class PendDetails {
    private String reviewType;
    private Integer estimatedCompletionHours;
    private String estimatedCompletionTime;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class StatusCheckInfo {
    private String url;
    private Integer pollingIntervalMinutes;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class CallbackInfo {
    private Boolean willCallback;
    private String callbackUrl;
}
