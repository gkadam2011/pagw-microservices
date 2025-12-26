package com.anthem.pagw.validator.rules;

import com.anthem.pagw.core.model.PagwMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DaVinciPasValidationRuleTest {

    private DaVinciPasValidationRule rule;
    private ObjectMapper mapper;
    private PagwMessage message;

    @BeforeEach
    void setUp() {
        rule = new DaVinciPasValidationRule();
        mapper = new ObjectMapper();
        message = PagwMessage.builder()
                .pagwId("PAGW-TEST-001")
                .tenant("test-tenant")
                .build();
    }

    @Test
    void testIsApplicable_alwaysTrue() {
        ObjectNode data = mapper.createObjectNode();
        assertThat(rule.isApplicable(data, message)).isTrue();
    }

    @Test
    void testValidate_validPreauthorization() {
        ObjectNode data = mapper.createObjectNode();
        data.put("use", "preauthorization");
        data.put("status", "active");
        data.put("priority", "normal");
        data.put("insuranceFocal", true);

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_validPredetermination() {
        ObjectNode data = mapper.createObjectNode();
        data.put("use", "predetermination");
        data.put("status", "active");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void testValidate_missingClaimUse() {
        ObjectNode data = mapper.createObjectNode();
        data.put("status", "active");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("MISSING_CLAIM_USE");
    }

    @Test
    void testValidate_invalidClaimUse() {
        ObjectNode data = mapper.createObjectNode();
        data.put("use", "claim"); // Wrong use - should be preauthorization
        data.put("status", "active");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("INVALID_CLAIM_USE");
        assertThat(result.getErrors().get(0).getMessage()).contains("preauthorization");
    }

    @Test
    void testValidate_invalidClaimStatus() {
        ObjectNode data = mapper.createObjectNode();
        data.put("use", "preauthorization");
        data.put("status", "draft"); // Should be 'active' for new submissions

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).getCode()).isEqualTo("INVALID_CLAIM_STATUS");
    }

    @Test
    void testValidate_unexpectedBundleType_warning() {
        ObjectNode data = mapper.createObjectNode();
        data.put("use", "preauthorization");
        data.put("status", "active");
        data.put("bundleType", "transaction"); // Should be 'collection' or 'batch'

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("UNEXPECTED_BUNDLE_TYPE"));
    }

    @Test
    void testValidate_missingPriority_warning() {
        ObjectNode data = mapper.createObjectNode();
        data.put("use", "preauthorization");
        data.put("status", "active");

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("MISSING_PRIORITY"));
    }

    @Test
    void testValidate_invalidPriority_warning() {
        ObjectNode data = mapper.createObjectNode();
        data.put("use", "preauthorization");
        data.put("status", "active");
        data.put("priority", "high"); // Should be normal, urgent, stat

        var result = rule.validate(data, message);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("INVALID_PRIORITY"));
    }

    @Test
    void testValidate_missingPasProfile_warning() {
        ObjectNode data = mapper.createObjectNode();
        data.put("use", "preauthorization");
        data.put("status", "active");
        data.put("priority", "normal");

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("MISSING_PAS_PROFILE"));
    }

    @Test
    void testValidate_noSupportingDocs_warning() {
        ObjectNode data = mapper.createObjectNode();
        data.put("use", "preauthorization");
        data.put("status", "active");
        data.put("priority", "normal");
        data.put("claimType", "professional");
        data.putArray("attachments"); // Empty attachments

        var result = rule.validate(data, message);

        assertThat(result.getWarnings()).anyMatch(w -> w.getCode().equals("NO_SUPPORTING_DOCS"));
    }

    @Test
    void testGetName() {
        assertThat(rule.getName()).isEqualTo("DA_VINCI_PAS_VALIDATION");
    }

    @Test
    void testGetPriority() {
        assertThat(rule.getPriority()).isEqualTo(100); // Highest priority
    }
}
