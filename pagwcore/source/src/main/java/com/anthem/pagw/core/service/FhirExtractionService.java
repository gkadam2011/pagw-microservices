package com.anthem.pagw.core.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.anthem.pagw.core.model.fhir.*;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Service to extract structured data from FHIR R4 Bundles
 */
@Service
public class FhirExtractionService {
    
    private static final Logger log = LoggerFactory.getLogger(FhirExtractionService.class);
    private final FhirContext fhirContext;
    
    public FhirExtractionService() {
        this.fhirContext = FhirContext.forR4();
    }
    
    /**
     * Extract structured data from FHIR Bundle JSON
     */
    public ParsedFhirData extractFromBundle(String fhirJson, String pagwId, String tenant) {
        try {
            Bundle bundle = parseBundle(fhirJson);
            
            ParsedFhirData result = ParsedFhirData.builder()
                    .pagwId(pagwId)
                    .tenant(tenant)
                    .parsedAt(Instant.now())
                    .build();
            
            // Extract resources
            result.setPatient(extractPatient(bundle));
            result.setPractitioner(extractPractitioner(bundle));
            result.setClaim(extractClaim(bundle));
            
            // Calculate statistics
            if (result.getClaim() != null) {
                result.setTotalDiagnosisCodes(result.getClaim().getDiagnosisCodes().size());
                result.setTotalProcedureCodes(result.getClaim().getProcedureCodes().size());
                result.setTotalAttachments(result.getClaim().getAttachmentCount() != null ? result.getClaim().getAttachmentCount() : 0);
                result.setHasUrgentIndicator(isUrgent(result.getClaim()));
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("Failed to extract FHIR data: pagwId={}, error={}", pagwId, e.getMessage(), e);
            throw new RuntimeException("FHIR extraction failed", e);
        }
    }
    
    private Bundle parseBundle(String fhirJson) {
        IParser parser = fhirContext.newJsonParser();
        return parser.parseResource(Bundle.class, fhirJson);
    }
    
    private PatientInfo extractPatient(Bundle bundle) {
        Patient patient = findResource(bundle, Patient.class);
        if (patient == null) {
            log.warn("No Patient resource found in bundle");
            return null;
        }
        
        PatientInfo.PatientInfoBuilder builder = PatientInfo.builder();
        
        // Member ID
        builder.memberId(extractMemberId(patient));
        
        // Name
        if (patient.hasName() && !patient.getName().isEmpty()) {
            HumanName name = patient.getName().get(0);
            if (name.hasGiven()) {
                builder.firstName(name.getGivenAsSingleString());
            }
            if (name.hasFamily()) {
                builder.lastName(name.getFamily());
            }
        }
        
        // DOB
        if (patient.hasBirthDate()) {
            builder.dateOfBirth(convertToLocalDate(patient.getBirthDate()));
        }
        
        // Gender
        if (patient.hasGender()) {
            builder.gender(patient.getGender().toCode());
        }
        
        // Address
        if (patient.hasAddress() && !patient.getAddress().isEmpty()) {
            Address address = patient.getAddress().get(0);
            if (address.hasLine() && !address.getLine().isEmpty()) {
                builder.addressLine1(address.getLine().get(0).getValue());
            }
            if (address.hasCity()) {
                builder.city(address.getCity());
            }
            if (address.hasState()) {
                builder.state(address.getState());
            }
            if (address.hasPostalCode()) {
                builder.postalCode(address.getPostalCode());
            }
            if (address.hasCountry()) {
                builder.country(address.getCountry());
            }
        }
        
        // Contact
        if (patient.hasTelecom()) {
            for (ContactPoint contact : patient.getTelecom()) {
                if (contact.getSystem() == ContactPoint.ContactPointSystem.PHONE && builder.build().getPhone() == null) {
                    builder.phone(contact.getValue());
                } else if (contact.getSystem() == ContactPoint.ContactPointSystem.EMAIL && builder.build().getEmail() == null) {
                    builder.email(contact.getValue());
                }
            }
        }
        
        return builder.build();
    }
    
    private PractitionerInfo extractPractitioner(Bundle bundle) {
        Practitioner practitioner = findResource(bundle, Practitioner.class);
        if (practitioner == null) {
            log.warn("No Practitioner resource found in bundle");
            return null;
        }
        
        PractitionerInfo.PractitionerInfoBuilder builder = PractitionerInfo.builder();
        
        // NPI
        builder.npi(extractNpi(practitioner));
        
        // Name
        if (practitioner.hasName() && !practitioner.getName().isEmpty()) {
            HumanName name = practitioner.getName().get(0);
            if (name.hasGiven()) {
                builder.firstName(name.getGivenAsSingleString());
            }
            if (name.hasFamily()) {
                builder.lastName(name.getFamily());
            }
        }
        
        // Taxonomy (from qualification)
        if (practitioner.hasQualification()) {
            for (Practitioner.PractitionerQualificationComponent qual : practitioner.getQualification()) {
                if (qual.hasCode() && qual.getCode().hasCoding()) {
                    Coding coding = qual.getCode().getCoding().get(0);
                    if (coding.hasCode()) {
                        builder.taxonomyCode(coding.getCode());
                        if (coding.hasDisplay()) {
                            builder.specialty(coding.getDisplay());
                        }
                        break;
                    }
                }
            }
        }
        
        // Contact
        if (practitioner.hasTelecom()) {
            for (ContactPoint contact : practitioner.getTelecom()) {
                if (contact.getSystem() == ContactPoint.ContactPointSystem.PHONE && builder.build().getPhone() == null) {
                    builder.phone(contact.getValue());
                } else if (contact.getSystem() == ContactPoint.ContactPointSystem.EMAIL && builder.build().getEmail() == null) {
                    builder.email(contact.getValue());
                }
            }
        }
        
        return builder.build();
    }
    
    private ClaimInfo extractClaim(Bundle bundle) {
        Claim claim = findResource(bundle, Claim.class);
        if (claim == null) {
            log.warn("No Claim resource found in bundle");
            return null;
        }
        
        ClaimInfo.ClaimInfoBuilder builder = ClaimInfo.builder();
        
        // IDs
        if (claim.hasIdentifier() && !claim.getIdentifier().isEmpty()) {
            builder.claimId(claim.getIdentifier().get(0).getValue());
        }
        if (bundle.hasIdentifier()) {
            builder.bundleId(bundle.getIdentifier().getValue());
        }
        
        // Type
        if (claim.hasType() && claim.getType().hasCoding()) {
            builder.claimType(claim.getType().getCoding().get(0).getCode());
        }
        
        // Use (claim, preauthorization, predetermination)
        if (claim.hasUse()) {
            builder.use(claim.getUse().toCode());
        }
        
        // Priority
        if (claim.hasPriority() && claim.getPriority().hasCoding()) {
            builder.priority(claim.getPriority().getCoding().get(0).getCode());
        }
        
        // Billable period
        if (claim.hasBillablePeriod()) {
            Period period = claim.getBillablePeriod();
            if (period.hasStart()) {
                builder.billablePeriodStart(period.getStart().toString());
                builder.serviceDate(convertToLocalDate(period.getStart()));
            }
            if (period.hasEnd()) {
                builder.billablePeriodEnd(period.getEnd().toString());
            }
        }
        
        // Diagnosis codes
        builder.diagnosisCodes(extractDiagnosisCodes(claim));
        
        // Procedure codes
        builder.procedureCodes(extractProcedureCodes(claim));
        
        // Total
        builder.totalAmount(calculateTotal(claim));
        builder.currency("USD");
        
        // Attachments
        builder.attachmentCount(countSupportingInfo(claim));
        
        return builder.build();
    }
    
    private List<DiagnosisCode> extractDiagnosisCodes(Claim claim) {
        List<DiagnosisCode> codes = new ArrayList<>();
        
        if (!claim.hasDiagnosis()) {
            return codes;
        }
        
        for (Claim.DiagnosisComponent diagnosis : claim.getDiagnosis()) {
            if (diagnosis.hasDiagnosisCodeableConcept() && diagnosis.getDiagnosisCodeableConcept().hasCoding()) {
                Coding coding = diagnosis.getDiagnosisCodeableConcept().getCoding().get(0);
                
                DiagnosisCode dc = DiagnosisCode.builder()
                        .code(coding.getCode())
                        .display(coding.getDisplay())
                        .system(coding.getSystem())
                        .sequence(diagnosis.getSequence())
                        .build();
                
                // Type (principal, admitting, etc.)
                if (diagnosis.hasType() && !diagnosis.getType().isEmpty() && 
                    diagnosis.getType().get(0).hasCoding()) {
                    dc.setType(diagnosis.getType().get(0).getCoding().get(0).getCode());
                }
                
                codes.add(dc);
            }
        }
        
        return codes;
    }
    
    private List<ProcedureCode> extractProcedureCodes(Claim claim) {
        List<ProcedureCode> codes = new ArrayList<>();
        
        if (!claim.hasItem()) {
            return codes;
        }
        
        for (Claim.ItemComponent item : claim.getItem()) {
            if (item.hasProductOrService() && item.getProductOrService().hasCoding()) {
                Coding coding = item.getProductOrService().getCoding().get(0);
                
                ProcedureCode pc = ProcedureCode.builder()
                        .code(coding.getCode())
                        .display(coding.getDisplay())
                        .system(coding.getSystem())
                        .sequence(item.getSequence())
                        .build();
                
                // Quantity
                if (item.hasQuantity()) {
                    pc.setQuantity(item.getQuantity().getValue().intValue());
                }
                
                // Charge amount
                if (item.hasNet()) {
                    pc.setChargeAmount(item.getNet().getValue());
                }
                
                // Service date
                if (item.hasServicedDateType()) {
                    pc.setServiceDate(convertToLocalDate(item.getServicedDateType().getValue()));
                } else if (item.hasServicedPeriod() && item.getServicedPeriod().hasStart()) {
                    pc.setServiceDate(convertToLocalDate(item.getServicedPeriod().getStart()));
                }
                
                codes.add(pc);
            }
        }
        
        return codes;
    }
    
    private BigDecimal calculateTotal(Claim claim) {
        if (claim.hasTotal()) {
            return claim.getTotal().getValue();
        }
        
        // Sum from items if total not present
        BigDecimal total = BigDecimal.ZERO;
        if (claim.hasItem()) {
            for (Claim.ItemComponent item : claim.getItem()) {
                if (item.hasNet()) {
                    total = total.add(item.getNet().getValue());
                }
            }
        }
        
        return total;
    }
    
    private int countSupportingInfo(Claim claim) {
        return claim.hasSupportingInfo() ? claim.getSupportingInfo().size() : 0;
    }
    
    private boolean isUrgent(ClaimInfo claim) {
        return claim.getPriority() != null && 
               (claim.getPriority().equalsIgnoreCase("stat") || 
                claim.getPriority().equalsIgnoreCase("urgent"));
    }
    
    private String extractMemberId(Patient patient) {
        if (!patient.hasIdentifier()) {
            return null;
        }
        
        // Look for member ID system
        for (Identifier id : patient.getIdentifier()) {
            String system = id.getSystem();
            if (system != null && (system.contains("member") || system.contains("subscriber"))) {
                return id.getValue();
            }
        }
        
        // Fallback to first identifier
        return patient.getIdentifier().get(0).getValue();
    }
    
    private String extractNpi(Practitioner practitioner) {
        if (!practitioner.hasIdentifier()) {
            return null;
        }
        
        // Look for NPI system
        for (Identifier id : practitioner.getIdentifier()) {
            String system = id.getSystem();
            if (system != null && system.contains("us-npi")) {
                return id.getValue();
            }
        }
        
        // Fallback to first identifier
        return practitioner.getIdentifier().get(0).getValue();
    }
    
    private <T extends Resource> T findResource(Bundle bundle, Class<T> resourceType) {
        if (!bundle.hasEntry()) {
            return null;
        }
        
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.hasResource()) {
                Resource resource = entry.getResource();
                if (resourceType.isInstance(resource)) {
                    return resourceType.cast(resource);
                }
            }
        }
        
        return null;
    }
    
    private LocalDate convertToLocalDate(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
