package com.anthem.pagw.parser.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parsed claim data extracted from FHIR bundle.
 */
public class ParsedClaim {
    
    private String pagwId;
    private String tenant;
    private String claimId;
    private String claimType;
    private String use;
    private String status;
    private String created;
    private String priority;
    
    private BigDecimal totalValue;
    private String totalCurrency;
    
    private String providerReference;
    private String patientReference;
    
    private int insuranceSequence;
    private boolean insuranceFocal;
    private String coverageReference;
    
    private List<Map<String, Object>> lineItems = new ArrayList<>();
    private List<Map<String, Object>> attachments = new ArrayList<>();
    
    private Map<String, Object> patientData;
    private Map<String, Object> practitionerData;
    private Map<String, Object> organizationData;

    // Getters and Setters
    public String getPagwId() {
        return pagwId;
    }

    public void setPagwId(String pagwId) {
        this.pagwId = pagwId;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getClaimId() {
        return claimId;
    }

    public void setClaimId(String claimId) {
        this.claimId = claimId;
    }

    public String getClaimType() {
        return claimType;
    }

    public void setClaimType(String claimType) {
        this.claimType = claimType;
    }

    public String getUse() {
        return use;
    }

    public void setUse(String use) {
        this.use = use;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public void setTotalValue(BigDecimal totalValue) {
        this.totalValue = totalValue;
    }

    public String getTotalCurrency() {
        return totalCurrency;
    }

    public void setTotalCurrency(String totalCurrency) {
        this.totalCurrency = totalCurrency;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public String getPatientReference() {
        return patientReference;
    }

    public void setPatientReference(String patientReference) {
        this.patientReference = patientReference;
    }

    public int getInsuranceSequence() {
        return insuranceSequence;
    }

    public void setInsuranceSequence(int insuranceSequence) {
        this.insuranceSequence = insuranceSequence;
    }

    public boolean isInsuranceFocal() {
        return insuranceFocal;
    }

    public void setInsuranceFocal(boolean insuranceFocal) {
        this.insuranceFocal = insuranceFocal;
    }

    public String getCoverageReference() {
        return coverageReference;
    }

    public void setCoverageReference(String coverageReference) {
        this.coverageReference = coverageReference;
    }

    public List<Map<String, Object>> getLineItems() {
        return lineItems;
    }

    public void setLineItems(List<Map<String, Object>> lineItems) {
        this.lineItems = lineItems;
    }

    public List<Map<String, Object>> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<Map<String, Object>> attachments) {
        this.attachments = attachments;
    }

    public Map<String, Object> getPatientData() {
        return patientData;
    }

    public void setPatientData(Map<String, Object> patientData) {
        this.patientData = patientData;
    }

    public Map<String, Object> getPractitionerData() {
        return practitionerData;
    }

    public void setPractitionerData(Map<String, Object> practitionerData) {
        this.practitionerData = practitionerData;
    }

    public Map<String, Object> getOrganizationData() {
        return organizationData;
    }

    public void setOrganizationData(Map<String, Object> organizationData) {
        this.organizationData = organizationData;
    }

    // Bundle metadata
    private String bundleType;
    private String bundleId;

    public String getBundleType() {
        return bundleType;
    }

    public void setBundleType(String bundleType) {
        this.bundleType = bundleType;
    }

    public String getBundleId() {
        return bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }
}
