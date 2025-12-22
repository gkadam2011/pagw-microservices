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
public class ApprovedResponse {
    private String responseCode;
    private String responseStatus;
    private String message;
    private String authorizationNumber;
    private String effectiveDate;
    private String expirationDate;
    private List<ApprovedService> approvedServices;
    private String providerNpi;
    private String payerName;
    private String payerId;
    private String processedAt;
}

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class ApprovedService {
    private String serviceCode;
    private Integer approvedUnits;
    private Integer approvedVisits;
    private String unitType;
}
