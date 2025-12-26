package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisProcedureValidationRuleTest {

    private DiagnosisProcedureValidationRule rule;
    private ObjectMapper mapper;
    private PagwMessage message;

    @BeforeEach
    void setUp() {
        rule = new DiagnosisProcedureValidationRule();
        mapper = new ObjectMapper();
        message = PagwMessage.builder()
                .pagwId("PAGW-TEST-001")
                .tenant("test-tenant")
                .build();
    }

    @Test
    void testIsApplicable_withDiagnosisCodes() {
        ObjectNode data = mapper.createObjectNode();
        data.putArray("diagnosisCodes");
        assertThat(rule.isApplicable(data, message)).isTrue();
    }

    @Test
    void testIsApplicable_withLineItems() {
        ObjectNode data = mapper.createObjectNode();
        data.putArray("lineItems");
        assertThat(rule.isApplicable(data, message)).isTrue();
    }

    @Test
    void testIsApplicable_withoutCodesOrItems() {
        ObjectNode data = mapper.createObjectNode();
        assertThat(rule.isApplicable(data, message)).isFalse();
    }

    // ===== Diagnosis Code Tests =====

    @Test
    void testValidate_validIcd10Codes() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode diagCodes = data.putArray("diagnosisCodes");
        
        ObjectNode dx1 = diagCodes.addObject();
        dx1.put("code", "E11.9");
        dx1.put("system", "http://hl7.org/fhir/sid/icd-10-cm");
        dx1.put("type", "principal");
        
        ObjectNode dx2 = diagCodes.addObject();
        dx2.put("code", "I10");
        dx2.put("system", "http://hl7.org/fhir/sid/icd-10-cm");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_validIcd10WithExtension() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode diagCodes = data.putArray("diagnosisCodes");
        
        ObjectNode dx = diagCodes.addObject();
        dx.put("code", "S72.001A"); // Fracture with extension
        dx.put("system", "http://hl7.org/fhir/sid/icd-10-cm");
        dx.put("type", "principal");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_invalidIcd10Format() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode diagCodes = data.putArray("diagnosisCodes");
        
        ObjectNode dx = diagCodes.addObject();
        dx.put("code", "123456"); // Invalid - should start with letter
        dx.put("system", "http://hl7.org/fhir/sid/icd-10-cm");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("INVALID_ICD10_FORMAT");
    }

    @Test
    void testValidate_placeholderDiagnosisCode() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode diagCodes = data.putArray("diagnosisCodes");
        
        ObjectNode dx = diagCodes.addObject();
        dx.put("code", "00000");
        dx.put("system", "http://hl7.org/fhir/sid/icd-10-cm");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("INVALID_DIAGNOSIS_CODE");
    }

    @Test
    void testValidate_noPrincipalDiagnosis_warning() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode diagCodes = data.putArray("diagnosisCodes");
        
        ObjectNode dx = diagCodes.addObject();
        dx.put("code", "E11.9");
        dx.put("system", "http://hl7.org/fhir/sid/icd-10-cm");
        // No 'type' field - no principal indicated

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("NO_PRINCIPAL_DIAGNOSIS"));
    }

    @Test
    void testValidate_noDiagnosisCodes_warning() {
        ObjectNode data = mapper.createObjectNode();
        data.putArray("lineItems"); // Has line items but no diagnosis codes

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("NO_DIAGNOSIS_CODES"));
    }

    // ===== Procedure Code Tests =====

    @Test
    void testValidate_validCptCode() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode lineItems = data.putArray("lineItems");
        
        ObjectNode item = lineItems.addObject();
        item.put("productOrService", "99213");
        item.put("system", "http://www.ama-assn.org/go/cpt");
        item.put("quantity", 1);
        item.put("unitPrice", 150.00);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_validHcpcsCode() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode lineItems = data.putArray("lineItems");
        
        ObjectNode item = lineItems.addObject();
        item.put("productOrService", "A0425"); // HCPCS Level II
        item.put("quantity", 1);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_missingProcedureCode() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode lineItems = data.putArray("lineItems");
        
        ObjectNode item = lineItems.addObject();
        item.put("quantity", 1);
        // Missing productOrService

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("MISSING_PROCEDURE_CODE");
    }

    @Test
    void testValidate_placeholderProcedureCode() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode lineItems = data.putArray("lineItems");
        
        ObjectNode item = lineItems.addObject();
        item.put("productOrService", "XXXXX");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("INVALID_PROCEDURE_CODE");
    }

    @Test
    void testValidate_invalidQuantity_warning() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode lineItems = data.putArray("lineItems");
        
        ObjectNode item = lineItems.addObject();
        item.put("productOrService", "99213");
        item.put("quantity", 0); // Should be > 0

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("INVALID_QUANTITY"));
    }

    @Test
    void testValidate_missingAmount_warning() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode lineItems = data.putArray("lineItems");
        
        ObjectNode item = lineItems.addObject();
        item.put("productOrService", "99213");
        item.put("quantity", 1);
        // Missing unitPrice and net

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("MISSING_AMOUNT"));
    }

    @Test
    void testValidate_noProcedureCodes_warning() {
        ObjectNode data = mapper.createObjectNode();
        ArrayNode diagCodes = data.putArray("diagnosisCodes");
        ObjectNode dx = diagCodes.addObject();
        dx.put("code", "E11.9");
        dx.put("type", "principal");
        // No line items

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("NO_PROCEDURE_CODES"));
    }

    @Test
    void testGetName() {
        assertThat(rule.getName()).isEqualTo("DIAGNOSIS_PROCEDURE_VALIDATION");
    }

    @Test
    void testGetPriority() {
        assertThat(rule.getPriority()).isEqualTo(85);
    }
}
