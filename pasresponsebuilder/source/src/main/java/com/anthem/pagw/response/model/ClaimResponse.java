package com.anthem.pagw.response.model;

import java.util.ArrayList;
import java.util.List;

/**
 * FHIR ClaimResponse resource model.
 */
public class ClaimResponse {
    
    // FHIR metadata
    private String resourceType;
    private String id;
    private List<Identifier> identifier;
    
    // Status
    private String status;
    private String use;
    
    // References
    private String requestReference;
    private String patientReference;
    private String insurerReference;
    private String insurerDisplay;
    
    // Outcome
    private String outcome;
    private String disposition;
    private String preAuthRef;
    
    // Timestamps
    private String created;
    
    // Details
    private List<ProcessNote> processNote;
    private List<ClaimError> errors;

    // Getters and Setters
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public List<Identifier> getIdentifier() { return identifier; }
    public void setIdentifier(List<Identifier> identifier) { this.identifier = identifier; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getUse() { return use; }
    public void setUse(String use) { this.use = use; }
    
    public String getRequestReference() { return requestReference; }
    public void setRequestReference(String requestReference) { this.requestReference = requestReference; }
    
    public String getPatientReference() { return patientReference; }
    public void setPatientReference(String patientReference) { this.patientReference = patientReference; }
    
    public String getInsurerReference() { return insurerReference; }
    public void setInsurerReference(String insurerReference) { this.insurerReference = insurerReference; }
    
    public String getInsurerDisplay() { return insurerDisplay; }
    public void setInsurerDisplay(String insurerDisplay) { this.insurerDisplay = insurerDisplay; }
    
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    
    public String getDisposition() { return disposition; }
    public void setDisposition(String disposition) { this.disposition = disposition; }
    
    public String getPreAuthRef() { return preAuthRef; }
    public void setPreAuthRef(String preAuthRef) { this.preAuthRef = preAuthRef; }
    
    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }
    
    public List<ProcessNote> getProcessNote() { return processNote; }
    public void setProcessNote(List<ProcessNote> processNote) { this.processNote = processNote; }
    
    public List<ClaimError> getErrors() { return errors; }
    public void setErrors(List<ClaimError> errors) { this.errors = errors; }
    
    public void addProcessNote(int number, String text, String type) {
        if (this.processNote == null) {
            this.processNote = new ArrayList<>();
        }
        ProcessNote note = new ProcessNote();
        note.setNumber(number);
        note.setText(text);
        note.setType(type);
        this.processNote.add(note);
    }

    // Nested classes
    public static class Identifier {
        private String system;
        private String value;
        
        public String getSystem() { return system; }
        public void setSystem(String system) { this.system = system; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
    
    public static class ProcessNote {
        private int number;
        private String type;
        private String text;
        
        public int getNumber() { return number; }
        public void setNumber(int number) { this.number = number; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }
    
    public static class ClaimError {
        private String code;
        private String message;
        private String severity;
        
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
    }
}
