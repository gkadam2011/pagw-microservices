package com.anthem.pagw.core.model.fhir;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CPT/HCPCS procedure code from FHIR Claim
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcedureCode {
    private String code;               // CPT/HCPCS code (e.g., "99213")
    private String display;            // Human-readable description
    private String system;             // http://www.ama-assn.org/go/cpt
    private int sequence;              // Position in claim
    private BigDecimal chargeAmount;   // Amount charged for this procedure
    private Integer quantity;          // Number of units
    private LocalDate serviceDate;     // Date service was provided
}
