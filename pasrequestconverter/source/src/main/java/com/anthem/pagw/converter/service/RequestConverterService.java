package com.anthem.pagw.converter.service;

import com.anthem.pagw.core.model.PagwMessage;
import com.anthem.pagw.core.util.JsonUtils;
import com.anthem.pagw.converter.model.ConvertedPayload;
import com.anthem.pagw.converter.model.ConversionResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for converting enriched FHIR data to per-target payloads.
 * Creates target-specific formats for downstream claims systems.
 */
@Service
public class RequestConverterService {

    private static final Logger log = LoggerFactory.getLogger(RequestConverterService.class);

    /**
     * Convert enriched FHIR data to target-specific payload.
     * 
     * @param enrichedData The enriched claim data JSON
     * @param message The PAGW message context
     * @return ConversionResult with converted payload
     */
    public ConversionResult convertPayload(String enrichedData, PagwMessage message) {
        ConversionResult result = new ConversionResult();
        
        try {
            JsonNode data = JsonUtils.parseJson(enrichedData);
            
            ConvertedPayload payload = new ConvertedPayload();
            
            // Core identifiers
            payload.setPagwId(message.getPagwId());
            payload.setTenant(message.getTenant());
            payload.setSourceClaimId(data.path("claimId").asText());
            payload.setConvertedAt(Instant.now());
            
            // Determine target system based on claim type
            String claimType = data.path("claimType").asText();
            String targetSystem = determineTargetSystem(claimType, data);
            payload.setTargetSystem(targetSystem);
            result.setTargetSystem(targetSystem);
            
            // Convert claim details to target format
            payload.setClaimType(convertClaimType(claimType, targetSystem));
            payload.setUse(data.path("use").asText());
            payload.setStatus("PENDING");
            payload.setPriority(convertPriority(data.path("priority").asText(), targetSystem));
            
            // Convert dates
            String created = data.path("created").asText();
            if (!created.isEmpty()) {
                payload.setServiceDate(LocalDate.parse(created.substring(0, 10)));
            }
            payload.setReceivedDate(LocalDate.now());
            
            // Convert amounts
            payload.setTotalAmount(data.path("totalValue").decimalValue());
            payload.setCurrency(data.path("totalCurrency").asText("USD"));
            
            // Convert patient to target format
            payload.setPatient(convertPatient(data.path("patientData"), targetSystem));
            
            // Convert provider to target format
            payload.setProvider(convertProvider(data, targetSystem));
            
            // Convert insurance
            payload.setInsurance(convertInsurance(data, targetSystem));
            
            // Convert line items
            payload.setLineItems(convertLineItems(data.path("lineItems"), targetSystem));
            
            // Add eligibility data if present
            if (data.has("eligibilityData")) {
                payload.setEligibility(convertEligibility(data.path("eligibilityData"), targetSystem));
            }
            
            // Add target-specific headers
            payload.setTargetHeaders(buildTargetHeaders(targetSystem, message));
            
            // Add processing metadata
            payload.setProcessingMetadata(Map.of(
                    "convertedAt", Instant.now().toString(),
                    "converterVersion", "1.0",
                    "sourceFormat", "FHIR_R4",
                    "targetFormat", targetSystem + "_V1"
            ));
            
            result.setConvertedPayload(payload);
            result.setSuccess(true);
            
            log.info("Converted payload: pagwId={}, targetSystem={}", 
                    message.getPagwId(), targetSystem);
            
        } catch (Exception e) {
            log.error("Conversion error: pagwId={}", message.getPagwId(), e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
        }
        
        return result;
    }

    private String determineTargetSystem(String claimType, JsonNode data) {
        // Determine target system based on claim type and other factors
        switch (claimType.toLowerCase()) {
            case "professional":
                return "CLAIMS_PRO";
            case "institutional":
                return "CLAIMS_INST";
            case "pharmacy":
                return "CLAIMS_RX";
            case "oral":
            case "dental":
                return "CLAIMS_DENTAL";
            case "vision":
                return "CLAIMS_VISION";
            default:
                return "CLAIMS_GENERIC";
        }
    }

    private String convertClaimType(String fhirType, String targetSystem) {
        // Convert FHIR claim type to target-specific format
        switch (targetSystem) {
            case "CLAIMS_PRO":
                return "837P";
            case "CLAIMS_INST":
                return "837I";
            case "CLAIMS_RX":
                return "NCPDP";
            case "CLAIMS_DENTAL":
                return "837D";
            default:
                return fhirType.toUpperCase();
        }
    }

    private int convertPriority(String fhirPriority, String targetSystem) {
        switch (fhirPriority.toLowerCase()) {
            case "stat":
                return 1;
            case "urgent":
                return 2;
            case "normal":
            default:
                return 5;
        }
    }

    private ConvertedPayload.Patient convertPatient(JsonNode patientData, String targetSystem) {
        ConvertedPayload.Patient patient = new ConvertedPayload.Patient();
        patient.setId(patientData.path("id").asText());
        patient.setMemberId(patientData.path("memberId").asText());
        patient.setFirstName(patientData.path("firstName").asText());
        patient.setLastName(patientData.path("lastName").asText());
        patient.setDateOfBirth(patientData.path("dateOfBirth").asText());
        patient.setGender(patientData.path("gender").asText());
        return patient;
    }

    private ConvertedPayload.Provider convertProvider(JsonNode data, String targetSystem) {
        ConvertedPayload.Provider provider = new ConvertedPayload.Provider();
        JsonNode providerData = data.path("providerData");
        provider.setId(providerData.path("id").asText());
        provider.setNpi(providerData.path("npi").asText());
        provider.setName(providerData.path("name").asText());
        provider.setSpecialty(providerData.path("specialty").asText());
        provider.setNetworkStatus(providerData.path("networkStatus").asText());
        provider.setTier(providerData.path("tier").asText());
        return provider;
    }

    private ConvertedPayload.Insurance convertInsurance(JsonNode data, String targetSystem) {
        ConvertedPayload.Insurance insurance = new ConvertedPayload.Insurance();
        JsonNode insuranceData = data.path("insuranceData");
        insurance.setSequence(insuranceData.path("sequence").asInt(1));
        insurance.setPrimary(insuranceData.path("primary").asBoolean(true));
        insurance.setCoverageReference(insuranceData.path("coverageReference").asText());
        insurance.setPlanName(insuranceData.path("planName").asText());
        insurance.setPlanType(insuranceData.path("planType").asText());
        insurance.setGroupNumber(insuranceData.path("groupNumber").asText());
        return insurance;
    }

    private List<ConvertedPayload.LineItem> convertLineItems(JsonNode items, String targetSystem) {
        List<ConvertedPayload.LineItem> lineItems = new ArrayList<>();
        if (items.isArray()) {
            for (JsonNode item : items) {
                ConvertedPayload.LineItem li = new ConvertedPayload.LineItem();
                li.setSequence(item.path("sequence").asInt());
                li.setServiceCode(item.path("code").asText());
                li.setQuantity(item.path("quantity").asInt(1));
                li.setUnitPrice(item.path("unitPrice").decimalValue());
                li.setNetAmount(item.path("netAmount").decimalValue());
                lineItems.add(li);
            }
        }
        return lineItems;
    }

    private ConvertedPayload.Eligibility convertEligibility(JsonNode eligData, String targetSystem) {
        ConvertedPayload.Eligibility elig = new ConvertedPayload.Eligibility();
        elig.setStatus(eligData.path("status").asText());
        elig.setEffectiveDate(eligData.path("effectiveDate").asText());
        elig.setTerminationDate(eligData.path("terminationDate").asText());
        elig.setPlanName(eligData.path("planName").asText());
        elig.setDeductibleMet(eligData.path("deductibleMet").asBoolean());
        elig.setCopay(eligData.path("copay").decimalValue());
        elig.setCoinsurance(eligData.path("coinsurance").decimalValue());
        return elig;
    }

    private Map<String, String> buildTargetHeaders(String targetSystem, PagwMessage message) {
        return Map.of(
                "X-Target-System", targetSystem,
                "X-PAGW-ID", message.getPagwId(),
                "X-Tenant", message.getTenant() != null ? message.getTenant() : "default",
                "X-Correlation-ID", message.getMessageId()
        );
    }
}
