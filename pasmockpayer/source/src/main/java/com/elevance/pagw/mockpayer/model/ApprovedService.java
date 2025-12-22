package com.elevance.pagw.mockpayer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovedService {
    private String serviceCode;
    private Integer approvedUnits;
    private Integer approvedVisits;
    private String unitType;
}
