package com.anthem.pagw.response.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClaimResponse Model Tests")
class ClaimResponseTest {

    @Test
    @DisplayName("Should create ClaimResponse with all fields")
    void testFullClaimResponse() {
        // Given & When
        ClaimResponse response = new ClaimResponse();
        response.setResourceType("ClaimResponse");
        response.setId("claim-resp-123");
        response.setStatus("active");
        response.setUse("claim");
        response.setOutcome("complete");
        response.setDisposition("Claim accepted for processing");
        response.setPreAuthRef("PREAUTH-12345");
        response.setCreated("2024-12-23T10:00:00Z");
        response.setRequestReference("Claim/claim-001");
        response.setPatientReference("Patient/patient-001");
        response.setInsurerReference("Organization/anthem");
        response.setInsurerDisplay("Anthem Blue Cross Blue Shield");

        // Then
        assertEquals("ClaimResponse", response.getResourceType());
        assertEquals("claim-resp-123", response.getId());
        assertEquals("active", response.getStatus());
        assertEquals("claim", response.getUse());
        assertEquals("complete", response.getOutcome());
        assertEquals("Claim accepted for processing", response.getDisposition());
        assertEquals("PREAUTH-12345", response.getPreAuthRef());
        assertEquals("2024-12-23T10:00:00Z", response.getCreated());
        assertEquals("Claim/claim-001", response.getRequestReference());
        assertEquals("Patient/patient-001", response.getPatientReference());
        assertEquals("Organization/anthem", response.getInsurerReference());
        assertEquals("Anthem Blue Cross Blue Shield", response.getInsurerDisplay());
    }

    @Test
    @DisplayName("Should create and manage identifiers")
    void testIdentifiers() {
        // Given
        ClaimResponse response = new ClaimResponse();
        List<ClaimResponse.Identifier> identifiers = new ArrayList<>();
        
        ClaimResponse.Identifier id1 = new ClaimResponse.Identifier();
        id1.setSystem("urn:anthem:pagw");
        id1.setValue("PAGW-001");
        identifiers.add(id1);
        
        ClaimResponse.Identifier id2 = new ClaimResponse.Identifier();
        id2.setSystem("urn:external:payer");
        id2.setValue("EXT-001");
        identifiers.add(id2);
        
        // When
        response.setIdentifier(identifiers);

        // Then
        assertNotNull(response.getIdentifier());
        assertEquals(2, response.getIdentifier().size());
        assertEquals("urn:anthem:pagw", response.getIdentifier().get(0).getSystem());
        assertEquals("PAGW-001", response.getIdentifier().get(0).getValue());
        assertEquals("urn:external:payer", response.getIdentifier().get(1).getSystem());
        assertEquals("EXT-001", response.getIdentifier().get(1).getValue());
    }

    @Test
    @DisplayName("Should add process notes using helper method")
    void testAddProcessNote() {
        // Given
        ClaimResponse response = new ClaimResponse();

        // When
        response.addProcessNote(1, "Claim received", "display");
        response.addProcessNote(2, "Validation passed", "display");
        response.addProcessNote(3, "PAGW ID: PAGW-001", "print");

        // Then
        assertNotNull(response.getProcessNote());
        assertEquals(3, response.getProcessNote().size());
        
        ClaimResponse.ProcessNote note1 = response.getProcessNote().get(0);
        assertEquals(1, note1.getNumber());
        assertEquals("Claim received", note1.getText());
        assertEquals("display", note1.getType());
        
        ClaimResponse.ProcessNote note2 = response.getProcessNote().get(1);
        assertEquals(2, note2.getNumber());
        assertEquals("Validation passed", note2.getText());
        
        ClaimResponse.ProcessNote note3 = response.getProcessNote().get(2);
        assertEquals(3, note3.getNumber());
        assertEquals("PAGW ID: PAGW-001", note3.getText());
        assertEquals("print", note3.getType());
    }

    @Test
    @DisplayName("Should initialize process note list if null")
    void testAddProcessNoteWithNullList() {
        // Given
        ClaimResponse response = new ClaimResponse();
        assertNull(response.getProcessNote());

        // When
        response.addProcessNote(1, "First note", "display");

        // Then
        assertNotNull(response.getProcessNote());
        assertEquals(1, response.getProcessNote().size());
    }

    @Test
    @DisplayName("Should manage error list")
    void testErrorsList() {
        // Given
        ClaimResponse response = new ClaimResponse();
        List<ClaimResponse.ClaimError> errors = new ArrayList<>();
        
        ClaimResponse.ClaimError error1 = new ClaimResponse.ClaimError();
        error1.setCode("VALIDATION_ERROR");
        error1.setMessage("Missing required field");
        error1.setSeverity("error");
        errors.add(error1);
        
        ClaimResponse.ClaimError error2 = new ClaimResponse.ClaimError();
        error2.setCode("FORMAT_ERROR");
        error2.setMessage("Invalid date format");
        error2.setSeverity("warning");
        errors.add(error2);

        // When
        response.setErrors(errors);

        // Then
        assertNotNull(response.getErrors());
        assertEquals(2, response.getErrors().size());
        
        ClaimResponse.ClaimError retrievedError1 = response.getErrors().get(0);
        assertEquals("VALIDATION_ERROR", retrievedError1.getCode());
        assertEquals("Missing required field", retrievedError1.getMessage());
        assertEquals("error", retrievedError1.getSeverity());
        
        ClaimResponse.ClaimError retrievedError2 = response.getErrors().get(1);
        assertEquals("FORMAT_ERROR", retrievedError2.getCode());
        assertEquals("Invalid date format", retrievedError2.getMessage());
        assertEquals("warning", retrievedError2.getSeverity());
    }

    @Test
    @DisplayName("Should handle null and empty collections")
    void testNullCollections() {
        // Given
        ClaimResponse response = new ClaimResponse();

        // When & Then
        assertNull(response.getIdentifier());
        assertNull(response.getProcessNote());
        assertNull(response.getErrors());
        
        // Set empty lists
        response.setIdentifier(new ArrayList<>());
        response.setProcessNote(new ArrayList<>());
        response.setErrors(new ArrayList<>());
        
        assertNotNull(response.getIdentifier());
        assertTrue(response.getIdentifier().isEmpty());
        assertNotNull(response.getProcessNote());
        assertTrue(response.getProcessNote().isEmpty());
        assertNotNull(response.getErrors());
        assertTrue(response.getErrors().isEmpty());
    }

    @Test
    @DisplayName("Should create Identifier nested class")
    void testIdentifierNestedClass() {
        // Given & When
        ClaimResponse.Identifier identifier = new ClaimResponse.Identifier();
        identifier.setSystem("urn:test:system");
        identifier.setValue("TEST-123");

        // Then
        assertEquals("urn:test:system", identifier.getSystem());
        assertEquals("TEST-123", identifier.getValue());
    }

    @Test
    @DisplayName("Should create ProcessNote nested class")
    void testProcessNoteNestedClass() {
        // Given & When
        ClaimResponse.ProcessNote note = new ClaimResponse.ProcessNote();
        note.setNumber(5);
        note.setType("display");
        note.setText("Test note content");

        // Then
        assertEquals(5, note.getNumber());
        assertEquals("display", note.getType());
        assertEquals("Test note content", note.getText());
    }

    @Test
    @DisplayName("Should create ClaimError nested class")
    void testClaimErrorNestedClass() {
        // Given & When
        ClaimResponse.ClaimError error = new ClaimResponse.ClaimError();
        error.setCode("TEST_ERROR");
        error.setMessage("This is a test error");
        error.setSeverity("critical");

        // Then
        assertEquals("TEST_ERROR", error.getCode());
        assertEquals("This is a test error", error.getMessage());
        assertEquals("critical", error.getSeverity());
    }

    @Test
    @DisplayName("Should allow multiple process notes with same number")
    void testMultipleNotesWithSameNumber() {
        // Given
        ClaimResponse response = new ClaimResponse();

        // When
        response.addProcessNote(1, "First note", "display");
        response.addProcessNote(1, "Second note with same number", "print");

        // Then
        assertEquals(2, response.getProcessNote().size());
        // Both should have number 1
        assertEquals(1, response.getProcessNote().get(0).getNumber());
        assertEquals(1, response.getProcessNote().get(1).getNumber());
    }

    @Test
    @DisplayName("Should support typical FHIR outcome values")
    void testFhirOutcomeValues() {
        // Given
        ClaimResponse response = new ClaimResponse();

        // When & Then - Test various FHIR outcome values
        response.setOutcome("complete");
        assertEquals("complete", response.getOutcome());
        
        response.setOutcome("error");
        assertEquals("error", response.getOutcome());
        
        response.setOutcome("partial");
        assertEquals("partial", response.getOutcome());
        
        response.setOutcome("queued");
        assertEquals("queued", response.getOutcome());
    }

    @Test
    @DisplayName("Should support typical FHIR status values")
    void testFhirStatusValues() {
        // Given
        ClaimResponse response = new ClaimResponse();

        // When & Then - Test various FHIR status values
        response.setStatus("active");
        assertEquals("active", response.getStatus());
        
        response.setStatus("cancelled");
        assertEquals("cancelled", response.getStatus());
        
        response.setStatus("draft");
        assertEquals("draft", response.getStatus());
        
        response.setStatus("entered-in-error");
        assertEquals("entered-in-error", response.getStatus());
    }
}
