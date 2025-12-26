package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class PatientValidationRuleTest {

    private PatientValidationRule rule;
    private ObjectMapper mapper;
    private PagwMessage message;

    @BeforeEach
    void setUp() {
        rule = new PatientValidationRule();
        mapper = new ObjectMapper();
        message = PagwMessage.builder()
                .pagwId("PAGW-TEST-001")
                .tenant("test-tenant")
                .build();
    }

    @Test
    void testIsApplicable_withPatientData() {
        ObjectNode data = mapper.createObjectNode();
        data.putObject("patientData");
        assertThat(rule.isApplicable(data, message)).isTrue();
    }

    @Test
    void testIsApplicable_withoutPatientData() {
        ObjectNode data = mapper.createObjectNode();
        assertThat(rule.isApplicable(data, message)).isFalse();
    }

    @Test
    void testValidate_missingPatientData() {
        ObjectNode data = mapper.createObjectNode();
        // patientData exists but is missing (isMissingNode)
        
        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("MISSING_PATIENT");
    }

    @Test
    void testValidate_completePatientData() {
        ObjectNode data = createValidPatientData();

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    // ===== Member ID Tests =====

    @Test
    void testValidate_missingIdentifier() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidName(patient);
        patient.put("birthDate", "1980-05-15");
        // No identifier array

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("MISSING_PATIENT_IDENTIFIER"));
    }

    @Test
    void testValidate_emptyMemberId() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidName(patient);
        patient.put("birthDate", "1980-05-15");
        
        ArrayNode identifiers = patient.putArray("identifier");
        ObjectNode id = identifiers.addObject();
        id.put("system", "member");
        id.put("value", ""); // Empty value

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("EMPTY_MEMBER_ID"));
    }

    @Test
    void testValidate_unusualMemberIdLength_warning() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidName(patient);
        patient.put("birthDate", "1980-05-15");
        
        ArrayNode identifiers = patient.putArray("identifier");
        ObjectNode id = identifiers.addObject();
        id.put("system", "member");
        id.put("value", "AB"); // Too short

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("UNUSUAL_MEMBER_ID_LENGTH"));
    }

    @Test
    void testValidate_noMemberIdSystem_warning() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidName(patient);
        patient.put("birthDate", "1980-05-15");
        
        ArrayNode identifiers = patient.putArray("identifier");
        ObjectNode id = identifiers.addObject();
        id.put("system", "http://hospital.org/mrn"); // Not a member ID system
        id.put("value", "12345678");

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("NO_MEMBER_ID_SYSTEM"));
    }

    // ===== Name Tests =====

    @Test
    void testValidate_missingName() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidIdentifier(patient);
        patient.put("birthDate", "1980-05-15");
        // No name

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("MISSING_PATIENT_NAME"));
    }

    @Test
    void testValidate_missingFamilyName() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidIdentifier(patient);
        patient.put("birthDate", "1980-05-15");
        
        ArrayNode names = patient.putArray("name");
        ObjectNode name = names.addObject();
        name.put("given", "John");
        // No family name

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("MISSING_FAMILY_NAME"));
    }

    @Test
    void testValidate_missingGivenName() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidIdentifier(patient);
        patient.put("birthDate", "1980-05-15");
        
        ArrayNode names = patient.putArray("name");
        ObjectNode name = names.addObject();
        name.put("family", "Doe");
        // No given name

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("MISSING_GIVEN_NAME"));
    }

    // ===== Date of Birth Tests =====

    @Test
    void testValidate_missingDob() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidIdentifier(patient);
        addValidName(patient);
        // No birthDate

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("MISSING_DOB"));
    }

    @Test
    void testValidate_invalidDobFormat() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidIdentifier(patient);
        addValidName(patient);
        patient.put("birthDate", "05/15/1980"); // Wrong format

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("INVALID_DOB_FORMAT"));
    }

    @Test
    void testValidate_futureDob() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidIdentifier(patient);
        addValidName(patient);
        String future = LocalDate.now().plusDays(30).format(DateTimeFormatter.ISO_LOCAL_DATE);
        patient.put("birthDate", future);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("FUTURE_DOB"));
    }

    @Test
    void testValidate_tooOldDob() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        addValidIdentifier(patient);
        addValidName(patient);
        patient.put("birthDate", "1850-01-01"); // Over 130 years old

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).anyMatch(e -> e.getCode().equals("INVALID_DOB"));
    }

    // ===== Gender Tests =====

    @Test
    void testValidate_missingGender_warning() {
        ObjectNode data = createValidPatientData();
        data.with("patientData").remove("gender");

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("MISSING_GENDER"));
    }

    @Test
    void testValidate_invalidGender_warning() {
        ObjectNode data = createValidPatientData();
        data.with("patientData").put("gender", "M"); // Should be 'male'

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("INVALID_GENDER"));
    }

    @Test
    void testValidate_validGenders() {
        for (String gender : new String[]{"male", "female", "other", "unknown"}) {
            ObjectNode data = createValidPatientData();
            data.with("patientData").put("gender", gender);

            var result = rule.validate(data, message);

            assertThat(result.getWarnings())
                    .noneMatch(w -> w.getCode().equals("INVALID_GENDER"));
        }
    }

    // ===== Address Tests =====

    @Test
    void testValidate_missingAddress_warning() {
        ObjectNode data = createValidPatientData();
        data.with("patientData").remove("address");

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("MISSING_ADDRESS"));
    }

    @Test
    void testValidate_invalidState_warning() {
        ObjectNode data = createValidPatientData();
        ArrayNode addresses = data.with("patientData").putArray("address");
        ObjectNode addr = addresses.addObject();
        addr.put("state", "XX"); // Invalid state code

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("UNKNOWN_STATE"));
    }

    @Test
    void testValidate_invalidPostalCode_warning() {
        ObjectNode data = createValidPatientData();
        ArrayNode addresses = data.with("patientData").putArray("address");
        ObjectNode addr = addresses.addObject();
        addr.put("state", "GA");
        addr.put("postalCode", "ABCDE"); // Invalid postal code

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("INVALID_POSTAL_CODE"));
    }

    @Test
    void testValidate_validPostalCodes() {
        for (String zip : new String[]{"30301", "30301-1234"}) {
            ObjectNode data = createValidPatientData();
            ArrayNode addresses = data.with("patientData").putArray("address");
            ObjectNode addr = addresses.addObject();
            addr.put("state", "GA");
            addr.put("postalCode", zip);

            var result = rule.validate(data, message);

            assertThat(result.getWarnings())
                    .noneMatch(w -> w.getCode().equals("INVALID_POSTAL_CODE"));
        }
    }

    @Test
    void testGetName() {
        assertThat(rule.getName()).isEqualTo("PATIENT_VALIDATION");
    }

    @Test
    void testGetPriority() {
        assertThat(rule.getPriority()).isEqualTo(95);
    }

    // ===== Helper Methods =====

    private ObjectNode createValidPatientData() {
        ObjectNode data = mapper.createObjectNode();
        ObjectNode patient = data.putObject("patientData");
        
        addValidIdentifier(patient);
        addValidName(patient);
        patient.put("birthDate", "1980-05-15");
        patient.put("gender", "male");
        
        ArrayNode addresses = patient.putArray("address");
        ObjectNode addr = addresses.addObject();
        addr.put("line", "123 Main St");
        addr.put("city", "Atlanta");
        addr.put("state", "GA");
        addr.put("postalCode", "30301");
        
        return data;
    }

    private void addValidIdentifier(ObjectNode patient) {
        ArrayNode identifiers = patient.putArray("identifier");
        ObjectNode id = identifiers.addObject();
        id.put("system", "http://example.org/fhir/identifier/member");
        id.put("value", "MBR123456789");
    }

    private void addValidName(ObjectNode patient) {
        ArrayNode names = patient.putArray("name");
        ObjectNode name = names.addObject();
        name.put("family", "Doe");
        name.put("given", "John");
    }
}
