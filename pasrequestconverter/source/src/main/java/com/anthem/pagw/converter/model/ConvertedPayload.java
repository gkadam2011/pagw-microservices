package com.anthem.pagw.converter.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Converted payload format for target claims systems.
 */
public class ConvertedPayload {
    
    // Identifiers
    private String pagwId;
    private String tenant;
    private String sourceClaimId;
    private String targetSystem;
    
    // Timestamps
    private Instant convertedAt;
    private LocalDate serviceDate;
    private LocalDate receivedDate;
    
    // Claim details
    private String claimType;
    private String use;
    private String status;
    private int priority;
    
    // Amounts
    private BigDecimal totalAmount;
    private String currency;
    
    // Related entities
    private Patient patient;
    private Provider provider;
    private Insurance insurance;
    private Eligibility eligibility;
    private List<LineItem> lineItems;
    
    // Target-specific
    private Map<String, String> targetHeaders;
    private Map<String, String> processingMetadata;

    // Getters and Setters
    public String getPagwId() { return pagwId; }
    public void setPagwId(String pagwId) { this.pagwId = pagwId; }
    
    public String getTenant() { return tenant; }
    public void setTenant(String tenant) { this.tenant = tenant; }
    
    public String getSourceClaimId() { return sourceClaimId; }
    public void setSourceClaimId(String sourceClaimId) { this.sourceClaimId = sourceClaimId; }
    
    public String getTargetSystem() { return targetSystem; }
    public void setTargetSystem(String targetSystem) { this.targetSystem = targetSystem; }
    
    public Instant getConvertedAt() { return convertedAt; }
    public void setConvertedAt(Instant convertedAt) { this.convertedAt = convertedAt; }
    
    public LocalDate getServiceDate() { return serviceDate; }
    public void setServiceDate(LocalDate serviceDate) { this.serviceDate = serviceDate; }
    
    public LocalDate getReceivedDate() { return receivedDate; }
    public void setReceivedDate(LocalDate receivedDate) { this.receivedDate = receivedDate; }
    
    public String getClaimType() { return claimType; }
    public void setClaimType(String claimType) { this.claimType = claimType; }
    
    public String getUse() { return use; }
    public void setUse(String use) { this.use = use; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    
    public Patient getPatient() { return patient; }
    public void setPatient(Patient patient) { this.patient = patient; }
    
    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }
    
    public Insurance getInsurance() { return insurance; }
    public void setInsurance(Insurance insurance) { this.insurance = insurance; }
    
    public Eligibility getEligibility() { return eligibility; }
    public void setEligibility(Eligibility eligibility) { this.eligibility = eligibility; }
    
    public List<LineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<LineItem> lineItems) { this.lineItems = lineItems; }
    
    public Map<String, String> getTargetHeaders() { return targetHeaders; }
    public void setTargetHeaders(Map<String, String> targetHeaders) { this.targetHeaders = targetHeaders; }
    
    public Map<String, String> getProcessingMetadata() { return processingMetadata; }
    public void setProcessingMetadata(Map<String, String> processingMetadata) { this.processingMetadata = processingMetadata; }

    // Nested classes
    public static class Patient {
        private String id;
        private String memberId;
        private String firstName;
        private String lastName;
        private String dateOfBirth;
        private String gender;
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getMemberId() { return memberId; }
        public void setMemberId(String memberId) { this.memberId = memberId; }
        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
        public String getDateOfBirth() { return dateOfBirth; }
        public void setDateOfBirth(String dateOfBirth) { this.dateOfBirth = dateOfBirth; }
        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }
    }
    
    public static class Provider {
        private String id;
        private String npi;
        private String name;
        private String specialty;
        private String networkStatus;
        private String tier;
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getNpi() { return npi; }
        public void setNpi(String npi) { this.npi = npi; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSpecialty() { return specialty; }
        public void setSpecialty(String specialty) { this.specialty = specialty; }
        public String getNetworkStatus() { return networkStatus; }
        public void setNetworkStatus(String networkStatus) { this.networkStatus = networkStatus; }
        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }
    }
    
    public static class Insurance {
        private int sequence;
        private boolean primary;
        private String coverageReference;
        private String planName;
        private String planType;
        private String groupNumber;
        
        public int getSequence() { return sequence; }
        public void setSequence(int sequence) { this.sequence = sequence; }
        public boolean isPrimary() { return primary; }
        public void setPrimary(boolean primary) { this.primary = primary; }
        public String getCoverageReference() { return coverageReference; }
        public void setCoverageReference(String coverageReference) { this.coverageReference = coverageReference; }
        public String getPlanName() { return planName; }
        public void setPlanName(String planName) { this.planName = planName; }
        public String getPlanType() { return planType; }
        public void setPlanType(String planType) { this.planType = planType; }
        public String getGroupNumber() { return groupNumber; }
        public void setGroupNumber(String groupNumber) { this.groupNumber = groupNumber; }
    }
    
    public static class Eligibility {
        private String status;
        private String effectiveDate;
        private String terminationDate;
        private String planName;
        private boolean deductibleMet;
        private BigDecimal copay;
        private BigDecimal coinsurance;
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getEffectiveDate() { return effectiveDate; }
        public void setEffectiveDate(String effectiveDate) { this.effectiveDate = effectiveDate; }
        public String getTerminationDate() { return terminationDate; }
        public void setTerminationDate(String terminationDate) { this.terminationDate = terminationDate; }
        public String getPlanName() { return planName; }
        public void setPlanName(String planName) { this.planName = planName; }
        public boolean isDeductibleMet() { return deductibleMet; }
        public void setDeductibleMet(boolean deductibleMet) { this.deductibleMet = deductibleMet; }
        public BigDecimal getCopay() { return copay; }
        public void setCopay(BigDecimal copay) { this.copay = copay; }
        public BigDecimal getCoinsurance() { return coinsurance; }
        public void setCoinsurance(BigDecimal coinsurance) { this.coinsurance = coinsurance; }
    }
    
    public static class LineItem {
        private int sequence;
        private String serviceCode;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal netAmount;
        
        public int getSequence() { return sequence; }
        public void setSequence(int sequence) { this.sequence = sequence; }
        public String getServiceCode() { return serviceCode; }
        public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
        public BigDecimal getNetAmount() { return netAmount; }
        public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }
    }
}
